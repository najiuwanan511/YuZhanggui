package com.yuzhanggui.service.delivery;

import com.yuzhanggui.service.AccountService;
import com.yuzhanggui.service.Mh5tkRefreshResult;
import com.yuzhanggui.service.TokenRefreshService;
import com.yuzhanggui.utils.XianyuApiCallUtils;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderDetailFetcherTest {

    @Test
    void parsesGoodsIdentifierFromMerchantItemDetails() throws Exception {
        OrderDetailFetcher fetcher = new OrderDetailFetcher();
        OrderDetailFetcher.OrderDetailInfo detail = new OrderDetailFetcher.OrderDetailInfo();
        Map<String, Object> module = Map.of(
                "merchantItemVO", Map.of(
                        "itemId", 123456789L,
                        "title", "测试商品"
                )
        );

        Method parser = OrderDetailFetcher.class.getDeclaredMethod(
                "parseItemInfo", Map.class, OrderDetailFetcher.OrderDetailInfo.class);
        parser.setAccessible(true);
        parser.invoke(fetcher, module, detail);

        assertEquals("123456789", detail.xyGoodsId);
        assertEquals("测试商品", detail.goodsTitle);
    }

    @Test
    void refreshesH5TokenAndRetriesWhenOrderDetailTokenExpires() throws Exception {
        AccountService accountService = mock(AccountService.class);
        TokenRefreshService tokenRefreshService = mock(TokenRefreshService.class);
        XianyuApiCallUtils apiCallUtils = mock(XianyuApiCallUtils.class);
        OrderDetailFetcher fetcher = new OrderDetailFetcher();
        ReflectionTestUtils.setField(fetcher, "accountService", accountService);
        ReflectionTestUtils.setField(fetcher, "tokenRefreshService", tokenRefreshService);
        ReflectionTestUtils.setField(fetcher, "xianyuApiCallUtils", apiCallUtils);

        when(apiCallUtils.callApiWithRetry(eq(7L), eq("mtop.taobao.idle.trade.merchant.full.info"),
                anyMap(), eq("stale-cookie")))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(false, null, "令牌过期", true));
        when(tokenRefreshService.refreshMh5tkTokenDetailed(7L)).thenReturn(Mh5tkRefreshResult.REFRESHED);
        when(accountService.getCookieByAccountId(7L)).thenReturn("fresh-cookie");
        when(apiCallUtils.callApiWithRetry(eq(7L), eq("mtop.taobao.idle.trade.merchant.full.info"),
                anyMap(), eq("fresh-cookie")))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(true, "{}", null, false));

        Method method = OrderDetailFetcher.class.getDeclaredMethod("fetchOrderDetailApi",
                Long.class, Map.class, String.class, String.class);
        method.setAccessible(true);
        XianyuApiCallUtils.ApiCallResult result = (XianyuApiCallUtils.ApiCallResult) method.invoke(
                fetcher, 7L, new HashMap<>(), "stale-cookie", "order-1");

        assertTrue(result.isSuccess());
        verify(tokenRefreshService).refreshMh5tkTokenDetailed(7L);
    }

    @Test
    void doesNotRetryWithStaleTokenWhenRefreshYieldsNoNewToken() throws Exception {
        OrderDetailFetcher fetcher = newFetcherWithMocks(
                new XianyuApiCallUtils.ApiCallResult(false, null, "令牌过期", true),
                Mh5tkRefreshResult.UNCHANGED,
                "stale-cookie");

        XianyuApiCallUtils.ApiCallResult result = invokeFetchOrderDetailApi(fetcher, "stale-cookie");

        assertFalse(result.isSuccess());
        assertTrue(result.isTokenExpired());
        // 熔断：没有拿到新 Token 时绝不能用旧 Token 发起第二次调用
        verify(fetcherApiCallUtils(fetcher), times(1)).callApiWithRetry(
                eq(7L), eq("mtop.taobao.idle.trade.merchant.full.info"), anyMap(), anyString());
    }

    @Test
    void doesNotRetryWhenRefreshFails() throws Exception {
        OrderDetailFetcher fetcher = newFetcherWithMocks(
                new XianyuApiCallUtils.ApiCallResult(false, null, "令牌过期", true),
                Mh5tkRefreshResult.FAILED,
                "stale-cookie");

        XianyuApiCallUtils.ApiCallResult result = invokeFetchOrderDetailApi(fetcher, "stale-cookie");

        assertFalse(result.isSuccess());
        assertTrue(result.isTokenExpired());
        verify(fetcherApiCallUtils(fetcher), times(1)).callApiWithRetry(
                eq(7L), eq("mtop.taobao.idle.trade.merchant.full.info"), anyMap(), anyString());
    }

    @Test
    void doesNotRetryWhenCookieDidNotChangeAfterRefresh() throws Exception {
        // 纵深防御：即使刷新报告 REFRESHED，Cookie 没有实际变化也不重试
        OrderDetailFetcher fetcher = newFetcherWithMocks(
                new XianyuApiCallUtils.ApiCallResult(false, null, "令牌过期", true),
                Mh5tkRefreshResult.REFRESHED,
                "stale-cookie");

        XianyuApiCallUtils.ApiCallResult result = invokeFetchOrderDetailApi(fetcher, "stale-cookie");

        assertFalse(result.isSuccess());
        verify(fetcherApiCallUtils(fetcher), times(1)).callApiWithRetry(
                eq(7L), eq("mtop.taobao.idle.trade.merchant.full.info"), anyMap(), anyString());
    }

    private OrderDetailFetcher newFetcherWithMocks(XianyuApiCallUtils.ApiCallResult firstResult,
                                                   Mh5tkRefreshResult refreshResult,
                                                   String refreshedCookie) {
        AccountService accountService = mock(AccountService.class);
        TokenRefreshService tokenRefreshService = mock(TokenRefreshService.class);
        XianyuApiCallUtils apiCallUtils = mock(XianyuApiCallUtils.class);
        OrderDetailFetcher fetcher = new OrderDetailFetcher();
        ReflectionTestUtils.setField(fetcher, "accountService", accountService);
        ReflectionTestUtils.setField(fetcher, "tokenRefreshService", tokenRefreshService);
        ReflectionTestUtils.setField(fetcher, "xianyuApiCallUtils", apiCallUtils);

        when(apiCallUtils.callApiWithRetry(eq(7L), eq("mtop.taobao.idle.trade.merchant.full.info"),
                anyMap(), eq("stale-cookie"))).thenReturn(firstResult);
        when(tokenRefreshService.refreshMh5tkTokenDetailed(7L)).thenReturn(refreshResult);
        when(accountService.getCookieByAccountId(7L)).thenReturn(refreshedCookie);
        return fetcher;
    }

    private XianyuApiCallUtils.ApiCallResult invokeFetchOrderDetailApi(OrderDetailFetcher fetcher,
                                                                      String cookieStr) throws Exception {
        Method method = OrderDetailFetcher.class.getDeclaredMethod("fetchOrderDetailApi",
                Long.class, Map.class, String.class, String.class);
        method.setAccessible(true);
        return (XianyuApiCallUtils.ApiCallResult) method.invoke(
                fetcher, 7L, new HashMap<>(), cookieStr, "order-1");
    }

    private XianyuApiCallUtils fetcherApiCallUtils(OrderDetailFetcher fetcher) {
        return (XianyuApiCallUtils) ReflectionTestUtils.getField(fetcher, "xianyuApiCallUtils");
    }
}
