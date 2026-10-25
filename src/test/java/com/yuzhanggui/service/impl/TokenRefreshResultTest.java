package com.yuzhanggui.service.impl;

import com.yuzhanggui.service.Mh5tkRefreshResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Token 刷新结果判定的回归测试。
 *
 * <p>核心约束：刷新后 Token 未变化时，必须返回 UNCHANGED 而不是成功。
 * 曾有实现把"未变化"当成"仍然有效"返回成功，导致调用方拿旧 Token
 * 继续重试，自动发货任务在 BUYER_VERIFICATION_PENDING 状态下每 5 分钟空转。</p>
 */
class TokenRefreshResultTest {

    @Test
    void changedTokenIsRefreshed() {
        assertEquals(Mh5tkRefreshResult.REFRESHED,
                TokenRefreshServiceImpl.resolveRefreshResult("old_tk_value", "new_tk_value"));
    }

    @Test
    void unchangedTokenIsNotReportedAsSuccess() {
        assertEquals(Mh5tkRefreshResult.UNCHANGED,
                TokenRefreshServiceImpl.resolveRefreshResult("same_tk_value", "same_tk_value"));
    }

    @Test
    void firstTimeTokenIsRefreshed() {
        assertEquals(Mh5tkRefreshResult.REFRESHED,
                TokenRefreshServiceImpl.resolveRefreshResult(null, "new_tk_value"));
    }

    @Test
    void emptyOldTokenWithNewValueIsRefreshed() {
        assertEquals(Mh5tkRefreshResult.REFRESHED,
                TokenRefreshServiceImpl.resolveRefreshResult("", "new_tk_value"));
    }
}
