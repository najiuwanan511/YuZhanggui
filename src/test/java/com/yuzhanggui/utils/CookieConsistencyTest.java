package com.yuzhanggui.utils;

import com.yuzhanggui.service.AccountService;
import com.yuzhanggui.service.CookieRefreshService;
import com.yuzhanggui.service.impl.CredentialUpdateCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CookieConsistencyTest {
    @Test
    void parsesCookiesWithoutSpacesAndPreservesEqualsInsideValues() {
        var result = XianyuSignUtils.parseCookies("unb=123;_m_h5_tk=token_999; cookie2=a=b==");
        assertEquals("token_999", result.get("_m_h5_tk"));
        assertEquals("a=b==", result.get("cookie2"));
    }

    @Test
    void responseFromOldSessionCannotOverwriteNewLogin() {
        var service = new XianyuApiCallUtils();
        var accounts = mock(AccountService.class);
        var cookies = mock(CookieRefreshService.class);
        ReflectionTestUtils.setField(service, "accountService", accounts);
        ReflectionTestUtils.setField(service, "cookieRefreshService", cookies);
        ReflectionTestUtils.setField(service, "credentialUpdateCoordinator", new CredentialUpdateCoordinator());
        String latest = "unb=123; _m_h5_tk=fresh_999; cookie2=new-session";
        when(accounts.getCookieByAccountId(7L)).thenReturn(latest);
        String result = ReflectionTestUtils.invokeMethod(service, "updateCookiesFromResponse", 7L,
                "unb=123; _m_h5_tk=old_1; cookie2=old-session", List.of("_m_h5_tk=stale_2; Path=/"));
        assertEquals(latest, result);
        verify(accounts, never()).updateCookie(anyLong(), anyString());
        verifyNoInteractions(cookies);
    }
}
