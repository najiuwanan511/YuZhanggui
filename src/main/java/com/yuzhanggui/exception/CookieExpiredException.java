package com.yuzhanggui.exception;

/**
 * Cookie过期异常
 */
public class CookieExpiredException extends RuntimeException {
    
    public CookieExpiredException(String message) {
        super(message);
    }
}
