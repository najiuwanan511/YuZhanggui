package com.yuzhanggui.service.impl;

import com.yuzhanggui.entity.XianyuGoodsConfig;
import com.yuzhanggui.entity.XianyuGoodsOrder;
import com.yuzhanggui.entity.XianyuGoodsSku;
import com.yuzhanggui.entity.XianyuGoodsAutoDeliveryConfig;
import com.yuzhanggui.mapper.XianyuGoodsAutoDeliveryConfigMapper;
import com.yuzhanggui.mapper.XianyuGoodsConfigMapper;
import com.yuzhanggui.mapper.XianyuGoodsOrderMapper;
import com.yuzhanggui.service.BuyerBlacklistService;
import com.yuzhanggui.service.GoodsSkuService;
import com.yuzhanggui.service.ImageDimensionService;
import com.yuzhanggui.service.SentMessageSaveService;
import com.yuzhanggui.service.WebSocketService;
import com.yuzhanggui.service.delivery.OrderDetailFetcher;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoDeliveryServiceImplTest {

    @Test
    void exceptionAfterExternalRequestStartsRequiresReviewInsteadOfReplay() {
        var service = new AutoDeliveryServiceImpl();
        var orders = mock(XianyuGoodsOrderMapper.class);
        var goods = mock(XianyuGoodsConfigMapper.class);
        var accounts = mock(com.yuzhanggui.mapper.XianyuAccountMapper.class);
        var configs = mock(XianyuGoodsAutoDeliveryConfigMapper.class);
        var details = mock(OrderDetailFetcher.class);
        var platform = mock(com.yuzhanggui.service.OrderService.class);
        var resolver = mock(com.yuzhanggui.service.delivery.DeliveryStrategyResolver.class);
        var order = new XianyuGoodsOrder();
        order.setId(11L);
        order.setBuyerUserId("buyer-42");
        order.setLeaseOwner("worker");
        var goodsConfig = new XianyuGoodsConfig();
        goodsConfig.setXianyuAutoDeliveryOn(1);
        var config = new XianyuGoodsAutoDeliveryConfig();
        config.setDeliveryMode(1);
        var detail = new OrderDetailFetcher.OrderDetailInfo();
        detail.buyerUserId = "buyer-42";
        when(orders.selectById(11L)).thenReturn(order);
        when(goods.selectByAccountAndGoodsId(7L, "goods-1")).thenReturn(goodsConfig);
        when(configs.findByAccountIdAndGoodsIdNoSku(7L, "goods-1")).thenReturn(config);
        when(details.fetch(7L, "goods-1", "order-1")).thenReturn(detail);
        when(accounts.selectList(null)).thenReturn(java.util.List.of());
        when(resolver.resolve(eq(1), org.mockito.ArgumentMatchers.any())).thenReturn("delivery content");
        when(platform.consignDummyDelivery(eq(7L), eq("order-1"), eq("delivery content"), org.mockito.ArgumentMatchers.anyList()))
                .thenThrow(new IllegalStateException("connection lost after write"));
        when(orders.updateLeasedDeliveryResult(eq(11L), eq("worker"), eq(-1), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(1);
        ReflectionTestUtils.setField(service, "orderMapper", orders);
        ReflectionTestUtils.setField(service, "goodsConfigMapper", goods);
        ReflectionTestUtils.setField(service, "accountMapper", accounts);
        ReflectionTestUtils.setField(service, "autoDeliveryConfigMapper", configs);
        ReflectionTestUtils.setField(service, "orderDetailFetcher", details);
        ReflectionTestUtils.setField(service, "orderService", platform);
        ReflectionTestUtils.setField(service, "deliveryStrategyResolver", resolver);
        ReflectionTestUtils.setField(service, "messageTemplateRenderer", new com.yuzhanggui.service.delivery.DeliveryMessageTemplateRenderer());
        ReflectionTestUtils.setField(service, "goodsSkuService", mock(GoodsSkuService.class));
        ReflectionTestUtils.setField(service, "webSocketService", mock(WebSocketService.class));
        ReflectionTestUtils.setField(service, "blacklistService", mock(BuyerBlacklistService.class));
        service.executeDelivery(11L, 7L, "goods-1", "buyer-42@goofish", "order-1", "buyer", false, () -> true, () -> true);
        verify(platform).consignDummyDelivery(eq(7L), eq("order-1"), eq("delivery content"), org.mockito.ArgumentMatchers.anyList());
        verify(orders).updateLeasedDeliveryResult(eq(11L), eq("worker"), eq(-1), eq(""),
                argThat(reason -> reason.startsWith(AutoDeliveryServiceImpl.PARTIAL_DELIVERY_REVIEW_PREFIX)));
    }

    @Test
    void imageSendingStopsWhenLeaseIsLostBeforeExternalAttempt() {
        var service = new AutoDeliveryServiceImpl();
        var dimensions = mock(ImageDimensionService.class);
        var socket = mock(WebSocketService.class);
        var config = new XianyuGoodsAutoDeliveryConfig();
        config.setAutoDeliveryImageUrl("https://img.example.test/a.jpg");
        when(dimensions.resolve(config.getAutoDeliveryImageUrl())).thenReturn(new ImageDimensionService.ImageDimensions(10, 10));
        ReflectionTestUtils.setField(service, "imageDimensionService", dimensions);
        ReflectionTestUtils.setField(service, "webSocketService", socket);
        ReflectionTestUtils.setField(service, "blacklistService", mock(BuyerBlacklistService.class));
        java.util.function.BooleanSupplier denied = () -> false;
        java.util.function.BooleanSupplier allowed = () -> true;
        assertThrows(RuntimeException.class, () -> ReflectionTestUtils.invokeMethod(service, "sendDeliveryImages",
                7L, "goods-1", "cid-1", "buyer-42", config, false, denied, allowed));
        org.mockito.Mockito.verifyNoInteractions(socket);
        assertThrows(RuntimeException.class, () -> ReflectionTestUtils.invokeMethod(service, "sendDeliveryImages",
                7L, "goods-1", "cid-1", "buyer-42", config, false, allowed, denied));
        org.mockito.Mockito.verifyNoInteractions(socket);
    }

    @Test
    void sendsDeliveryImagesWithTheirActualDimensions() {
        ImageDimensionService dimensionService = mock(ImageDimensionService.class);
        WebSocketService webSocketService = mock(WebSocketService.class);
        BuyerBlacklistService blacklistService = mock(BuyerBlacklistService.class);
        SentMessageSaveService sentMessageSaveService = mock(SentMessageSaveService.class);
        XianyuGoodsAutoDeliveryConfig config = new XianyuGoodsAutoDeliveryConfig();
        config.setAutoDeliveryImageUrl("https://img.example.test/portrait.jpg");
        when(dimensionService.resolve(config.getAutoDeliveryImageUrl()))
                .thenReturn(new ImageDimensionService.ImageDimensions(720, 1280));
        when(webSocketService.sendImageMessage(
                7L, "cid-1", "buyer-42", config.getAutoDeliveryImageUrl(), 720, 1280)).thenReturn(true);

        AutoDeliveryServiceImpl service = new AutoDeliveryServiceImpl();
        ReflectionTestUtils.setField(service, "imageDimensionService", dimensionService);
        ReflectionTestUtils.setField(service, "webSocketService", webSocketService);
        ReflectionTestUtils.setField(service, "blacklistService", blacklistService);
        ReflectionTestUtils.setField(service, "sentMessageSaveService", sentMessageSaveService);

        ReflectionTestUtils.invokeMethod(service, "sendDeliveryImages",
                7L, "goods-1", "cid-1", "buyer-42", config, false);

        verify(webSocketService).sendImageMessage(
                7L, "cid-1", "buyer-42", config.getAutoDeliveryImageUrl(), 720, 1280);
    }

    @Test
    void usesTheOrderBuyerAsTheDeliveryRecipient() {
        assertEquals("buyer-42", AutoDeliveryServiceImpl.requireBuyerRecipientId("buyer-42@goofish"));
    }

    @Test
    void rejectsDeliveryWithoutAnOrderBuyer() {
        assertThrows(IllegalStateException.class,
                () -> AutoDeliveryServiceImpl.requireBuyerRecipientId(" "));
    }

    @Test
    void rejectsDeliveryToAnyConfiguredLocalAccount() {
        var localAccount = new com.yuzhanggui.entity.XianyuAccount();
        localAccount.setUnb("seller-account-2");

        assertThrows(IllegalStateException.class, () ->
                AutoDeliveryServiceImpl.requireExternalBuyerRecipientId(
                        "seller-account-2@goofish", java.util.List.of(localAccount)));
    }

    @Test
    void rejectsDeliveryToTheUserIdEmbeddedInALocalDeviceId() {
        var localAccount = new com.yuzhanggui.entity.XianyuAccount();
        localAccount.setDeviceId("11111111-1111-4111-8111-localSeller");

        assertThrows(IllegalStateException.class, () ->
                AutoDeliveryServiceImpl.requireExternalBuyerRecipientId(
                        "localSeller", java.util.List.of(localAccount)));
    }

    @Test
    void rejectsDeliveryWhenTheChatBuyerDiffersFromTheOrderBuyer() {
        assertThrows(IllegalStateException.class, () ->
                AutoDeliveryServiceImpl.requireVerifiedBuyerRecipientId("chat-buyer", "order-buyer"));
    }

    @Test
    void acceptsDeliveryOnlyWhenTheChatBuyerMatchesTheOrderBuyer() {
        assertEquals("buyer-42", AutoDeliveryServiceImpl.requireVerifiedBuyerRecipientId(
                "buyer-42@goofish", "buyer-42"));
    }

    @Test
    void usesDefaultConfigForGoodsWithoutSkus() {
        GoodsSkuService goodsSkuService = mock(GoodsSkuService.class);
        XianyuGoodsAutoDeliveryConfigMapper configMapper = mock(XianyuGoodsAutoDeliveryConfigMapper.class);
        XianyuGoodsAutoDeliveryConfig defaultConfig = new XianyuGoodsAutoDeliveryConfig();
        when(goodsSkuService.countByAccountIdAndXyGoodsId(7L, "goods-1")).thenReturn(0);
        when(configMapper.findByAccountIdAndGoodsIdNoSku(7L, "goods-1")).thenReturn(defaultConfig);

        AutoDeliveryServiceImpl service = deliveryService(goodsSkuService, configMapper);

        assertSame(defaultConfig, service.resolveDeliveryConfig(7L, "goods-1", null));
    }

    @Test
    void rejectsMultiSkuOrderWithoutSkuId() {
        GoodsSkuService goodsSkuService = mock(GoodsSkuService.class);
        when(goodsSkuService.countByAccountIdAndXyGoodsId(7L, "goods-1")).thenReturn(2);

        AutoDeliveryServiceImpl service = deliveryService(goodsSkuService, mock(XianyuGoodsAutoDeliveryConfigMapper.class));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resolveDeliveryConfig(7L, "goods-1", " "));
        assertEquals("订单缺少商品规格，已停止自动发货", error.getMessage());
    }

    @Test
    void rejectsMissingMultiSkuConfigInsteadOfFallingBackToDefault() {
        GoodsSkuService goodsSkuService = mock(GoodsSkuService.class);
        XianyuGoodsAutoDeliveryConfigMapper configMapper = mock(XianyuGoodsAutoDeliveryConfigMapper.class);
        XianyuGoodsSku sku = new XianyuGoodsSku();
        sku.setSkuId("sku-year");
        when(goodsSkuService.countByAccountIdAndXyGoodsId(7L, "goods-1")).thenReturn(1);
        when(goodsSkuService.listByAccountIdAndXyGoodsId(7L, "goods-1")).thenReturn(java.util.List.of(sku));

        AutoDeliveryServiceImpl service = deliveryService(goodsSkuService, configMapper);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resolveDeliveryConfig(7L, "goods-1", "sku-year"));
        assertEquals("当前商品规格未配置自动发货", error.getMessage());
        verify(configMapper, never()).findByAccountIdAndGoodsIdNoSku(7L, "goods-1");
    }

    @Test
    void rejectsUnknownSkuFromTheOrder() {
        GoodsSkuService goodsSkuService = mock(GoodsSkuService.class);
        XianyuGoodsSku sku = new XianyuGoodsSku();
        sku.setSkuId("sku-month");
        when(goodsSkuService.countByAccountIdAndXyGoodsId(7L, "goods-1")).thenReturn(1);
        when(goodsSkuService.listByAccountIdAndXyGoodsId(7L, "goods-1")).thenReturn(java.util.List.of(sku));

        AutoDeliveryServiceImpl service = deliveryService(goodsSkuService, mock(XianyuGoodsAutoDeliveryConfigMapper.class));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resolveDeliveryConfig(7L, "goods-1", "sku-invalid"));
        assertEquals("订单商品规格无效，已停止自动发货", error.getMessage());
    }

    @Test
    void usesOnlyTheMatchingMultiSkuConfig() {
        GoodsSkuService goodsSkuService = mock(GoodsSkuService.class);
        XianyuGoodsAutoDeliveryConfigMapper configMapper = mock(XianyuGoodsAutoDeliveryConfigMapper.class);
        XianyuGoodsSku sku = new XianyuGoodsSku();
        sku.setSkuId("sku-year");
        XianyuGoodsAutoDeliveryConfig skuConfig = new XianyuGoodsAutoDeliveryConfig();
        when(goodsSkuService.countByAccountIdAndXyGoodsId(7L, "goods-1")).thenReturn(1);
        when(goodsSkuService.listByAccountIdAndXyGoodsId(7L, "goods-1")).thenReturn(java.util.List.of(sku));
        when(configMapper.findByAccountIdAndGoodsIdAndSkuId(7L, "goods-1", "sku-year")).thenReturn(skuConfig);

        AutoDeliveryServiceImpl service = deliveryService(goodsSkuService, configMapper);

        assertSame(skuConfig, service.resolveDeliveryConfig(7L, "goods-1", "sku-year"));
        verify(configMapper, never()).findByAccountIdAndGoodsIdNoSku(7L, "goods-1");
    }

    @Test
    void persistsPlatformBuyerAndReloadsTheOrderBeforeRecipientVerification() throws Exception {
        XianyuGoodsOrderMapper orderMapper = mock(XianyuGoodsOrderMapper.class);
        XianyuGoodsOrder partial = new XianyuGoodsOrder();
        partial.setId(11L);

        OrderDetailFetcher.OrderDetailInfo detail = new OrderDetailFetcher.OrderDetailInfo();
        detail.xyGoodsId = "goods-1";
        detail.buyerUserId = "buyer-42";
        detail.buyerUserName = "测试买家";
        detail.goodsTitle = "测试商品";
        detail.skuId = "sku-pro";
        detail.buyNum = 1;

        XianyuGoodsOrder refreshed = new XianyuGoodsOrder();
        refreshed.setId(11L);
        refreshed.setXyGoodsId("goods-1");
        refreshed.setBuyerUserId("buyer-42");
        when(orderMapper.selectById(11L)).thenReturn(refreshed);

        AutoDeliveryServiceImpl service = new AutoDeliveryServiceImpl();
        ReflectionTestUtils.setField(service, "orderMapper", orderMapper);
        Method method = AutoDeliveryServiceImpl.class.getDeclaredMethod(
                "persistOrderDetailAndReload", XianyuGoodsOrder.class, String.class,
                OrderDetailFetcher.OrderDetailInfo.class);
        method.setAccessible(true);

        XianyuGoodsOrder result = (XianyuGoodsOrder) method.invoke(
                service, partial, null, detail);

        assertSame(refreshed, result);
        assertEquals("buyer-42", result.getBuyerUserId());
        verify(orderMapper).updateOrderDetail(
                eq(11L), eq("goods-1"), eq("buyer-42"), eq("测试买家"),
                isNull(), isNull(), isNull(), isNull(), eq("sku-pro"), eq("测试商品"), isNull(), eq(1));
    }

    @Test
    void defersDeliveryWhenOrderBuyerCannotBeVerified() {
        XianyuGoodsOrderMapper orderMapper = mock(XianyuGoodsOrderMapper.class);
        XianyuGoodsConfigMapper goodsConfigMapper = mock(XianyuGoodsConfigMapper.class);
        BuyerBlacklistService blacklistService = mock(BuyerBlacklistService.class);
        OrderDetailFetcher orderDetailFetcher = mock(OrderDetailFetcher.class);

        XianyuGoodsOrder order = new XianyuGoodsOrder();
        order.setBuyerUserId("buyer-42");
        XianyuGoodsConfig goodsConfig = new XianyuGoodsConfig();
        goodsConfig.setXianyuAutoDeliveryOn(1);

        when(orderMapper.selectById(11L)).thenReturn(order);
        when(blacklistService.blockedMessage(7L, "buyer-42")).thenReturn(null);
        when(goodsConfigMapper.selectByAccountAndGoodsId(7L, "goods-1")).thenReturn(goodsConfig);
        when(orderDetailFetcher.fetch(7L, "goods-1", "order-1")).thenReturn(null);
        when(orderMapper.updateStateContentAndFailReason(eq(11L), eq(0), isNull(),
                argThat(reason -> reason != null && reason.contains("Cookie")))).thenReturn(1);

        AutoDeliveryServiceImpl service = new AutoDeliveryServiceImpl();
        ReflectionTestUtils.setField(service, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(service, "goodsConfigMapper", goodsConfigMapper);
        ReflectionTestUtils.setField(service, "blacklistService", blacklistService);
        ReflectionTestUtils.setField(service, "orderDetailFetcher", orderDetailFetcher);

        service.executeDelivery(11L, 7L, "goods-1", "buyer-42@goofish", "order-1", "buyer", false);

        verify(orderMapper).updateStateContentAndFailReason(
                eq(11L), eq(0), isNull(),
                argThat(reason -> reason.startsWith(AutoDeliveryServiceImpl.BUYER_VERIFICATION_PENDING_PREFIX)
                        && reason.contains("Cookie")));
    }

    private AutoDeliveryServiceImpl deliveryService(GoodsSkuService goodsSkuService,
                                                     XianyuGoodsAutoDeliveryConfigMapper configMapper) {
        AutoDeliveryServiceImpl service = new AutoDeliveryServiceImpl();
        ReflectionTestUtils.setField(service, "goodsSkuService", goodsSkuService);
        ReflectionTestUtils.setField(service, "autoDeliveryConfigMapper", configMapper);
        return service;
    }
}
