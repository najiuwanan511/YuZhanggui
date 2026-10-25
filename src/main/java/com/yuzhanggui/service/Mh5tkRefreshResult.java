package com.yuzhanggui.service;

/**
 * _m_h5_tk 刷新结果（诚实三态）。
 *
 * <p>背景：刷新接口被调用时，往往是因为某个真实业务接口已经报告了凭证失效。
 * 此时"Token 没变化"不代表"Token 仍然有效"，只代表本次刷新没有拿到新 Token；
 * 调用方必须把它当作"未恢复"处理，绝不能拿旧 Token 继续重试。</p>
 */
public enum Mh5tkRefreshResult {
    /**
     * 拿到了与之前不同的新 Token，刷新真正生效。
     */
    REFRESHED,

    /**
     * 刷新接口调用成功，但 Token 没有变化：没有拿到新 Token，不能视为恢复成功。
     */
    UNCHANGED,

    /**
     * 刷新失败（网络异常、登录态失效、重试次数耗尽等）。
     */
    FAILED
}
