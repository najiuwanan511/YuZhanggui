package com.yuzhanggui.service.impl;

import com.yuzhanggui.common.ResultObject;
import com.yuzhanggui.controller.dto.KamiConfigReqDTO;
import com.yuzhanggui.entity.XianyuKamiConfig;
import com.yuzhanggui.entity.XianyuKamiItem;
import com.yuzhanggui.entity.XianyuKamiUsageRecord;
import com.yuzhanggui.mapper.XianyuKamiConfigMapper;
import com.yuzhanggui.mapper.XianyuKamiItemMapper;
import com.yuzhanggui.mapper.XianyuKamiUsageRecordMapper;
import com.yuzhanggui.mapper.XianyuGoodsAutoDeliveryConfigMapper;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ExtendWith(MockitoExtension.class)
class KamiConfigServiceImplTest {

    @Mock
    private XianyuKamiConfigMapper kamiConfigMapper;
    @Mock
    private XianyuKamiItemMapper kamiItemMapper;
    @Mock
    private XianyuKamiUsageRecordMapper kamiUsageRecordMapper;
    @Mock
    private XianyuGoodsAutoDeliveryConfigMapper autoDeliveryConfigMapper;
    @InjectMocks
    private KamiConfigServiceImpl service;

    @Test
    void deletesDeliveredItemAndRefreshesInventoryCounts() {
        XianyuKamiItem item = item(11L, 7L, 1);
        XianyuKamiConfig config = new XianyuKamiConfig();
        config.setId(7L);
        when(kamiItemMapper.selectById(11L)).thenReturn(item);
        when(kamiItemMapper.deleteIfNotPending(11L)).thenReturn(1);
        when(kamiItemMapper.countByConfigId(7L)).thenReturn(3);
        when(kamiItemMapper.countUsed(7L)).thenReturn(1);
        when(kamiConfigMapper.selectById(7L)).thenReturn(config);

        ResultObject<Void> result = service.deleteKamiItem(11L);

        assertEquals(200, result.getCode());
        assertEquals(3, config.getTotalCount());
        assertEquals(1, config.getUsedCount());
        verify(kamiConfigMapper).updateById(config);
    }

    @Test
    void refusesToDeleteItemWhoseDeliveryStateChangedConcurrently() {
        when(kamiItemMapper.selectById(11L)).thenReturn(item(11L, 7L, 0));
        when(kamiItemMapper.deleteIfNotPending(11L)).thenReturn(0);

        ResultObject<Void> result = service.deleteKamiItem(11L);

        assertEquals(500, result.getCode());
        assertTrue(result.getMsg().contains("发货处理中"));
        verify(kamiConfigMapper, never()).updateById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void resettingDeliveredItemRefreshesInventoryCounts() {
        XianyuKamiItem item = item(11L, 7L, 1);
        XianyuKamiConfig config = new XianyuKamiConfig();
        config.setId(7L);
        when(kamiItemMapper.selectById(11L)).thenReturn(item);
        when(kamiItemMapper.markUnused(11L)).thenReturn(1);
        when(kamiItemMapper.countByConfigId(7L)).thenReturn(4);
        when(kamiItemMapper.countUsed(7L)).thenReturn(0);
        when(kamiConfigMapper.selectById(7L)).thenReturn(config);

        ResultObject<Void> result = service.resetKamiItem(11L);

        assertEquals(200, result.getCode());
        assertEquals(0, config.getUsedCount());
        verify(kamiConfigMapper).updateById(config);
    }

    @Test
    void freshRedeliveryCommitsReservationToOriginalBusinessOrder() {
        XianyuKamiItem item = item(11L, 7L, 2);
        item.setKamiContent("NEW-CARD");
        when(kamiItemMapper.findByOrderAndStatus("order-1#R#attempt", 2)).thenReturn(java.util.List.of(item));
        when(kamiItemMapper.commitReservation("order-1#R#attempt", "order-1")).thenReturn(1);

        service.commitReservation("order-1#R#attempt", "order-1", 3L, "goods-1", "buyer-1", "买家");

        ArgumentCaptor<XianyuKamiUsageRecord> captor = ArgumentCaptor.forClass(XianyuKamiUsageRecord.class);
        verify(kamiUsageRecordMapper).insert(captor.capture());
        assertEquals("order-1", captor.getValue().getOrderId());
        assertEquals("NEW-CARD", captor.getValue().getKamiContent());
        verify(kamiItemMapper).commitReservation("order-1#R#attempt", "order-1");
    }

    @Test
    void resolvesImageUploadedByTheCurrentSellerAccount() {
        XianyuKamiConfig config = new XianyuKamiConfig();
        config.setDeliveryImageUrl("https://legacy.example/image.jpg");
        config.setDeliveryImageUrlsJson("{\"2\":\"https://cdn.example/account-2.jpg\",\"3\":\"https://cdn.example/account-3.jpg\"}");

        assertEquals("https://cdn.example/account-2.jpg", service.resolveDeliveryImageUrl(config, 2L));
        assertEquals("https://cdn.example/account-3.jpg", service.resolveDeliveryImageUrl(config, 3L));
    }

    @Test
    void fallsBackToLegacyImageForExistingConfigurations() {
        XianyuKamiConfig config = new XianyuKamiConfig();
        config.setDeliveryImageUrl(" https://legacy.example/image.jpg ");
        config.setDeliveryImageUrlsJson("{\"2\":\"https://cdn.example/account-2.jpg\"}");

        assertEquals("https://legacy.example/image.jpg", service.resolveDeliveryImageUrl(config, 9L));
    }

    @Test
    void savesAndClearsAccountScopedImagesExplicitly() {
        XianyuKamiConfig config = new XianyuKamiConfig();
        config.setId(7L);
        config.setAliasName("共享卡券");
        config.setSourceType(1);
        config.setTotalCount(0);
        config.setUsedCount(0);
        when(kamiConfigMapper.selectById(7L)).thenReturn(config);
        when(kamiItemMapper.countUnused(7L)).thenReturn(0);
        when(autoDeliveryConfigMapper.findDefaultByKamiConfigId(7L)).thenReturn(List.of());

        KamiConfigReqDTO request = new KamiConfigReqDTO();
        request.setId(7L);
        Map<Long, String> images = new LinkedHashMap<>();
        images.put(2L, " https://cdn.example/account-2.jpg ");
        images.put(3L, "https://cdn.example/account-3.jpg");
        request.setDeliveryImageUrls(images);

        ResultObject<?> saved = service.createOrUpdateConfig(request);
        assertEquals(200, saved.getCode());
        assertEquals("{\"2\":\"https://cdn.example/account-2.jpg\",\"3\":\"https://cdn.example/account-3.jpg\"}",
                config.getDeliveryImageUrlsJson());

        request.setDeliveryImageUrls(Map.of());
        ResultObject<?> cleared = service.createOrUpdateConfig(request);
        assertEquals(200, cleared.getCode());
        assertEquals("{}", config.getDeliveryImageUrlsJson());
    }

    private XianyuKamiItem item(Long id, Long configId, int status) {
        XianyuKamiItem item = new XianyuKamiItem();
        item.setId(id);
        item.setKamiConfigId(configId);
        item.setStatus(status);
        return item;
    }
}
