package com.yuzhanggui.mapper;

import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BuyerVerificationRecoverySqlTest {
    @Test
    void refundsAndClosedOrdersCannotStartSendingAndUncertainWorkRemainsForReview() throws Exception {
        try (var db = database()) {
            String marker = String.join(" ", XianyuGoodsOrderMapper.class.getMethod("markExternalAttemptStarted", Long.class, String.class)
                    .getAnnotation(Update.class).value()).replace("#{id}", "1").replace("#{workerId}", "'worker'");
            db.createStatement().execute("INSERT INTO xianyu_goods_order (id, delivery_status, lease_owner, lease_expire_time) "
                    + "VALUES (1,'PROCESSING','worker',DATEADD('MINUTE',5,CURRENT_TIMESTAMP))");
            assertEquals(1, db.createStatement().executeUpdate(marker));
            for (String state : java.util.List.of("REFUNDING", "REFUNDED", "CLOSED", "PENDING_PAYMENT", "SHIPPED", "COMPLETED")) {
                db.createStatement().execute("UPDATE xianyu_goods_order SET trade_status='" + state + "'");
                assertEquals(0, db.createStatement().executeUpdate(marker), state);
            }
            String skip = String.join(" ", XianyuGoodsOrderMapper.class.getMethod("skipNonDeliverableTasks").getAnnotation(Update.class).value());
            db.createStatement().execute("UPDATE xianyu_goods_order SET lease_expire_time=DATEADD('MINUTE',-5,CURRENT_TIMESTAMP)");
            assertEquals(0, db.createStatement().executeUpdate(skip)); // External send already started: never silently skip.
            db.createStatement().execute("UPDATE xianyu_goods_order SET delivery_status='RETRY_WAIT',last_error_code=NULL");
            assertEquals(1, db.createStatement().executeUpdate(skip));
        }
    }
    private Connection database() throws Exception {
        var db = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL");
        db.createStatement().execute("""
                CREATE TABLE xianyu_goods_order (
                    id BIGINT PRIMARY KEY, xianyu_account_id BIGINT DEFAULT 7,
                    state INT DEFAULT 0, delivery_status VARCHAR(32) DEFAULT 'SUSPENDED',
                    delivered_quantity INT DEFAULT 0, confirm_state INT DEFAULT 0,
                    content VARCHAR(100) DEFAULT '', delivery_channel VARCHAR(24) DEFAULT 'AUTO',
                    trade_status VARCHAR(32) DEFAULT 'PENDING_SHIPMENT', attempt_count INT DEFAULT 3,
                    next_retry_time TIMESTAMP, lease_owner VARCHAR(40), lease_expire_time TIMESTAMP,
                    last_error_code VARCHAR(64) DEFAULT 'TOKEN_REFRESH_FAILED',
                    last_error_message VARCHAR(500), fail_reason VARCHAR(500))
                """);
        return db;
    }

    private void recoveryFixtures(Connection db) throws Exception {
        for (int id = 1; id <= 10; id++) db.createStatement().execute("INSERT INTO xianyu_goods_order (id) VALUES (" + id + ")");
        db.createStatement().execute("UPDATE xianyu_goods_order SET delivery_status='RETRY_WAIT', last_error_code='BUYER_VERIFICATION_PENDING' WHERE id=2");
        db.createStatement().execute("UPDATE xianyu_goods_order SET delivery_status='COMPLETED', state=1 WHERE id=3");
        db.createStatement().execute("UPDATE xianyu_goods_order SET delivery_status='REVIEW_REQUIRED', last_error_code='DELIVERY_UNCERTAIN' WHERE id=4");
        db.createStatement().execute("UPDATE xianyu_goods_order SET delivered_quantity=1 WHERE id=5");
        db.createStatement().execute("UPDATE xianyu_goods_order SET content='already sent' WHERE id=6");
        db.createStatement().execute("UPDATE xianyu_goods_order SET trade_status='REFUNDED' WHERE id=7");
        db.createStatement().execute("UPDATE xianyu_goods_order SET delivery_channel='PICKUP' WHERE id=8");
        db.createStatement().execute("UPDATE xianyu_goods_order SET confirm_state=1 WHERE id=9");
        db.createStatement().execute("UPDATE xianyu_goods_order SET delivery_status='PROCESSING', last_error_code='EXTERNAL_SEND_STARTED' WHERE id=10");
    }

    @Test
    void queuedOrdersWaitUntilAccountRecoveryCompletes() throws Exception {
        try (var db = database()) {
            db.createStatement().execute("ALTER TABLE xianyu_goods_order ADD create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
            db.createStatement().execute("CREATE TABLE xianyu_account (id BIGINT PRIMARY KEY, status INT)");
            db.createStatement().execute("INSERT INTO xianyu_account VALUES (7,-2),(8,1),(9,0)");
            db.createStatement().execute("INSERT INTO xianyu_goods_order (id,xianyu_account_id,delivery_status) VALUES (1,7,'PENDING'),(2,8,'PENDING'),(3,9,'PENDING')");
            String sql = String.join(" ", XianyuGoodsOrderMapper.class.getMethod("lockDueTasks", int.class)
                    .getAnnotation(Select.class).value()).replace("#{limit}", "20");
            try (var rows = db.createStatement().executeQuery(sql)) {
                assertTrue(rows.next()); assertEquals(2, rows.getLong("id"));
                assertFalse(rows.next());
            }
            db.createStatement().execute("UPDATE xianyu_account SET status=1 WHERE id=7");
            try (var rows = db.createStatement().executeQuery(sql)) {
                int count = 0;
                while (rows.next()) count++;
                assertEquals(2, count);
            }
        }
    }

    @Test
    void suppliedCredentialsResumeOnlyUnsentVerificationFailuresForThatAccount() throws Exception {
        try (var db = database()) {
            recoveryFixtures(db);
            db.createStatement().execute("INSERT INTO xianyu_goods_order (id,xianyu_account_id) VALUES (11,8)");
            String sql = String.join(" ", XianyuGoodsOrderMapper.class
                    .getMethod("resumeBuyerVerificationTasks", Long.class).getAnnotation(Update.class).value())
                    .replace("#{accountId}", "7");
            assertEquals(2, db.createStatement().executeUpdate(sql));
            try (var rows = db.createStatement().executeQuery("SELECT id FROM xianyu_goods_order WHERE delivery_status='PENDING' ORDER BY id")) {
                assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
                assertTrue(rows.next()); assertEquals(2, rows.getInt(1));
                assertFalse(rows.next());
            }
        }
    }

    @Test
    void upgradeMigrationRecoversOnlyOrdersBlockedBeforeSending() throws Exception {
        try (var db = database()) {
            recoveryFixtures(db);
            String sql = Files.readString(Path.of("src/main/resources/db/migration/V37__recover_unsent_buyer_verification_tasks.sql"));
            assertEquals(2, db.createStatement().executeUpdate(sql));
            try (var rows = db.createStatement().executeQuery("SELECT COUNT(*) FROM xianyu_goods_order WHERE next_retry_time IS NOT NULL AND attempt_count=0")) {
                assertTrue(rows.next()); assertEquals(2, rows.getInt(1));
            }
        }
    }

    @Test
    void verificationWaitDoesNotSpendSendAttemptsOrRequeueExternalSends() throws Exception {
        for (String method : new String[]{"deferBuyerVerificationTask", "deferBuyerVerificationRecoveryTask"}) {
            try (var db = database()) {
                db.createStatement().execute("INSERT INTO xianyu_goods_order (id,delivery_status,lease_owner,lease_expire_time,last_error_code) "
                        + "VALUES (1,'PROCESSING','worker',DATEADD('MINUTE',5,CURRENT_TIMESTAMP),NULL)");
                String sql = String.join(" ", XianyuGoodsOrderMapper.class
                        .getMethod(method, Long.class, String.class, String.class).getAnnotation(Update.class).value())
                        .replace("#{id}", "1").replace("#{workerId}", "'worker'").replace("#{reason}", "'waiting'")
                        // H2 uses DATEADD for MySQL's DATE_ADD; all predicates and assignments run unchanged.
                        .replace("DATE_ADD(NOW(3), INTERVAL 5 MINUTE)", "DATEADD('MINUTE',5,NOW(3))")
                        .replace("DATE_ADD(NOW(3), INTERVAL 30 MINUTE)", "DATEADD('MINUTE',30,NOW(3))");
                assertEquals(1, db.createStatement().executeUpdate(sql));
                try (var rows = db.createStatement().executeQuery("SELECT attempt_count,lease_owner,delivery_status FROM xianyu_goods_order WHERE id=1")) {
                    assertTrue(rows.next()); assertEquals(2, rows.getInt(1));
                    assertNull(rows.getString(2)); assertEquals("RETRY_WAIT", rows.getString(3));
                }
                assertEquals(0, db.createStatement().executeUpdate(sql));
                db.createStatement().execute("UPDATE xianyu_goods_order SET delivery_status='PROCESSING',lease_owner='worker',"
                        + "lease_expire_time=DATEADD('MINUTE',5,CURRENT_TIMESTAMP),last_error_code='EXTERNAL_SEND_STARTED' WHERE id=1");
                assertEquals(0, db.createStatement().executeUpdate(sql));
            }
        }
    }
}
