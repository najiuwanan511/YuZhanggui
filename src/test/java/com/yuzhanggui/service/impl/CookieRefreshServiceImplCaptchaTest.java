package com.yuzhanggui.service.impl;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class CookieRefreshServiceImplCaptchaTest {

    @Test
    void completedCaptchaRemovesChallengeMarkers() {
        Map<String, String> cookies = new LinkedHashMap<>();
        cookies.put("x5secdata", "challenge");
        cookies.put("x5sectag", "tag");
        cookies.put("x5step", "2");
        cookies.put("x5sec", "passed");
        cookies.put("_m_h5_tk", "token_expiry");

        CookieRefreshServiceImpl.removeCaptchaChallengeCookies(cookies);

        assertEquals("passed", cookies.get("x5sec"));
        assertEquals("token_expiry", cookies.get("_m_h5_tk"));
        assertFalse(cookies.containsKey("x5secdata"));
        assertFalse(cookies.containsKey("x5sectag"));
        assertFalse(cookies.containsKey("x5step"));
    }

    @Test
    void cookieLookupIsCaseInsensitive() {
        Map<String, String> cookies = Map.of("X5SEC", "value");

        assertEquals("value", CookieRefreshServiceImpl.findCookieIgnoreCase(cookies, "x5sec"));
        assertNull(CookieRefreshServiceImpl.findCookieIgnoreCase(cookies, "x5secdata"));
    }
}
