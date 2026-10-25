package com.yuzhanggui.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhanggui.controller.dto.PublishCapabilityCheckRespDTO;
import com.yuzhanggui.controller.dto.ProductPublishReqDTO;
import com.yuzhanggui.entity.XianyuAccount;
import com.yuzhanggui.mapper.XianyuAccountMapper;
import com.yuzhanggui.utils.XianyuApiCallUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class PublishCapabilityProbeServiceTest {

    @Mock
    private XianyuAccountMapper accountMapper;
    @Mock
    private AccountService accountService;
    @Mock
    private XianyuApiCallUtils apiCallUtils;

    private PublishCapabilityProbeService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new PublishCapabilityProbeService(accountMapper, accountService, apiCallUtils, new ObjectMapper());
    }

    @Test
    void shouldReadCategoryPropertiesAndLocationWithoutPublishing() {
        XianyuAccount account = new XianyuAccount();
        account.setId(7L);
        when(accountMapper.selectById(7L)).thenReturn(account);
        when(accountService.getCookieByAccountId(7L)).thenReturn("_m_h5_tk=token_exp; unb=buyer");

        String categoryResponse = """
                {"ret":["SUCCESS::调用成功"],"data":{
                  "categoryPredictResult":{"catId":"50012029","catName":"手机","channelCatId":"126854956","tbCatId":"1512"},
                  "cardList":[
                    {"cardData":{"propertyId":"20000","propertyName":"品牌","valuesList":[{"catName":"Apple/苹果"},{"catName":"华为"}]}},
                    {"cardData":{"propertyId":"30000","propertyName":"型号","valuesList":[{"catName":"iPhone 15 Pro"}]}}
                  ]
                }}
                """;
        String locationResponse = """
                {"ret":["SUCCESS::调用成功"],"data":{"commonAddresses":[{"divisionId":"310101","city":"上海"}]}}
                """;
        when(apiCallUtils.callApiWithRetry(eq(7L), eq(PublishCapabilityProbeService.CATEGORY_API), any(Map.class),
                any(String.class), eq("2.0"), eq(null), any(Map.class)))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(true, categoryResponse, null, false));
        when(apiCallUtils.callApiWithRetry(eq(7L), eq(PublishCapabilityProbeService.LOCATION_API), any(Map.class),
                any(String.class), eq("1.0"), eq(null), eq(null)))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(true, locationResponse, null, false));

        PublishCapabilityCheckRespDTO result = service.check(7L, "iPhone 15 Pro 256G");

        assertTrue(result.isPassed());
        assertEquals("PASS", result.getStatus());
        assertEquals("手机", result.getCategoryName());
        assertEquals(2, result.getPropertyCount());
        assertEquals("品牌", result.getProperties().get(0).getPropertyName());
        assertEquals(2, result.getProperties().get(0).getOptionCount());
        assertEquals("Apple/苹果", result.getProperties().get(0).getOptions().get(0).getValueName());
        assertEquals("GENERAL_FORM", result.getSupportLevel());
        assertEquals("可使用通用动态表单", result.getSupportLabel());
        assertFalse(result.isRealPublishTested());
        verify(apiCallUtils, never()).callApiWithRetry(eq(7L), eq("mtop.idle.pc.idleitem.publish"),
                any(Map.class), any(String.class));
    }

    @Test
    void shouldAllowVirtualMembershipWhenPlatformReturnsCompleteDynamicForm() {
        XianyuAccount account = new XianyuAccount();
        account.setId(12L);
        when(accountMapper.selectById(12L)).thenReturn(account);
        when(accountService.getCookieByAccountId(12L)).thenReturn("_m_h5_tk=token_exp; unb=seller");

        String categoryResponse = """
                {"ret":["SUCCESS::调用成功"],"data":{
                  "categoryPredictResult":{"catId":"virtual-1","catName":"视频会员充值","channelCatId":"100","tbCatId":"200"},
                  "cardList":[
                    {"cardData":{"propertyId":"1","propertyName":"适用平台","required":true,"valuesList":[{"valueId":"doubao","catName":"豆包"}]}},
                    {"cardData":{"propertyId":"2","propertyName":"使用周期","required":true,"valuesList":[{"valueId":"30d","catName":"30天"}]}},
                    {"cardData":{"propertyId":"3","propertyName":"是否支持电视","required":true,"valuesList":[{"valueId":"no","catName":"否"}]}},
                    {"cardData":{"propertyId":"4","propertyName":"交易形式","required":true,"valuesList":[{"valueId":"direct","catName":"直充"}]}}
                  ]
                }}
                """;
        String locationResponse = """
                {"ret":["SUCCESS::调用成功"],"data":{"commonAddresses":[{"divisionId":"310101","city":"上海"}]}}
                """;
        when(apiCallUtils.callApiWithRetry(eq(12L), eq(PublishCapabilityProbeService.CATEGORY_API), any(Map.class),
                any(String.class), eq("2.0"), eq(null), any(Map.class)))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(true, categoryResponse, null, false));
        when(apiCallUtils.callApiWithRetry(eq(12L), eq(PublishCapabilityProbeService.LOCATION_API), any(Map.class),
                any(String.class), eq("1.0"), eq(null), eq(null)))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(true, locationResponse, null, false));

        PublishCapabilityCheckRespDTO result = service.check(12L,
                "【直冲自己账号秒到】豆包标准版 标准会员 VIP 30天月");

        assertTrue(result.isPassed());
        assertEquals("GENERAL_FORM", result.getSupportLevel());
        assertFalse(result.isSpecialCategory());
        assertEquals(4, result.getRequiredPropertyCount());
        assertEquals(0, result.getDependentPropertyCount());
        assertTrue(result.getPublishWarnings().stream().anyMatch(warning -> warning.contains("虚拟商品")));
    }

    @Test
    void shouldIdentifyEmptyOptionsAsDependentAndSpecialCategory() {
        XianyuAccount account = new XianyuAccount();
        account.setId(8L);
        when(accountMapper.selectById(8L)).thenReturn(account);
        when(accountService.getCookieByAccountId(8L)).thenReturn("_m_h5_tk=token_exp; unb=buyer");

        String categoryResponse = """
                {"ret":["SUCCESS::调用成功"],"data":{
                  "categoryPredictResult":{"catId":"50025461","catName":"咖啡/奶茶/冷饮"},
                  "cardList":[
                    {"cardData":{"propertyId":"1","propertyName":"餐饮品牌","required":true,"valuesList":[{"catName":"瑞幸咖啡","channelCatId":"11","isClicked":true}]}},
                    {"cardData":{"propertyId":"2","propertyName":"适用门店","required":true,"valuesList":[]}}
                  ]
                }}
                """;
        String locationResponse = """
                {"ret":["SUCCESS::调用成功"],"data":{"commonAddresses":[{"divisionId":"310101"}]}}
                """;
        when(apiCallUtils.callApiWithRetry(eq(8L), eq(PublishCapabilityProbeService.CATEGORY_API), any(Map.class),
                any(String.class), eq("2.0"), eq(null), any(Map.class)))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(true, categoryResponse, null, false));
        when(apiCallUtils.callApiWithRetry(eq(8L), eq(PublishCapabilityProbeService.LOCATION_API), any(Map.class),
                any(String.class), eq("1.0"), eq(null), eq(null)))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(true, locationResponse, null, false));

        PublishCapabilityCheckRespDTO result = service.check(8L, "瑞幸咖啡代下单电子券");

        assertEquals("SPECIAL_ADAPTER", result.getSupportLevel());
        assertTrue(result.isSpecialCategory());
        assertEquals(2, result.getRequiredPropertyCount());
        assertEquals(1, result.getDependentPropertyCount());
        assertTrue(result.getProperties().get(1).isDependent());
        assertTrue(result.getProperties().get(0).getOptions().get(0).isSelected());
    }

    @Test
    void shouldIdentifyAssistServiceFormWhenServiceFieldsAreReturned() {
        XianyuAccount account = new XianyuAccount();
        account.setId(9L);
        when(accountMapper.selectById(9L)).thenReturn(account);
        when(accountService.getCookieByAccountId(9L)).thenReturn("_m_h5_tk=token_exp; unb=buyer");

        String categoryResponse = """
                {"ret":["SUCCESS::调用成功"],"data":{
                  "categoryPredictResult":{"catId":"service-1","catName":"拼单/助力"},
                  "cardList":[
                    {"cardData":{"propertyId":"1","propertyName":"交付周期","required":true,"valuesList":[{"catName":"10分钟"}]}},
                    {"cardData":{"propertyId":"2","propertyName":"服务类型","required":true,"valuesList":[{"catName":"助力"}]}},
                    {"cardData":{"propertyId":"3","propertyName":"计价方式","required":true,"valuesList":[{"catName":"元/次"}]}}
                  ]
                }}
                """;
        String locationResponse = """
                {"ret":["SUCCESS::调用成功"],"data":{"commonAddresses":[{"divisionId":"310101"}]}}
                """;
        when(apiCallUtils.callApiWithRetry(eq(9L), eq(PublishCapabilityProbeService.CATEGORY_API), any(Map.class),
                any(String.class), eq("2.0"), eq(null), any(Map.class)))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(true, categoryResponse, null, false));
        when(apiCallUtils.callApiWithRetry(eq(9L), eq(PublishCapabilityProbeService.LOCATION_API), any(Map.class),
                any(String.class), eq("1.0"), eq(null), eq(null)))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(true, locationResponse, null, false));

        PublishCapabilityCheckRespDTO result = service.check(9L, "书亦烧仙草免单助力");

        assertEquals("SERVICE_FORM", result.getSupportLevel());
        assertEquals("拼单/助力服务表单", result.getSupportLabel());
        assertEquals(3, result.getRequiredPropertyCount());
    }

    @Test
    void shouldSendSelectedCategoryToLoadDependentServiceFields() {
        XianyuAccount account = new XianyuAccount();
        account.setId(10L);
        when(accountMapper.selectById(10L)).thenReturn(account);
        when(accountService.getCookieByAccountId(10L)).thenReturn("_m_h5_tk=token_exp; unb=buyer");
        String categoryResponse = """
                {"ret":["SUCCESS"],"data":{
                  "categoryPredictResult":[{"catId":"service-1","catName":"拼单/助力"}],
                  "cardList":[
                    {"cardData":{"propertyId":"category","propertyName":"分类","required":true,"valuesList":[{"valueId":"assist","catName":"拼单/助力","channelCatId":"100"}]}},
                    {"cardData":{"propertyId":"period","propertyName":"交付周期","required":true,"valuesList":[{"valueId":"10m","catName":"10分钟"}]}},
                    {"cardData":{"propertyId":"service","propertyName":"服务类型","required":true,"valuesList":[{"valueId":"assist","catName":"助力"}]}},
                    {"cardData":{"propertyId":"pricing","propertyName":"计价方式","required":true,"valuesList":[{"valueId":"unit","catName":"元/次"}]}}
                  ]
                }}
                """;
        String locationResponse = """
                {"ret":["SUCCESS"],"data":{"commonAddresses":[{"divisionId":"310101"}]}}
                """;
        when(apiCallUtils.callApiWithRetry(eq(10L), eq(PublishCapabilityProbeService.CATEGORY_API), any(Map.class),
                any(String.class), eq("2.0"), eq(null), any(Map.class)))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(true, categoryResponse, null, false));
        when(apiCallUtils.callApiWithRetry(eq(10L), eq(PublishCapabilityProbeService.LOCATION_API), any(Map.class),
                any(String.class), eq("1.0"), eq(null), eq(null)))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(true, locationResponse, null, false));
        ProductPublishReqDTO.PropertySelection selection = new ProductPublishReqDTO.PropertySelection();
        selection.setPropertyId("category");
        selection.setValueKey("assist");
        selection.setPropertyName("分类");
        selection.setValueName("拼单/助力");
        selection.setChannelCategoryId("100");

        PublishCapabilityCheckRespDTO result = service.check(10L, "奶茶助力快单", List.of(selection));

        assertEquals("SERVICE_FORM", result.getSupportLevel());
        assertEquals(0, result.getDependentPropertyCount());
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, String>> query = ArgumentCaptor.forClass(Map.class);
        verify(apiCallUtils).callApiWithRetry(eq(10L), eq(PublishCapabilityProbeService.CATEGORY_API), request.capture(),
                any(String.class), eq("2.0"), eq(null), query.capture());
        List<?> labels = (List<?>) request.getValue().get("itemLabelExtList");
        assertEquals(1, labels.size());
        assertEquals("分类", ((Map<?, ?>) labels.get(0)).get("propertyName"));
        assertEquals("拼单/助力", ((Map<?, ?>) labels.get(0)).get("text"));
        assertEquals("a21ybx.publish.0.0", query.getValue().get("spm_cnt"));
    }

    @Test
    void shouldRetryWithDescriptionAndFindNestedCategoryAliases() {
        XianyuAccount account = new XianyuAccount();
        account.setId(11L);
        when(accountMapper.selectById(11L)).thenReturn(account);
        when(accountService.getCookieByAccountId(11L)).thenReturn("_m_h5_tk=token_exp; unb=buyer");
        String emptyCategoryResponse = """
                {"ret":["SUCCESS"],"data":{"categoryPredictResult":[],"cardList":[]}}
                """;
        String nestedCategoryResponse = """
                {"ret":["SUCCESS"],"data":{
                  "result":{"recommendation":{"categoryId":"service-1","categoryName":"拼单/助力","channelCid":"100","tbCid":"200"}},
                  "cardList":[
                    {"cardData":{"propertyId":"1","propertyName":"交付周期","required":true,"valuesList":[{"catName":"10分钟"}]}},
                    {"cardData":{"propertyId":"2","propertyName":"服务类型","required":true,"valuesList":[{"catName":"助力"}]}},
                    {"cardData":{"propertyId":"3","propertyName":"计价方式","required":true,"valuesList":[{"catName":"元/次"}]}}
                  ]
                }}
                """;
        String locationResponse = """
                {"ret":["SUCCESS"],"data":{"commonAddresses":[{"divisionId":"310101"}]}}
                """;
        when(apiCallUtils.callApiWithRetry(eq(11L), eq(PublishCapabilityProbeService.CATEGORY_API), any(Map.class),
                any(String.class), eq("2.0"), eq(null), any(Map.class)))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(true, emptyCategoryResponse, null, false))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(true, nestedCategoryResponse, null, false));
        when(apiCallUtils.callApiWithRetry(eq(11L), eq(PublishCapabilityProbeService.LOCATION_API), any(Map.class),
                any(String.class), eq("1.0"), eq(null), eq(null)))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(true, locationResponse, null, false));
        ProductPublishReqDTO.Image image = new ProductPublishReqDTO.Image();
        image.setUrl("https://img.alicdn.com/test.jpg");
        image.setWidth(800);
        image.setHeight(600);

        PublishCapabilityCheckRespDTO result = service.check(11L, "爷爷不泡茶助力",
                "爷爷不泡茶奶茶拼单助力服务", List.of(image), List.of());

        assertEquals("service-1", result.getCategoryId());
        assertEquals("拼单/助力", result.getCategoryName());
        assertEquals("100", result.getChannelCategoryId());
        assertEquals("200", result.getTaobaoCategoryId());
        assertEquals("SERVICE_FORM", result.getSupportLevel());
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(apiCallUtils, times(2)).callApiWithRetry(eq(11L), eq(PublishCapabilityProbeService.CATEGORY_API),
                request.capture(), any(String.class), eq("2.0"), eq(null), any(Map.class));
        assertFalse(request.getAllValues().get(0).containsKey("itemLabelExtList"));
        assertEquals("爷爷不泡茶奶茶拼单助力服务", request.getAllValues().get(1).get("title"));
        assertEquals(1, ((List<?>) request.getAllValues().get(1).get("imageInfos")).size());
    }

    @Test
    void shouldStopBeforeNetworkWhenAccountDoesNotExist() {
        when(accountMapper.selectById(99L)).thenReturn(null);

        PublishCapabilityCheckRespDTO result = service.check(99L, "测试商品");

        assertFalse(result.isPassed());
        assertEquals("FAIL", result.getStatus());
        assertEquals("账号不存在", result.getSummary());
        verify(apiCallUtils, never()).callApiWithRetry(any(), any(), any(), any());
    }
}
