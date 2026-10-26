package com.yuzhanggui.service.impl;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VerificationBrowserFactoryTest {
    @Test void restrictsNavigationAndResourceOrigins() {
        assertTrue(VerificationBrowserFactory.trustedUrl("https://h5api.m.goofish.com:443/h5/challenge", false));
        assertTrue(VerificationBrowserFactory.trustedUrl("https://g.alicdn.com/script.js", true));
        for (String url : List.of("http://goofish.com/", "https://goofish.com:444/", "https://goofish.com.evil.test/",
                "https://evil.test/?host=goofish.com", "https://user@goofish.com/", "file:///secret", "https://127.0.0.1/")) {
            assertFalse(VerificationBrowserFactory.trustedUrl(url, true), url);
        }
        assertFalse(VerificationBrowserFactory.trustedUrl("https://g.alicdn.com/", false));
    }

    @Test void validatesRealInputBoundsAndMonotonicTime() {
        var first = new VerificationBrowserFactory.Point(20, 30, 0);
        assertDoesNotThrow(() -> VerificationBrowserFactory.validatePoints(List.of(first, new VerificationBrowserFactory.Point(500, 30, 350))));
        for (var invalid : List.of(new VerificationBrowserFactory.Point(Double.NaN, 0, 10),
                new VerificationBrowserFactory.Point(1081, 0, 10), new VerificationBrowserFactory.Point(0, -1, 10),
                new VerificationBrowserFactory.Point(0, 0, -1), new VerificationBrowserFactory.Point(0, 0, 8001))) {
            assertThrows(IllegalArgumentException.class, () -> VerificationBrowserFactory.validatePoints(List.of(first, invalid)));
        }
        assertThrows(IllegalArgumentException.class, () -> VerificationBrowserFactory.validatePoints(List.of(first)));
        assertThrows(IllegalArgumentException.class, () -> VerificationBrowserFactory.validatePoints(List.of(
                first, new VerificationBrowserFactory.Point(30, 30, 200), new VerificationBrowserFactory.Point(40, 30, 100))));
    }

    @Test void manualScopeIsNestedAndClearedAfterFailures() {
        var scope = new ManualVerificationScope();
        assertThrows(IllegalStateException.class, () -> scope.run(1L, () -> {
            assertTrue(scope.owns(1L)); assertFalse(scope.owns(2L));
            scope.run(2L, () -> { assertTrue(scope.owns(2L)); return null; });
            assertTrue(scope.owns(1L));
            throw new IllegalStateException("test");
        }));
        assertFalse(scope.owns(1L)); assertFalse(scope.owns(2L));
    }
}
