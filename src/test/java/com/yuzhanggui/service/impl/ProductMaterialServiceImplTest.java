package com.yuzhanggui.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhanggui.config.rag.DynamicAIChatClientManager;
import com.yuzhanggui.controller.dto.ProductCopywritingReqDTO;
import com.yuzhanggui.controller.dto.ProductMaterialDTO;
import com.yuzhanggui.controller.dto.ProductMaterialImportReqDTO;
import com.yuzhanggui.controller.dto.ProductMaterialSaveReqDTO;
import com.yuzhanggui.controller.dto.ProductPublishReqDTO;
import com.yuzhanggui.entity.XianyuProductMaterial;
import com.yuzhanggui.entity.XianyuGoodsInfo;
import com.yuzhanggui.entity.XianyuGoodsSku;
import com.yuzhanggui.entity.XianyuGoodsSkuProperty;
import com.yuzhanggui.exception.BusinessException;
import com.yuzhanggui.mapper.XianyuProductMaterialMapper;
import com.yuzhanggui.service.GoodsInfoService;
import com.yuzhanggui.service.GoodsSkuPropertyService;
import com.yuzhanggui.service.GoodsSkuService;
import com.yuzhanggui.service.ImageDimensionService;
import com.yuzhanggui.service.ItemDetailSyncService;
import com.yuzhanggui.controller.dto.SyncSingleItemRespDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductMaterialServiceImplTest {

    @Test
    void shouldSaveAndRestoreReusableImages() {
        XianyuProductMaterialMapper mapper = mock(XianyuProductMaterialMapper.class);
        AtomicReference<XianyuProductMaterial> stored = new AtomicReference<>();
        when(mapper.insert(any())).thenAnswer(invocation -> {
            XianyuProductMaterial entity = invocation.getArgument(0);
            entity.setId(12L);
            stored.set(entity);
            return 1;
        });
        when(mapper.selectById(12L)).thenAnswer(ignored -> stored.get());
        ProductMaterialServiceImpl service = service(mapper);

        ProductMaterialSaveReqDTO request = new ProductMaterialSaveReqDTO();
        request.setMaterialName("耳机素材");
        request.setTitle("全新蓝牙耳机");
        request.setDescription("全新未拆封");
        request.setPrice(new BigDecimal("19.90"));
        request.setQuantity(2);
        request.setDeliveryMode("FREE");
        ProductPublishReqDTO.Image image = new ProductPublishReqDTO.Image();
        image.setUrl("https://img.alicdn.com/demo.jpg");
        image.setWidth(800);
        image.setHeight(800);
        request.setImages(List.of(image));

        ProductMaterialDTO saved = service.save(request);

        assertEquals(12L, saved.getId());
        assertEquals("耳机素材", saved.getMaterialName());
        assertEquals(1, saved.getImages().size());
        assertEquals("https://img.alicdn.com/demo.jpg", saved.getImages().get(0).getUrl());
    }

    @Test
    void shouldImportListedMultiSkuProductAsReusableMaterial() {
        XianyuProductMaterialMapper mapper = mock(XianyuProductMaterialMapper.class);
        GoodsInfoService goodsInfoService = mock(GoodsInfoService.class);
        GoodsSkuService goodsSkuService = mock(GoodsSkuService.class);
        GoodsSkuPropertyService propertyService = mock(GoodsSkuPropertyService.class);
        ItemDetailSyncService syncService = mock(ItemDetailSyncService.class);
        ImageDimensionService dimensionService = mock(ImageDimensionService.class);
        AtomicReference<XianyuProductMaterial> stored = new AtomicReference<>();

        XianyuGoodsInfo goods = new XianyuGoodsInfo();
        goods.setXianyuAccountId(7L);
        goods.setXyGoodId("9988");
        goods.setTitle("测试多规格商品");
        goods.setDetailInfo("完整商品详情");
        goods.setSoldPrice("¥12.80");
        goods.setSkuCount(2);
        goods.setInfoPic("[{\"picUrl\":\"https://img.alicdn.com/a.jpg\",\"width\":800,\"height\":800}]");
        when(goodsInfoService.getByXyGoodId("9988")).thenReturn(goods);

        XianyuGoodsSku red = sku("红色", 1280, 3);
        XianyuGoodsSku blue = sku("蓝色", 1380, 4);
        when(goodsSkuService.listByAccountIdAndXyGoodsId(7L, "9988")).thenReturn(List.of(red, blue));
        XianyuGoodsSkuProperty property = new XianyuGoodsSkuProperty();
        property.setPropertyText("颜色");
        when(propertyService.listByAccountIdAndXyGoodsId(7L, "9988")).thenReturn(List.of(property));
        SyncSingleItemRespDTO syncResult = new SyncSingleItemRespDTO();
        syncResult.setSuccess(true);
        when(syncService.syncSingleItem(7L, "9988")).thenReturn(syncResult);
        when(mapper.insert(any())).thenAnswer(invocation -> {
            XianyuProductMaterial entity = invocation.getArgument(0);
            entity.setId(21L);
            stored.set(entity);
            return 1;
        });
        when(mapper.selectById(21L)).thenAnswer(ignored -> stored.get());

        ProductMaterialServiceImpl service = new ProductMaterialServiceImpl(mapper, new ObjectMapper(), goodsInfoService,
                goodsSkuService, propertyService, syncService, dimensionService);
        ProductMaterialImportReqDTO request = new ProductMaterialImportReqDTO();
        request.setXianyuAccountId(7L);
        request.setXyGoodsId("9988");

        ProductMaterialDTO material = service.importFromGoods(request);

        assertEquals(21L, material.getId());
        assertEquals(7L, material.getSourceAccountId());
        assertEquals("9988", material.getSourceGoodsId());
        assertEquals("颜色", material.getSkuPropertyName());
        assertEquals(2, material.getSkuSpecs().size());
        assertEquals(new BigDecimal("12.80"), material.getSkuSpecs().getFirst().getPrice());
        assertEquals(7, material.getQuantity());
        assertEquals(1, material.getImages().size());
    }

    @Test
    void shouldRejectCopywritingWhenAiIsNotConfigured() {
        DynamicAIChatClientManager manager = mock(DynamicAIChatClientManager.class);
        when(manager.getChatClient()).thenReturn(null);
        ProductCopywritingServiceImpl service = new ProductCopywritingServiceImpl(manager);
        ProductCopywritingReqDTO request = new ProductCopywritingReqDTO();
        request.setTitle("测试商品");
        request.setMode("GENERATE");

        BusinessException error = assertThrows(BusinessException.class, () -> service.generate(request));

        assertEquals(409, error.getCode());
    }

    private ProductMaterialServiceImpl service(XianyuProductMaterialMapper mapper) {
        return new ProductMaterialServiceImpl(mapper, new ObjectMapper(), mock(GoodsInfoService.class),
                mock(GoodsSkuService.class), mock(GoodsSkuPropertyService.class), mock(ItemDetailSyncService.class),
                mock(ImageDimensionService.class));
    }

    private XianyuGoodsSku sku(String name, int price, int quantity) {
        XianyuGoodsSku sku = new XianyuGoodsSku();
        sku.setValueText(name);
        sku.setPrice(price);
        sku.setQuantity(quantity);
        return sku;
    }
}
