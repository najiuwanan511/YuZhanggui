package com.yuzhanggui.service.impl;

import org.springframework.stereotype.Component;
import java.util.function.Supplier;

/** The inline human-operated browser owns this attempt; do not launch a second browser. */
@Component
public class ManualVerificationScope {
    private final ThreadLocal<Long> account = new ThreadLocal<>();

    public boolean owns(Long accountId) { return accountId != null && accountId.equals(account.get()); }

    public <T> T run(Long accountId, Supplier<T> action) {
        Long previous = account.get();
        account.set(accountId);
        try { return action.get(); }
        finally {
            if (previous == null) account.remove(); else account.set(previous);
        }
    }
}
