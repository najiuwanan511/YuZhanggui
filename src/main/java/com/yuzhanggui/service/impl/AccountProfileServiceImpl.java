package com.yuzhanggui.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhanggui.entity.XianyuAccount;
import com.yuzhanggui.entity.XianyuCookie;
import com.yuzhanggui.mapper.XianyuAccountMapper;
import com.yuzhanggui.mapper.XianyuCookieMapper;
import com.yuzhanggui.service.AccountProfileService;
import com.yuzhanggui.service.WebSocketTokenService;
import com.yuzhanggui.utils.XianyuApiUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 从闲鱼“我的”页面返回的资料中提取账号头像。
 *
 * <p>该接口的页面结构会随闲鱼更新，因此这里只识别明确的头像字段；
 * 不把任何普通图片当作头像。接口不可用时保持原有数据，由前端显示文字头像。</p>
 */
@Slf4j
@Service
public class AccountProfileServiceImpl implements AccountProfileService {

    private final XianyuAccountMapper accountMapper;
    private final XianyuCookieMapper cookieMapper;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private WebSocketTokenService webSocketTokenService;

    public AccountProfileServiceImpl(XianyuAccountMapper accountMapper,
                                     XianyuCookieMapper cookieMapper,
                                     ObjectMapper objectMapper) {
        this.accountMapper = accountMapper;
        this.cookieMapper = cookieMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public String refreshAvatar(Long accountId) {
        if (accountId == null) {
            return null;
        }

        XianyuAccount account = accountMapper.selectById(accountId);
        if (account == null) {
            return null;
        }
        if (Integer.valueOf(-2).equals(account.getStatus())
                || (webSocketTokenService != null && webSocketTokenService.isCaptchaPending(accountId))) {
            return account.getAvatarUrl();
        }

        String cookieText = getValidCookie(accountId);
        if (cookieText == null || cookieText.isBlank()) {
            log.info("账号头像未刷新：账号没有可用 Cookie，accountId={}", accountId);
            return account.getAvatarUrl();
        }

        try {
            String response = XianyuApiUtils.callApiOnAcsGateway(
                    "mtop.idle.user.page.my.adapter",
                    Collections.emptyMap(),
                    cookieText
            );
            if (!XianyuApiUtils.isSuccess(response)) {
                String error = XianyuApiUtils.extractError(response);
                if (isVerificationRequired(error) && webSocketTokenService != null) {
                    webSocketTokenService.pauseForVerification(accountId, null, "账号资料接口要求安全验证");
                }
                log.info("账号头像未刷新：闲鱼资料接口未返回成功，accountId={}, reason={}",
                        accountId, error);
                return account.getAvatarUrl();
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> responseMap = objectMapper.readValue(response, Map.class);
            String avatarUrl = findAvatarUrl(responseMap.get("data"));
            if (avatarUrl == null) {
                log.info("账号头像未刷新：闲鱼资料中未找到头像字段，accountId={}", accountId);
                return account.getAvatarUrl();
            }

            if (!avatarUrl.equals(account.getAvatarUrl())) {
                account.setAvatarUrl(avatarUrl);
                accountMapper.updateById(account);
                log.info("账号头像已刷新，accountId={}", accountId);
            }
            return avatarUrl;
        } catch (Exception e) {
            // 头像仅用于界面展示，不能影响账号保存、登录与自动化。
            log.info("账号头像获取失败，继续使用文字头像，accountId={}", accountId, e);
            return account.getAvatarUrl();
        }
    }

    private boolean isVerificationRequired(String message) {
        if (message == null) return false;
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("fail_sys_user_validate") || normalized.contains("rgv587_error");
    }

    private String getValidCookie(Long accountId) {
        XianyuCookie cookie = cookieMapper.selectOne(new LambdaQueryWrapper<XianyuCookie>()
                .eq(XianyuCookie::getXianyuAccountId, accountId)
                .eq(XianyuCookie::getCookieStatus, 1)
                .orderByDesc(XianyuCookie::getUpdatedTime)
                .last("LIMIT 1"));
        return cookie == null ? null : cookie.getCookieText();
    }

    private String findAvatarUrl(Object node) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey()).toLowerCase();
                if (isAvatarKey(key)) {
                    String url = extractImageUrl(entry.getValue());
                    if (url != null) {
                        return url;
                    }
                }
            }
            for (Object value : map.values()) {
                String url = findAvatarUrl(value);
                if (url != null) {
                    return url;
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                String url = findAvatarUrl(item);
                if (url != null) {
                    return url;
                }
            }
        }
        return null;
    }

    private boolean isAvatarKey(String key) {
        return key.equals("avatar")
                || key.equals("avatarurl")
                || key.equals("avatar_url")
                || key.equals("headurl")
                || key.equals("head_url")
                || key.equals("headpic")
                || key.equals("head_pic")
                || key.equals("headimage")
                || key.equals("head_image")
                || key.equals("portrait")
                || key.equals("portraiturl")
                || key.equals("usericon")
                || key.equals("usericonurl");
    }

    @SuppressWarnings("unchecked")
    private String extractImageUrl(Object value) {
        if (value instanceof String text) {
            return isSafeImageUrl(text) ? text : null;
        }
        if (value instanceof Map<?, ?> map) {
            for (String field : List.of("url", "imageUrl", "image_url", "src")) {
                Object candidate = ((Map<String, Object>) map).get(field);
                if (candidate instanceof String text && isSafeImageUrl(text)) {
                    return text;
                }
            }
        }
        return null;
    }

    private boolean isSafeImageUrl(String value) {
        if (value == null || value.isBlank() || value.length() > 1000) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
