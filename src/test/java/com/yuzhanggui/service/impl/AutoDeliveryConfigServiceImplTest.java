package com.yuzhanggui.service.impl;

import com.yuzhanggui.common.ResultObject;
import com.yuzhanggui.controller.dto.AutoDeliveryConfigReqDTO;
import com.yuzhanggui.controller.dto.AutoDeliveryConfigQueryReqDTO;
import com.yuzhanggui.controller.dto.AutoDeliveryConfigRespDTO;
import com.yuzhanggui.entity.XianyuGoodsAutoDeliveryConfig;
import com.yuzhanggui.entity.XianyuGoodsSku;
import com.yuzhanggui.entity.XianyuKamiConfig;
import com.yuzhanggui.mapper.XianyuGoodsAutoDeliveryConfigMapper;
import com.yuzhanggui.mapper.XianyuKamiConfigMapper;
import com.yuzhanggui.service.GoodsSkuService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoDeliveryConfigServiceImplTest {

    @Test
    void savesAnExactSkuToKamiMapping() {
        XianyuGoodsAutoDeliveryConfigMapper configMapper = mock(XianyuGoodsAutoDeliveryConfigMapper.class);
        XianyuKamiConfigMapper kamiMapper = mock(XianyuKamiConfigMapper.class);
        GoodsSkuService skuService = mock(GoodsSkuService.class);
        AutoDeliveryConfigServiceImpl service = service(configMapper, kamiMapper, skuService);

        XianyuGoodsSku sku = new XianyuGoodsSku();
        sku.setSkuId("sku-quarter");
        sku.setValueText("90天");
        when(skuService.listByAccountIdAndXyGoodsId(7L, "goods-1")).thenReturn(List.of(sku));
        XianyuKamiConfig kami = new XianyuKamiConfig();
        kami.setId(12L);
        kami.setXianyuAccountId(7L);
        when(kamiMapper.selectById(12L)).thenReturn(kami);

        AutoDeliveryConfigReqDTO request = new AutoDeliveryConfigReqDTO();
        request.setXianyuAccountId(7L);
        request.setXianyuGoodsId(8L);
        request.setXyGoodsId("goods-1");
        request.setSkuId("sku-quarter");
        request.setDeliveryMode(2);
        request.setKamiConfigIds("12");
        request.setKamiDeliveryTemplate("{kmKey}");

        var result = service.saveOrUpdateConfig(request);

        ArgumentCaptor<XianyuGoodsAutoDeliveryConfig> saved = ArgumentCaptor.forClass(XianyuGoodsAutoDeliveryConfig.class);
        assertEquals(200, result.getCode());
        verify(configMapper).insert(saved.capture());
        assertEquals("sku-quarter", saved.getValue().getSkuId());
        assertEquals("90天", saved.getValue().getSkuName());
        assertEquals("12", saved.getValue().getKamiConfigIds());
    }

    @Test
    void rejectsAKamiLibraryOwnedByAnotherAccount() {
        XianyuGoodsAutoDeliveryConfigMapper configMapper = mock(XianyuGoodsAutoDeliveryConfigMapper.class);
        XianyuKamiConfigMapper kamiMapper = mock(XianyuKamiConfigMapper.class);
        GoodsSkuService skuService = mock(GoodsSkuService.class);
        AutoDeliveryConfigServiceImpl service = service(configMapper, kamiMapper, skuService);

        XianyuGoodsSku sku = new XianyuGoodsSku();
        sku.setSkuId("sku-month");
        when(skuService.listByAccountIdAndXyGoodsId(7L, "goods-1")).thenReturn(List.of(sku));
        XianyuKamiConfig kami = new XianyuKamiConfig();
        kami.setId(13L);
        kami.setXianyuAccountId(99L);
        when(kamiMapper.selectById(13L)).thenReturn(kami);

        AutoDeliveryConfigReqDTO request = new AutoDeliveryConfigReqDTO();
        request.setXianyuAccountId(7L);
        request.setXyGoodsId("goods-1");
        request.setSkuId("sku-month");
        request.setDeliveryMode(2);
        request.setKamiConfigIds("13");

        var result = service.saveOrUpdateConfig(request);

        assertEquals(500, result.getCode());
        assertEquals("选择的卡密库不属于当前账号", result.getMsg());
    }

    @Test
    void doesNotUseTheDefaultConfigWhenLoadingASpecificSku() {
        XianyuGoodsAutoDeliveryConfigMapper configMapper = mock(XianyuGoodsAutoDeliveryConfigMapper.class);
        AutoDeliveryConfigServiceImpl service = service(configMapper,
                mock(XianyuKamiConfigMapper.class), mock(GoodsSkuService.class));

        AutoDeliveryConfigQueryReqDTO request = new AutoDeliveryConfigQueryReqDTO();
        request.setXianyuAccountId(7L);
        request.setXyGoodsId("goods-1");
        request.setSkuId("sku-year");

        ResultObject<AutoDeliveryConfigRespDTO> result = service.getConfig(request);

        assertNull(result.getData());
        verify(configMapper, never()).findByAccountIdAndGoodsIdNoSku(7L, "goods-1");
    }

    private AutoDeliveryConfigServiceImpl service(XianyuGoodsAutoDeliveryConfigMapper configMapper,
                                                   XianyuKamiConfigMapper kamiMapper,
                                                   GoodsSkuService skuService) {
        AutoDeliveryConfigServiceImpl service = new AutoDeliveryConfigServiceImpl();
        ReflectionTestUtils.setField(service, "autoDeliveryConfigMapper", configMapper);
        ReflectionTestUtils.setField(service, "kamiConfigMapper", kamiMapper);
        ReflectionTestUtils.setField(service, "goodsSkuService", skuService);
        return service;
    }
}
