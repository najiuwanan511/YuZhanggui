-- Restore only orders blocked before any delivery attempt by the 2.3.22 verification circuit.
UPDATE xianyu_goods_order
SET delivery_status = 'RETRY_WAIT', next_retry_time = CURRENT_TIMESTAMP(3),
    attempt_count = 0, lease_owner = NULL, lease_expire_time = NULL,
    last_error_code = 'BUYER_VERIFICATION_PENDING',
    last_error_message = '订单详情等待核验，将自动重试；更新账号Cookie后提前恢复',
    fail_reason = '订单详情等待核验，将自动重试；更新账号Cookie后提前恢复'
WHERE state = 0
  AND COALESCE(delivered_quantity, 0) = 0
  AND COALESCE(confirm_state, 0) = 0
  AND COALESCE(content, '') = ''
  AND COALESCE(delivery_channel, '') <> 'PICKUP'
  AND COALESCE(trade_status, '') IN ('', 'PENDING_SHIPMENT')
  AND ((delivery_status = 'SUSPENDED' AND last_error_code = 'TOKEN_REFRESH_FAILED')
    OR (delivery_status = 'RETRY_WAIT' AND last_error_code = 'BUYER_VERIFICATION_PENDING'));
