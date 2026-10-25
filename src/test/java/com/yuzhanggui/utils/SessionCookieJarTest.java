package com.yuzhanggui.utils;

import okhttp3.Cookie;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionCookieJarTest {
    @Test
    void honorsServerDeletionAndDoesNotForwardLoginCookiesThroughRedirects() {
        var jar = new SessionCookieJar("cookie2=login; _m_h5_tk=expired");
        var url = HttpUrl.get("https://h5api.m.goofish.com/");
        jar.saveFromResponse(url, java.util.List.of(Cookie.parse(url, "_m_h5_tk=; Max-Age=0; Path=/")));
        assertFalse(jar.getCookieString().contains("_m_h5_tk"));
        assertTrue(jar.getCookieString().contains("cookie2=login"));
        assertFalse(jar.createHttpClient().followRedirects());
        assertFalse(jar.createHttpClient().followSslRedirects());
    }
}
