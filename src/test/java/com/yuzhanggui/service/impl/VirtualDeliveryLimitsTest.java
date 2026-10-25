package com.yuzhanggui.service.impl;

import com.yuzhanggui.service.DeliveryAttemptResult;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VirtualDeliveryLimitsTest {
    @Test
    void rejectsOverlongContentBeforeNetworkInsteadOfSilentlyTruncating() {
        var result = new OrderServiceImpl().consignDummyDelivery(7L, "order", "x".repeat(201), List.of());
        assertEquals(DeliveryAttemptResult.Status.REJECTED, result.status());
        assertTrue(result.message().contains("200"));
    }

    @Test
    void rejectsExcessImagesBeforeNetworkInsteadOfDroppingTheLastImage() {
        var result = new OrderServiceImpl().consignDummyDelivery(7L, "order", "content", List.of("a", "b", "c", "d"));
        assertEquals(DeliveryAttemptResult.Status.REJECTED, result.status());
        assertTrue(result.message().contains("3张"));
    }
}
