package com.yuzhanggui.enums;

public enum DeliveryStatus {
    PENDING,
    PROCESSING,
    RETRY_WAIT,
    DELIVERED,
    CONFIRMING,
    COMPLETED,
    FAILED,
    REVIEW_REQUIRED,
    SKIPPED,
    /**
     * 兼容历史核验挂起记录；新任务使用 RETRY_WAIT 退避恢复。
     */
    SUSPENDED
}
