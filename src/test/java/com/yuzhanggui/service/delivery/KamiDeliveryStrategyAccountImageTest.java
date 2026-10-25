package com.yuzhanggui.service.delivery;

import com.yuzhanggui.entity.XianyuGoodsAutoDeliveryConfig;
import com.yuzhanggui.entity.XianyuKamiConfig;
import com.yuzhanggui.entity.XianyuKamiItem;
import com.yuzhanggui.service.ApiKamiDeliveryService;
import com.yuzhanggui.service.KamiConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KamiDeliveryStrategyAccountImageTest {

    @Test
    void appliesTheImageForTheAccountThatReceivedTheOrder() {
        KamiConfigService kamiConfigService = mock(KamiConfigService.class);
        XianyuKamiConfig kamiConfig = new XianyuKamiConfig();
        kamiConfig.setId(7L);
        kamiConfig.setSourceType(1);
        XianyuKamiItem item = new XianyuKamiItem();
        item.setKamiContent("CARD-123");
        when(kamiConfigService.getConfig(7L)).thenReturn(kamiConfig);
        when(kamiConfigService.reserveKami(7L, "ORDER-1", 1)).thenReturn(List.of(item));
        when(kamiConfigService.resolveDeliveryImageUrl(kamiConfig, 2L))
                .thenReturn("https://cdn.example/account-2.jpg");

        KamiDeliveryStrategy strategy = new KamiDeliveryStrategy();
        ReflectionTestUtils.setField(strategy, "kamiConfigService", kamiConfigService);
        ReflectionTestUtils.setField(strategy, "templateRenderer", new DeliveryMessageTemplateRenderer());
        ReflectionTestUtils.setField(strategy, "apiKamiDeliveryService", mock(ApiKamiDeliveryService.class));

        XianyuGoodsAutoDeliveryConfig deliveryConfig = new XianyuGoodsAutoDeliveryConfig();
        deliveryConfig.setKamiConfigIds("7");
        String content = strategy.resolve(DeliveryContext.builder()
                .accountId(2L)
                .orderId("ORDER-1")
                .quantity(1)
                .deliveryConfig(deliveryConfig)
                .build());

        assertEquals("CARD-123", content);
        assertEquals("https://cdn.example/account-2.jpg", deliveryConfig.getAutoDeliveryImageUrl());
        verify(kamiConfigService).resolveDeliveryImageUrl(kamiConfig, 2L);
    }
}
