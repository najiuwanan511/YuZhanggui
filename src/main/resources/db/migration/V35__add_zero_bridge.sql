ALTER TABLE xianyu_goods_auto_delivery_config
    ADD COLUMN zero_input_count INT NOT NULL DEFAULT 1 AFTER auto_confirm_shipment;

CREATE TABLE xianyu_zero_bridge_order (
    id BIGINT NOT NULL AUTO_INCREMENT,
    goods_order_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    external_order_id VARCHAR(100) NOT NULL,
    xy_goods_id VARCHAR(100) NOT NULL,
    sku_id VARCHAR(100) NULL,
    buyer_user_id VARCHAR(100) NOT NULL,
    buyer_user_name VARCHAR(256) NULL,
    sid VARCHAR(200) NOT NULL,
    expected_count INT NOT NULL DEFAULT 1,
    collected_count INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'WAITING_INPUT',
    zero_response LONGTEXT NULL,
    result_summary TEXT NULL,
    submit_attempts INT NOT NULL DEFAULT 0,
    next_submit_time DATETIME(3) NULL,
    reply_attempts INT NOT NULL DEFAULT 0,
    next_reply_time DATETIME(3) NULL,
    last_error VARCHAR(500) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_zero_bridge_goods_order (goods_order_id),
    UNIQUE KEY uk_zero_bridge_external_order (xianyu_account_id, external_order_id),
    KEY idx_zero_bridge_session (xianyu_account_id, sid, buyer_user_id, status),
    KEY idx_zero_bridge_submit (status, next_submit_time),
    KEY idx_zero_bridge_reply (status, next_reply_time),
    CONSTRAINT fk_zero_bridge_order FOREIGN KEY (goods_order_id) REFERENCES xianyu_goods_order (id) ON DELETE CASCADE,
    CONSTRAINT fk_zero_bridge_account FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE xianyu_zero_submission (
    id BIGINT NOT NULL AUTO_INCREMENT,
    bridge_order_id BIGINT NOT NULL,
    line_id VARCHAR(100) NOT NULL,
    pnm_id VARCHAR(100) NOT NULL,
    content TEXT NOT NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_zero_submission_message (bridge_order_id, pnm_id),
    UNIQUE KEY uk_zero_submission_line (bridge_order_id, line_id),
    CONSTRAINT fk_zero_submission_bridge FOREIGN KEY (bridge_order_id) REFERENCES xianyu_zero_bridge_order (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE xianyu_zero_callback_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(160) NOT NULL,
    bridge_order_id BIGINT NOT NULL,
    line_id VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_zero_callback_event (event_id),
    KEY idx_zero_callback_bridge (bridge_order_id),
    CONSTRAINT fk_zero_callback_bridge FOREIGN KEY (bridge_order_id) REFERENCES xianyu_zero_bridge_order (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
