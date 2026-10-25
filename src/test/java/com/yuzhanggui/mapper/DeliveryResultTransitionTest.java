package com.yuzhanggui.mapper;

import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryResultTransitionTest {
    @Test
    void workerResultRemainsLeasedUntilSchedulerCompletesAndRejectsStaleWorker() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL")) {
            db.createStatement().execute("CREATE TABLE xianyu_goods_order (id BIGINT, state INT, content VARCHAR(500),fail_reason VARCHAR(500),"
                    + "delivery_status VARCHAR(32),lease_owner VARCHAR(64),lease_expire_time TIMESTAMP,"
                    + "delivered_quantity INT DEFAULT 0,expected_quantity INT DEFAULT 2,next_retry_time TIMESTAMP,"
                    + "last_error_code VARCHAR(64),last_error_message VARCHAR(500))");
            db.createStatement().execute("INSERT INTO xianyu_goods_order(id,state,delivery_status,lease_owner,lease_expire_time) "
                    + "VALUES (1,0,'PROCESSING','current-worker',DATEADD('MINUTE',5,CURRENT_TIMESTAMP))");
            String result = String.join(" ", XianyuGoodsOrderMapper.class.getMethod("updateLeasedDeliveryResult",
                    Long.class,String.class,Integer.class,String.class,String.class).getAnnotation(Update.class).value())
                    .replace("#{id}","1").replace("#{state}","-1").replace("#{content}","NULL").replace("#{failReason}","'temporary failure'");
            assertEquals(0, db.createStatement().executeUpdate(result.replace("#{workerId}","'old-worker'")));
            assertEquals(1, db.createStatement().executeUpdate(result.replace("#{workerId}","'current-worker'")));
            try(var rows = db.createStatement().executeQuery("SELECT state,delivery_status FROM xianyu_goods_order")) {
                assertTrue(rows.next()); assertEquals(-1, rows.getInt(1)); assertEquals("PROCESSING",rows.getString(2));
            }
            assertEquals(1, db.createStatement().executeUpdate(result.replace("#{workerId}","'current-worker'").replace("state = -1","state = 1")));
            String complete = String.join(" ", XianyuGoodsOrderMapper.class.getMethod("completeTask",Long.class,String.class)
                    .getAnnotation(Update.class).value()).replace("#{id}","1").replace("#{workerId}","'current-worker'");
            assertEquals(1, db.createStatement().executeUpdate(complete));
            try(var rows = db.createStatement().executeQuery("SELECT delivered_quantity,lease_owner,delivery_status FROM xianyu_goods_order")) {
                assertTrue(rows.next()); assertEquals(2, rows.getInt(1)); assertNull(rows.getString(2)); assertEquals("COMPLETED",rows.getString(3));
            }
            assertEquals(0, db.createStatement().executeUpdate(result.replace("#{workerId}","'current-worker'")));
        }
    }
}
