package com.yuzhanggui.controller;

import com.yuzhanggui.common.ResultObject;
import com.yuzhanggui.entity.XianyuAccount;
import com.yuzhanggui.controller.dto.UpdateCookieReqDTO;
import com.yuzhanggui.controller.dto.UpdateCookieRespDTO;
import com.yuzhanggui.service.CookieRefreshService;
import com.yuzhanggui.service.Mh5tkRefreshResult;
import com.yuzhanggui.service.WebSocketService;
import com.yuzhanggui.service.ImageDimensionService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

/**
 * WebSocket控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/websocket")
public class WebSocketController {

    @Autowired
    private WebSocketService webSocketService;

    /**
     * 在线客服手动发送等待平台回执的超时（秒）。
     *
     * <p>自动流程沿用 10 秒超时以保证幂等判断的准确性；手动发送是人在等界面响应，
     * 超时后平台仍会送达，因此用更短的超时换取即时反馈。</p>
     */
    private static final int MANUAL_SEND_ACK_TIMEOUT_SECONDS = 2;

    @Autowired
    private ImageDimensionService imageDimensionService;
    
    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;
    
    @Autowired
    private com.yuzhanggui.service.TokenRefreshService tokenRefreshService;
    
    @Autowired
    private CookieRefreshService cookieRefreshService;

    @Autowired
    private com.yuzhanggui.service.WebSocketTokenService webSocketTokenService;

    @Value("${app.captcha.remote-enabled:false}")
    private boolean captchaRemoteEnabled;

    @Value("${app.captcha.remote-url:}")
    private String captchaRemoteUrl;

    @Value("${app.captcha.remote-port:7900}")
    private int captchaRemotePort;

    @Autowired
    private com.yuzhanggui.service.SentMessageSaveService sentMessageSaveService;

    @Autowired
    private com.yuzhanggui.service.AutoReplyDelayService autoReplyDelayService;
    
    @Autowired
    private com.yuzhanggui.service.OperationLogService operationLogService;

    /**
     * 启动WebSocket连接
     */
    @PostMapping("/start")
    public ResultObject<CaptchaInfoDTO> startWebSocket(@RequestBody StartWebSocketReqDTO reqDTO) {
        try {
            log.info("启动WebSocket请求: xianyuAccountId={}, 手动Token={}", 
                    reqDTO.getXianyuAccountId(), 
                    reqDTO.getAccessToken() != null ? "已提供" : "未提供");
            
            if (reqDTO.getXianyuAccountId() == null) {
                return ResultObject.failed("账号ID不能为空");
            }
            
            boolean success;
            if (reqDTO.getAccessToken() != null && !reqDTO.getAccessToken().isEmpty()) {
                // 使用手动提供的 accessToken
                success = webSocketService.startWebSocketWithToken(
                        reqDTO.getXianyuAccountId(), 
                        reqDTO.getAccessToken()
                );
            } else {
                // 自动获取 accessToken
                success = webSocketService.startWebSocket(reqDTO.getXianyuAccountId());
            }
            
            if (success) {
                return ResultObject.success(null, "WebSocket连接已启动");
            } else {
                // 检查具体失败原因
                String errorMessage = getDetailedErrorMessage(reqDTO.getXianyuAccountId());
                return ResultObject.failed(errorMessage);
            }
            
        } catch (com.yuzhanggui.exception.CaptchaRequiredException e) {
            CaptchaInfoDTO captchaInfo = new CaptchaInfoDTO();
            captchaInfo.setNeedCaptcha(true);
            captchaInfo.setCaptchaUrl(e.getCaptchaUrl());
            captchaInfo.setRemoteBrowserEnabled(captchaRemoteEnabled);
            captchaInfo.setRemoteBrowserUrl(captchaRemoteUrl);
            captchaInfo.setRemoteBrowserPort(captchaRemotePort);
            captchaInfo.setMessage(captchaRemoteEnabled
                    ? "飞牛远程验证画面已启动，请打开 noVNC 页面完成滑块；完成后保持页面打开，系统会自动回收新的 x5sec。"
                    : "闲鱼要求网页安全验证，系统已暂停该账号的自动刷新与重连。请完成验证后更新 Cookie。");
            return new ResultObject<>(1001, "需要安全验证", captchaInfo);
            /*
            log.warn("⚠️ 需要滑块验证: accountId={}, url={}", reqDTO.getXianyuAccountId(), e.getCaptchaUrl());
            CaptchaInfoDTO captchaInfo = new CaptchaInfoDTO();
            captchaInfo.setNeedCaptcha(true);
            captchaInfo.setCaptchaUrl(e.getCaptchaUrl());
            captchaInfo.setMessage("检测到账号需要完成滑块验证。请完成验证后更新Cookie，点击启动连接会自动更新Token，滑块校验生效会延迟，稍等片刻会自动连接。");
            
            log.info("📋 滑块验证信息:");
            log.info("   - 账号ID: {}", reqDTO.getXianyuAccountId());
            log.info("   - 验证URL: {}", e.getCaptchaUrl());
            log.info("   - 提示: 请访问 https://www.goofish.com/im 完成验证后手动更新Cookie和Token");
            
            ResultObject<CaptchaInfoDTO> result = new ResultObject<>(1001, "需要滑块验证", captchaInfo);
            return result;
        */ } catch (com.yuzhanggui.exception.CookieNotFoundException e) {
            log.error("Cookie未找到: accountId={}", reqDTO.getXianyuAccountId());
            return ResultObject.failed("WebSocket连接启动失败：" + e.getMessage());
        } catch (com.yuzhanggui.exception.CookieExpiredException e) {
            return ResultObject.failed("账号凭证已失效或触发安全验证，请在闲鱼客户端确认账号状态后，在账号管理中使用“凭证更新”重新扫码，再重新连接。");
            /*
            log.error("Cookie已过期: accountId={}", reqDTO.getXianyuAccountId());
            return ResultObject.failed("WebSocket连接启动失败：" + e.getMessage());
            */
        } catch (com.yuzhanggui.exception.TokenInvalidException e) {
            log.error("Token无效: accountId={}", reqDTO.getXianyuAccountId());
            return ResultObject.failed("WebSocket连接启动失败：" + e.getMessage());
        } catch (Exception e) {
            log.error("启动WebSocket失败", e);
            return ResultObject.failed("启动WebSocket失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取详细的错误信息
     */
    private String getDetailedErrorMessage(Long xianyuAccountId) {
        try {
            // 查询Cookie信息
            com.yuzhanggui.mapper.XianyuCookieMapper cookieMapper = 
                    applicationContext.getBean(com.yuzhanggui.mapper.XianyuCookieMapper.class);
            
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.yuzhanggui.entity.XianyuCookie> cookieQuery = 
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
            cookieQuery.eq(com.yuzhanggui.entity.XianyuCookie::getXianyuAccountId, xianyuAccountId)
                    .orderByDesc(com.yuzhanggui.entity.XianyuCookie::getCreatedTime)
                    .last("LIMIT 1");
            com.yuzhanggui.entity.XianyuCookie cookie = cookieMapper.selectOne(cookieQuery);
            
            if (cookie == null) {
                return "WebSocket连接启动失败：未找到账号Cookie，请先配置Cookie";
            }
            
            // 检查Cookie状态
            if (cookie.getCookieStatus() != null && cookie.getCookieStatus() == 2) {
                return "WebSocket连接启动失败：Cookie已过期，请更新Cookie后重试";
            }
            
            if (cookie.getCookieStatus() != null && cookie.getCookieStatus() == 3) {
                return "WebSocket连接启动失败：Cookie已失效，请重新获取Cookie";
            }
            
            // 检查Cookie文本是否为空
            if (cookie.getCookieText() == null || cookie.getCookieText().trim().isEmpty()) {
                return "WebSocket连接启动失败：Cookie内容为空，请重新配置Cookie";
            }
            
            // 检查WebSocket Token
            if (cookie.getWebsocketToken() != null && !cookie.getWebsocketToken().isEmpty()) {
                // 检查Token是否过期
                if (cookie.getTokenExpireTime() != null) {
                    long now = System.currentTimeMillis();
                    if (cookie.getTokenExpireTime() <= now) {
                        return "WebSocket连接启动失败：WebSocket Token已过期，系统将自动刷新Token，请稍后重试";
                    }
                }
                // Token存在且未过期，但连接失败
                return "WebSocket连接启动失败：WebSocket Token无效或连接被拒绝，请尝试更新Cookie或稍后重试";
            }
            
            // Token不存在，可能是获取Token失败
            return "WebSocket连接启动失败：无法获取WebSocket Token，请检查Cookie是否有效或稍后重试";
            
        } catch (Exception e) {
            log.error("获取详细错误信息失败", e);
            return "WebSocket连接启动失败：系统错误，请查看日志获取详细信息";
        }
    }

    /**
     * 停止WebSocket连接
     */
    @PostMapping("/stop")
    public ResultObject<String> stopWebSocket(@RequestBody StopWebSocketReqDTO reqDTO) {
        try {
            log.info("停止WebSocket请求: xianyuAccountId={}", reqDTO.getXianyuAccountId());
            
            if (reqDTO.getXianyuAccountId() == null) {
                return ResultObject.failed("账号ID不能为空");
            }
            
            boolean success = webSocketService.stopWebSocket(reqDTO.getXianyuAccountId());
            
            if (success) {
                return ResultObject.success("WebSocket连接已停止");
            } else {
                return ResultObject.failed("WebSocket连接停止失败");
            }
            
        } catch (Exception e) {
            log.error("停止WebSocket失败", e);
            return ResultObject.failed("停止WebSocket失败: " + e.getMessage());
        }
    }

    /**
     * 发送消息
     */
    @PostMapping("/sendMessage")
    public ResultObject<String> sendMessage(@RequestBody SendMessageReqDTO reqDTO) {
        try {
            log.info("发送消息请求: xianyuAccountId={}, cid={}, toId={}, textLength={}",
                    reqDTO.getXianyuAccountId(), reqDTO.getCid(), reqDTO.getToId(), safeLength(reqDTO.getText()));
            
            // 参数校验
            if (reqDTO.getXianyuAccountId() == null) {
                return ResultObject.failed("账号ID不能为空");
            }
            if (reqDTO.getCid() == null || reqDTO.getCid().isEmpty()) {
                return ResultObject.failed("会话ID(cid)不能为空");
            }
            if (reqDTO.getToId() == null || reqDTO.getToId().isEmpty()) {
                return ResultObject.failed("接收方ID(toId)不能为空");
            }
            if (reqDTO.getText() == null || reqDTO.getText().isEmpty()) {
                return ResultObject.failed("消息内容不能为空");
            }
            
            // 检查WebSocket连接状态
            if (!webSocketService.isConnected(reqDTO.getXianyuAccountId())) {
                return ResultObject.failed("WebSocket未连接，请先启动连接");
            }
            
            // 在线客服一旦主动发送，立即接管真实会话并取消尚未发出的自动回复。
            String sId = reqDTO.getCid() + "@goofish";
            autoReplyDelayService.recordCustomerServiceReply(
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), sId);

            // 发送消息。
            // 在线客服是人机交互场景：消息帧写入已连接的 WebSocket 后买家即可收到，
            // 这里只做短超时确认，避免 HTTP 线程因平台回执慢而长时间阻塞（表现为界面卡顿/延迟）。
            boolean success = webSocketService.sendMessageWithResult(
                    reqDTO.getXianyuAccountId(),
                    reqDTO.getCid(),
                    reqDTO.getToId(),
                    reqDTO.getText(),
                    MANUAL_SEND_ACK_TIMEOUT_SECONDS
            );
            
            if (success) {
                sentMessageSaveService.saveManualReply(
                        reqDTO.getXianyuAccountId(),
                        reqDTO.getCid(),
                        reqDTO.getToId(),
                        reqDTO.getText(),
                        reqDTO.getXyGoodsId()
                );
                return ResultObject.success("消息发送成功");
            } else {
                return ResultObject.failed("消息发送失败");
            }
            
        } catch (Exception e) {
            log.error("发送消息失败", e);
            return ResultObject.failed("发送消息失败: " + e.getMessage());
        }
    }
    
    /**
     * 发送图片消息
     */
    @PostMapping("/sendImageMessage")
    public ResultObject<String> sendImageMessage(@RequestBody SendImageMessageReqDTO reqDTO) {
        try {
            log.info("发送图片消息请求: xianyuAccountId={}, cid={}, toId={}",
                    reqDTO.getXianyuAccountId(), reqDTO.getCid(), reqDTO.getToId());
            
            // 参数校验
            if (reqDTO.getXianyuAccountId() == null) {
                return ResultObject.failed("账号ID不能为空");
            }
            if (reqDTO.getCid() == null || reqDTO.getCid().isEmpty()) {
                return ResultObject.failed("会话ID(cid)不能为空");
            }
            if (reqDTO.getToId() == null || reqDTO.getToId().isEmpty()) {
                return ResultObject.failed("接收方ID(toId)不能为空");
            }
            if (reqDTO.getImageUrl() == null || reqDTO.getImageUrl().isEmpty()) {
                return ResultObject.failed("图片URL不能为空");
            }
            
            // 检查WebSocket连接状态
            if (!webSocketService.isConnected(reqDTO.getXianyuAccountId())) {
                return ResultObject.failed("WebSocket未连接，请先启动连接");
            }
            
            // 前端尺寸可能是旧版本固定值，服务端必须以图片本身的真实尺寸为准。
            ImageDimensionService.ImageDimensions dimensions = imageDimensionService.resolve(reqDTO.getImageUrl());
            int width = dimensions.width();
            int height = dimensions.height();
            
            // 在线客服一旦主动发送，立即接管真实会话并取消尚未发出的自动回复。
            String sId = reqDTO.getCid() + "@goofish";
            autoReplyDelayService.recordCustomerServiceReply(
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), sId);

            // 发送图片消息（同手动文本消息，使用短超时避免 HTTP 线程长时间阻塞）
            boolean success = webSocketService.sendImageMessageWithResult(
                    reqDTO.getXianyuAccountId(),
                    reqDTO.getCid(),
                    reqDTO.getToId(),
                    reqDTO.getImageUrl(),
                    width,
                    height,
                    MANUAL_SEND_ACK_TIMEOUT_SECONDS
            );
            
            if (success) {
                sentMessageSaveService.saveManualImageReply(
                        reqDTO.getXianyuAccountId(),
                        reqDTO.getCid(),
                        reqDTO.getToId(),
                        reqDTO.getImageUrl(),
                        reqDTO.getXyGoodsId()
                );
                return ResultObject.success("图片消息发送成功");
            } else {
                return ResultObject.failed("图片消息发送失败");
            }
            
        } catch (Exception e) {
            log.error("发送图片消息失败", e);
            return ResultObject.failed("发送图片消息失败: " + e.getMessage());
        }
    }

    /**
     * 检查WebSocket连接状态
     */
    @PostMapping("/status")
    public ResultObject<WebSocketStatusRespDTO> getWebSocketStatus(@RequestBody GetWebSocketStatusReqDTO reqDTO) {
        try {
            // 优化：将日志级别从INFO降为DEBUG，减少日志刷屏
            log.debug("查询WebSocket状态: xianyuAccountId={}", reqDTO.getXianyuAccountId());

            if (reqDTO.getXianyuAccountId() == null) {
                return ResultObject.failed("账号ID不能为空");
            }

            boolean connected = webSocketService.isConnected(reqDTO.getXianyuAccountId());

            WebSocketStatusRespDTO respDTO = new WebSocketStatusRespDTO();
            respDTO.setXianyuAccountId(reqDTO.getXianyuAccountId());
            respDTO.setConnected(connected);
            respDTO.setStatus(connected ? "已连接" : "未连接");


            // 查询Cookie信息
            com.yuzhanggui.mapper.XianyuCookieMapper cookieMapper =
                    applicationContext.getBean(com.yuzhanggui.mapper.XianyuCookieMapper.class);

            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.yuzhanggui.entity.XianyuCookie> cookieQuery =
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
            cookieQuery.eq(com.yuzhanggui.entity.XianyuCookie::getXianyuAccountId, reqDTO.getXianyuAccountId())
                    .orderByDesc(com.yuzhanggui.entity.XianyuCookie::getCreatedTime)
                    .last("LIMIT 1");
            com.yuzhanggui.entity.XianyuCookie cookie = cookieMapper.selectOne(cookieQuery);

            if (cookie != null) {
                respDTO.setCookieStatus(cookie.getCookieStatus());
                respDTO.setCookieConfigured(cookie.getCookieText() != null
                        && !cookie.getCookieText().isBlank());
                respDTO.setMh5TkConfigured(cookie.getMH5Tk() != null
                        && !cookie.getMH5Tk().isBlank());
                respDTO.setWebsocketTokenConfigured(cookie.getWebsocketToken() != null
                        && !cookie.getWebsocketToken().isBlank());
                Long rawTokenExpireTime = cookie.getTokenExpireTime();
                boolean tokenExpiryKnown = rawTokenExpireTime != null && rawTokenExpireTime >= 1577836800000L;
                respDTO.setTokenExpiryKnown(tokenExpiryKnown);
                respDTO.setTokenExpireTime(tokenExpiryKnown ? rawTokenExpireTime : null);
                respDTO.setTokenLastRefreshTime(tokenExpiryKnown
                        ? Math.max(0L, rawTokenExpireTime - 20L * 60L * 60L * 1000L) : null);
                respDTO.setCookieText(cookie.getCookieText());
                respDTO.setMh5Tk(cookie.getMH5Tk());
                respDTO.setWebsocketToken(cookie.getWebsocketToken());

                // 构建简洁的状态信息
                StringBuilder statusInfo = new StringBuilder();
                statusInfo.append("账号ID=").append(reqDTO.getXianyuAccountId());
                statusInfo.append(", 连接=").append(connected ? "✅" : "❌");
                statusInfo.append(", Cookie=").append(getCookieStatusText(cookie.getCookieStatus()));
                statusInfo.append(", Token=").append(cookie.getWebsocketToken() != null ? "✅" : "❌");

                if (cookie.getTokenExpireTime() != null) {
                    long now = System.currentTimeMillis();
                    long remaining = cookie.getTokenExpireTime() - now;
                    if (remaining > 0) {
                        statusInfo.append(", 剩余").append(remaining / 1000).append("秒");
                    } else {
                        statusInfo.append(", Token已过期");
                    }
                }

                // 优化：将日志级别从INFO降为DEBUG，减少日志刷屏
                log.debug("✅ WebSocket状态: {}", statusInfo);
            } else {
                respDTO.setCookieStatus(null);
                respDTO.setCookieConfigured(false);
                respDTO.setMh5TkConfigured(false);
                respDTO.setWebsocketTokenConfigured(false);
                respDTO.setTokenExpireTime(null);
                respDTO.setTokenExpiryKnown(false);
                respDTO.setTokenLastRefreshTime(null);

                log.warn("⚠️ WebSocket状态: 账号ID={}, 连接={}, Cookie=未找到",
                        reqDTO.getXianyuAccountId(), connected ? "✅" : "❌");
            }

            com.yuzhanggui.service.WebSocketTokenService.RenewalStatus renewalStatus =
                    webSocketTokenService.getRenewalStatus(reqDTO.getXianyuAccountId());
            respDTO.setTokenRenewalState(renewalStatus.state());
            respDTO.setTokenRenewalMessage(renewalStatus.message());
            respDTO.setTokenRenewalUpdatedAt(renewalStatus.updatedAt());
            respDTO.setTokenRenewalNextRetryAt(renewalStatus.nextRetryAt());
            respDTO.setCaptchaRequired(webSocketTokenService.isCaptchaPending(reqDTO.getXianyuAccountId()));
            respDTO.setCaptchaUrl(webSocketTokenService.getCaptchaUrl(reqDTO.getXianyuAccountId()));
            respDTO.setCaptchaRemoteEnabled(captchaRemoteEnabled);
            respDTO.setCaptchaRemoteUrl(captchaRemoteUrl);
            respDTO.setCaptchaRemotePort(captchaRemotePort);

            com.yuzhanggui.mapper.XianyuGoodsConfigMapper goodsConfigMapper =
                    applicationContext.getBean(com.yuzhanggui.mapper.XianyuGoodsConfigMapper.class);
            try {
                java.util.List<com.yuzhanggui.entity.XianyuGoodsConfig> configs =
                        goodsConfigMapper.selectByAccountId(reqDTO.getXianyuAccountId());
                boolean hasAutoDelivery = configs != null && configs.stream()
                        .anyMatch(c -> c.getXianyuAutoDeliveryOn() != null && c.getXianyuAutoDeliveryOn() == 1);
                boolean hasAutoReply = configs != null && configs.stream()
                        .anyMatch(c -> c.getXianyuAutoReplyOn() != null && c.getXianyuAutoReplyOn() == 1);
                respDTO.setAutoDeliveryOn(hasAutoDelivery);
                respDTO.setAutoReplyOn(hasAutoReply);
            } catch (Exception e) {
                respDTO.setAutoDeliveryOn(null);
                respDTO.setAutoReplyOn(null);
            }

            return ResultObject.success(respDTO);

        } catch (Exception e) {
            log.error("查询WebSocket状态失败", e);
            return ResultObject.failed("查询WebSocket状态失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取Cookie状态文本描述
     */
    private String getCookieStatusText(Integer cookieStatus) {
        if (cookieStatus == null) {
            return "未知";
        }
        switch (cookieStatus) {
            case 1:
                return "✅ 有效";
            case 2:
                return "⚠️ 已过期";
            case 3:
                return "❌ 已失效";
            default:
                return "未知状态(" + cookieStatus + ")";
        }
    }
    private int safeLength(String value) {
        return value == null ? 0 : value.length();
    }

    
    /**
     * 清除验证等待状态
     */
    @PostMapping("/clearCaptchaWait")
    public ResultObject<String> clearCaptchaWait(@RequestBody ClearCaptchaWaitReqDTO reqDTO) {
        try {
            log.info("清除验证等待状态: xianyuAccountId={}", reqDTO.getXianyuAccountId());
            
            if (reqDTO.getXianyuAccountId() == null) {
                return ResultObject.failed("账号ID不能为空");
            }
            
            // Clearing this state without a new Cookie causes the next request
            // to replay the same risk-controlled session. Credential update
            // endpoints clear it only after persisting a new Cookie.
            return ResultObject.failed("请先完成安全验证并扫码更新 Cookie，不能单独清除验证状态");
            
        } catch (Exception e) {
            log.error("清除验证等待状态失败", e);
            return ResultObject.failed("清除验证等待状态失败: " + e.getMessage());
        }
    }

    /**
     * 用户完成平台安全验证后，检测结果并自动刷新Token、恢复连接。
     */
    @PostMapping("/captcha/verification/complete")
    public ResultObject<CaptchaVerificationRespDTO> completeCaptchaVerification(
            @RequestBody ClearCaptchaWaitReqDTO reqDTO) {
        if (reqDTO.getXianyuAccountId() == null) {
            return ResultObject.failed("账号ID不能为空");
        }
        Long accountId = reqDTO.getXianyuAccountId();
        boolean connected = false;

        CaptchaVerificationRespDTO result = new CaptchaVerificationRespDTO();
        result.setConnected(connected);
        result.setNeedCaptcha(webSocketTokenService.isCaptchaPending(accountId));
        result.setCaptchaUrl(webSocketTokenService.getCaptchaUrl(accountId));
        result.setCaptchaRemoteEnabled(captchaRemoteEnabled);
        result.setCaptchaRemoteUrl(captchaRemoteUrl);
        result.setCaptchaRemotePort(captchaRemotePort);
        result.setMessage(captchaRemoteEnabled
                ? "请打开飞牛 noVNC 远程验证画面完成滑块；验证通过后系统会自动回收 x5sec 并恢复连接。"
                : "网页验证不会自动把浏览器 Cookie 传回服务器，请完成验证后更新 Cookie；更新后系统才会重新获取 Token 并恢复连接");

        return new ResultObject<>(1001, result.getMessage(), result);
    }

    /**
     * 创建服务器滑块验证会话
     */
    /*
    @PostMapping("/captcha/session/start")
    public ResultObject<CaptchaSessionService.CaptchaSessionResult> startCaptchaSession(
            @RequestBody StartCaptchaSessionReqDTO reqDTO) {
        try {
            CaptchaSessionService.CaptchaSessionResult result = captchaSessionService.startSession(
                    reqDTO.getXianyuAccountId(), reqDTO.getCaptchaUrl());
            return ResultObject.success(result);
        } catch (Exception e) {
            log.error("创建服务器滑块验证会话失败: accountId={}", reqDTO.getXianyuAccountId(), e);
            return ResultObject.failed("验证页面暂时无法加载，请点击重新加载；若仍失败，请稍后重试。");
        }
    }

    /**
     * 回放滑块拖动轨迹并同步验证Cookie
     * /
    @PostMapping("/captcha/session/drag")
    public ResultObject<CaptchaSessionService.CaptchaSessionResult> replayCaptchaDrag(
            @RequestBody ReplayCaptchaDragReqDTO reqDTO) {
        try {
            java.util.List<CaptchaSessionService.DragPoint> points = reqDTO.getPoints() == null
                    ? java.util.List.of()
                    : reqDTO.getPoints().stream()
                            .map(point -> new CaptchaSessionService.DragPoint(
                                    point.getX(), point.getY(), point.getDelayMs()))
                            .toList();
            CaptchaSessionService.CaptchaSessionResult result = captchaSessionService.replayDrag(
                    reqDTO.getXianyuAccountId(), reqDTO.getSessionId(), points);
            return ResultObject.success(result);
        } catch (Exception e) {
            log.error("回放滑块拖动轨迹失败: accountId={}", reqDTO.getXianyuAccountId(), e);
            return ResultObject.failed(e.getMessage());
        }
    }

    /**
     * 刷新同一滑块验证会话的预览图，避免首次截图仍处于页面骨架屏。
     * /
    @PostMapping("/captcha/session/preview")
    public ResultObject<CaptchaSessionService.CaptchaSessionResult> refreshCaptchaPreview(
            @RequestBody CloseCaptchaSessionReqDTO reqDTO) {
        try {
            CaptchaSessionService.CaptchaSessionResult result = captchaSessionService.refreshPreview(
                    reqDTO.getXianyuAccountId(), reqDTO.getSessionId());
            return ResultObject.success(result);
        } catch (Exception e) {
            log.warn("刷新滑块验证预览失败: accountId={}", reqDTO.getXianyuAccountId(), e);
            return ResultObject.failed("验证画面暂时无法刷新，请点击重新加载");
        }
    }

    /**
     * 释放服务器滑块验证会话
     * /
    @PostMapping("/captcha/session/close")
    public ResultObject<String> closeCaptchaSession(@RequestBody CloseCaptchaSessionReqDTO reqDTO) {
        captchaSessionService.closeSession(reqDTO.getXianyuAccountId(), reqDTO.getSessionId());
        return ResultObject.success("滑块验证会话已释放");
    }

    /**
     * 更新Cookie
     * /
    */
    @PostMapping("/updateCookie")
    public ResultObject<UpdateCookieRespDTO> updateCookie(@RequestBody UpdateCookieReqDTO reqDTO) {
        try {
            log.info("更新Cookie请求: xianyuAccountId={}", reqDTO.getXianyuAccountId());
            
            if (reqDTO.getXianyuAccountId() == null) {
                return ResultObject.failed("账号ID不能为空");
            }
            
            if (reqDTO.getCookieText() == null || reqDTO.getCookieText().trim().isEmpty()) {
                return ResultObject.failed("Cookie不能为空");
            }
            
            // 检查账号是否存在
            com.yuzhanggui.mapper.XianyuAccountMapper accountMapper = 
                    applicationContext.getBean(com.yuzhanggui.mapper.XianyuAccountMapper.class);
            XianyuAccount account = accountMapper.selectById(reqDTO.getXianyuAccountId());
            if (account == null) {
                return ResultObject.failed("账号不存在");
            }
            
            // 从Cookie中提取UNB
            String unb = extractUnbFromCookie(reqDTO.getCookieText());
            if (unb == null || unb.isEmpty()) {
                return ResultObject.failed("无法从Cookie中提取UNB信息，请确保Cookie包含unb字段");
            }
            if (account.getUnb() != null && !account.getUnb().isBlank() && !account.getUnb().equals(unb)) {
                return ResultObject.failed("该Cookie属于其他闲鱼账号，请核对当前账号备注和UNB后重试");
            }


            
            // 更新Cookie
            com.yuzhanggui.service.AccountService accountService = 
                    applicationContext.getBean(com.yuzhanggui.service.AccountService.class);
            boolean updated = accountService.updateAccountCookie(reqDTO.getXianyuAccountId(), unb, reqDTO.getCookieText());
            if (!updated) {
                return ResultObject.failed("Cookie更新失败");
            }

            boolean accountDisabled = Integer.valueOf(0).equals(account.getStatus());
            // Cookie保存成功后立即清理旧凭证状态并重建连接
            boolean connected = !accountDisabled
                    && webSocketService.restartAfterCredentialUpdate(reqDTO.getXianyuAccountId());
            boolean needVerification = !accountDisabled && !connected
                    && webSocketTokenService.isCaptchaPending(reqDTO.getXianyuAccountId());
            
            // 记录操作日志
            operationLogService.log(reqDTO.getXianyuAccountId(),
                    com.yuzhanggui.constants.OperationConstants.Type.UPDATE,
                    com.yuzhanggui.constants.OperationConstants.Module.COOKIE,
                    "Cookie手动更新成功",
                    com.yuzhanggui.constants.OperationConstants.Status.SUCCESS,
                    com.yuzhanggui.constants.OperationConstants.TargetType.COOKIE,
                    String.valueOf(reqDTO.getXianyuAccountId()),
                    null, null, null, null);
            
            UpdateCookieRespDTO respDTO = new UpdateCookieRespDTO();
            respDTO.setMessage(accountDisabled
                    ? "Cookie更新成功；账号保持禁用，启用后再建立连接"
                    : connected
                        ? "Cookie更新成功，WebSocket已重连"
                        : needVerification
                            ? "Cookie更新成功，但账号需要完成滑块验证。" + (captchaRemoteEnabled
                                ? "请在账号管理发起远程验证，再打开 VNC（" + (captchaRemoteUrl == null || captchaRemoteUrl.isBlank()
                                    ? "服务器 " + captchaRemotePort + " 端口 /vnc.html" : captchaRemoteUrl)
                                    + "）完成滑块，验证通过后系统会尝试恢复连接。"
                                : "请启用远程验证，或在对应账号浏览器完成验证后更新Cookie；自动重连已暂停。")
                            : "Cookie更新成功，WebSocket正在重连");
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("更新Cookie失败", e);
            
            // 记录失败日志
            operationLogService.log(reqDTO.getXianyuAccountId(),
                    com.yuzhanggui.constants.OperationConstants.Type.UPDATE,
                    com.yuzhanggui.constants.OperationConstants.Module.COOKIE,
                    "Cookie手动更新失败",
                    com.yuzhanggui.constants.OperationConstants.Status.FAIL,
                    com.yuzhanggui.constants.OperationConstants.TargetType.COOKIE,
                    String.valueOf(reqDTO.getXianyuAccountId()),
                    null, null, e.getMessage(), null);
            
            return ResultObject.failed("更新Cookie失败: " + e.getMessage());
        }
    }
    
    /**
     * 从Cookie字符串中提取UNB值
     *
     * @param cookie Cookie字符串
     * @return UNB值，如果未找到则返回null
     */
    /**
     * 手动刷新Token
     */
    @PostMapping("/refreshToken")
    public ResultObject<RefreshTokenRespDTO> refreshToken(@RequestBody RefreshTokenReqDTO reqDTO) {
        try {
            log.info("手动刷新Token请求: xianyuAccountId={}", reqDTO.getXianyuAccountId());
            
            if (reqDTO.getXianyuAccountId() == null) {
                return ResultObject.failed("账号ID不能为空");
            }
            
            RefreshTokenRespDTO respDTO = new RefreshTokenRespDTO();
            
            // 刷新_m_h5_tk token
            log.info("【账号{}】开始刷新_m_h5_tk token...", reqDTO.getXianyuAccountId());
            Mh5tkRefreshResult mh5tkResult = tokenRefreshService.refreshMh5tkTokenDetailed(reqDTO.getXianyuAccountId());
            // 未取得新 Token 不能证明旧 Token 仍然有效。
            boolean mh5tkSuccess = mh5tkResult == Mh5tkRefreshResult.REFRESHED;
            respDTO.setMh5tkRefreshed(mh5tkSuccess);
            
            // 刷新WebSocket token
            log.info("【账号{}】开始刷新WebSocket token...", reqDTO.getXianyuAccountId());
            boolean wsTokenSuccess = tokenRefreshService.refreshWebSocketToken(reqDTO.getXianyuAccountId());
            respDTO.setWsTokenRefreshed(wsTokenSuccess);
            
            if (mh5tkSuccess && wsTokenSuccess) {
                String mh5tkNote = "";
                respDTO.setMessage("✅ 所有Token刷新成功" + mh5tkNote);
                log.info("【账号{}】✅ 所有Token刷新成功{}", reqDTO.getXianyuAccountId(), mh5tkNote);
                
                operationLogService.log(reqDTO.getXianyuAccountId(),
                        com.yuzhanggui.constants.OperationConstants.Type.REFRESH,
                        com.yuzhanggui.constants.OperationConstants.Module.TOKEN,
                        "Token手动刷新成功",
                        com.yuzhanggui.constants.OperationConstants.Status.SUCCESS,
                        com.yuzhanggui.constants.OperationConstants.TargetType.TOKEN,
                        String.valueOf(reqDTO.getXianyuAccountId()),
                        null, null, null, null);
                
                return ResultObject.success(respDTO);
            } else if (mh5tkSuccess || wsTokenSuccess) {
                respDTO.setMessage("⚠️ 部分Token刷新成功" + (mh5tkResult == Mh5tkRefreshResult.UNCHANGED
                        ? "；未取得新的签名Token，无法确认旧Token是否有效" : ""));
                log.warn("【账号{}】⚠️ 部分Token刷新成功: mh5tkSuccess={}, wsTokenSuccess={}",
                        reqDTO.getXianyuAccountId(), mh5tkSuccess, wsTokenSuccess);
                
                operationLogService.log(reqDTO.getXianyuAccountId(),
                        com.yuzhanggui.constants.OperationConstants.Type.REFRESH,
                        com.yuzhanggui.constants.OperationConstants.Module.TOKEN,
                        "Token部分刷新成功",
                        com.yuzhanggui.constants.OperationConstants.Status.PARTIAL,
                        com.yuzhanggui.constants.OperationConstants.TargetType.TOKEN,
                        String.valueOf(reqDTO.getXianyuAccountId()),
                        null, null, "_m_h5_tk=" + mh5tkSuccess + ", ws=" + wsTokenSuccess, null);
                
                return ResultObject.success(respDTO);
            } else {
                respDTO.setMessage("❌ Token刷新失败，请检查Cookie是否有效");
                log.error("【账号{}】❌ Token刷新失败", reqDTO.getXianyuAccountId());
                
                operationLogService.log(reqDTO.getXianyuAccountId(),
                        com.yuzhanggui.constants.OperationConstants.Type.REFRESH,
                        com.yuzhanggui.constants.OperationConstants.Module.TOKEN,
                        "Token手动刷新失败",
                        com.yuzhanggui.constants.OperationConstants.Status.FAIL,
                        com.yuzhanggui.constants.OperationConstants.TargetType.TOKEN,
                        String.valueOf(reqDTO.getXianyuAccountId()),
                        null, null, "Cookie可能无效", null);
                
                return ResultObject.failed("Token刷新失败，请检查Cookie是否有效");
            }
            
        } catch (Exception e) {
            log.error("手动刷新Token异常: xianyuAccountId={}", reqDTO.getXianyuAccountId(), e);
            
            operationLogService.log(reqDTO.getXianyuAccountId(),
                    com.yuzhanggui.constants.OperationConstants.Type.REFRESH,
                    com.yuzhanggui.constants.OperationConstants.Module.TOKEN,
                    "Token手动刷新异常",
                    com.yuzhanggui.constants.OperationConstants.Status.FAIL,
                    com.yuzhanggui.constants.OperationConstants.TargetType.TOKEN,
                    String.valueOf(reqDTO.getXianyuAccountId()),
                    null, null, e.getMessage(), null);
            
            return ResultObject.failed("刷新Token异常: " + e.getMessage());
        }
    }
    
    /**
     * 手动更新WebSocket Token
     */
    @PostMapping("/updateToken")
    public ResultObject<String> updateToken(@RequestBody UpdateTokenReqDTO reqDTO) {
        try {
            log.info("手动更新Token请求: xianyuAccountId={}", reqDTO.getXianyuAccountId());
            
            if (reqDTO.getXianyuAccountId() == null) {
                return ResultObject.failed("账号ID不能为空");
            }
            
            if (reqDTO.getWebsocketToken() == null || reqDTO.getWebsocketToken().trim().isEmpty()) {
                return ResultObject.failed("WebSocket Token不能为空");
            }
            
            // 获取WebSocketTokenService
            com.yuzhanggui.service.WebSocketTokenService tokenService = 
                    applicationContext.getBean(com.yuzhanggui.service.WebSocketTokenService.class);
            
            // 保存Token
            tokenService.saveToken(reqDTO.getXianyuAccountId(), reqDTO.getWebsocketToken().trim());
            
            log.info("【账号{}】✅ WebSocket Token手动更新成功", reqDTO.getXianyuAccountId());
            
            operationLogService.log(reqDTO.getXianyuAccountId(),
                    com.yuzhanggui.constants.OperationConstants.Type.UPDATE,
                    com.yuzhanggui.constants.OperationConstants.Module.TOKEN,
                    "WebSocket Token手动更新成功",
                    com.yuzhanggui.constants.OperationConstants.Status.SUCCESS,
                    com.yuzhanggui.constants.OperationConstants.TargetType.TOKEN,
                    String.valueOf(reqDTO.getXianyuAccountId()),
                    null, null, null, null);
            
            return ResultObject.success("Token更新成功");
            
        } catch (Exception e) {
            log.error("手动更新Token异常: xianyuAccountId={}", reqDTO.getXianyuAccountId(), e);
            
            operationLogService.log(reqDTO.getXianyuAccountId(),
                    com.yuzhanggui.constants.OperationConstants.Type.UPDATE,
                    com.yuzhanggui.constants.OperationConstants.Module.TOKEN,
                    "WebSocket Token手动更新失败",
                    com.yuzhanggui.constants.OperationConstants.Status.FAIL,
                    com.yuzhanggui.constants.OperationConstants.TargetType.TOKEN,
                    String.valueOf(reqDTO.getXianyuAccountId()),
                    null, null, e.getMessage(), null);
            
            return ResultObject.failed("更新Token异常: " + e.getMessage());
        }
    }
    
    /**
     * 刷新Cookie
     * 参考Python: 通过hasLogin接口刷新Cookie
     */
    @PostMapping("/refreshCookie")
    public ResultObject<String> refreshCookie(@RequestBody RefreshCookieReqDTO reqDTO) {
        try {
            log.info("手动刷新Cookie请求: xianyuAccountId={}", reqDTO.getXianyuAccountId());
            
            if (reqDTO.getXianyuAccountId() == null) {
                return ResultObject.failed("账号ID不能为空");
            }
            
            // 调用Cookie刷新服务
            boolean success = cookieRefreshService.refreshCookie(reqDTO.getXianyuAccountId());
            
            if (success) {
                log.info("【账号{}】✅ Cookie刷新成功", reqDTO.getXianyuAccountId());
                return ResultObject.success("Cookie刷新成功");
            } else {
                log.error("【账号{}】❌ Cookie刷新失败，请手动更新Cookie", reqDTO.getXianyuAccountId());
                return ResultObject.failed("Cookie刷新失败，请手动更新Cookie");
            }
            
        } catch (Exception e) {
            log.error("手动刷新Cookie异常: xianyuAccountId={}", reqDTO.getXianyuAccountId(), e);
            return ResultObject.failed("刷新Cookie异常: " + e.getMessage());
        }
    }
    
    /**
     * 检查登录状态
     */
    @PostMapping("/checkLogin")
    public ResultObject<String> checkLogin(@RequestBody CheckLoginReqDTO reqDTO) {
        try {
            log.info("检查登录状态请求: xianyuAccountId={}", reqDTO.getXianyuAccountId());
            
            if (reqDTO.getXianyuAccountId() == null) {
                return ResultObject.failed("账号ID不能为空");
            }
            
            // 调用Cookie刷新服务检查登录状态
            boolean valid = cookieRefreshService.checkLoginStatus(reqDTO.getXianyuAccountId());
            
            if (valid) {
                log.info("【账号{}】✅ 登录状态有效", reqDTO.getXianyuAccountId());
                return ResultObject.success("登录状态有效");
            } else {
                log.warn("【账号{}】⚠️ 登录状态无效，请更新Cookie", reqDTO.getXianyuAccountId());
                return ResultObject.failed("登录状态无效，请更新Cookie");
            }
            
        } catch (Exception e) {
            log.error("检查登录状态异常: xianyuAccountId={}", reqDTO.getXianyuAccountId(), e);
            return ResultObject.failed("检查登录状态异常: " + e.getMessage());
        }
    }

    private String extractUnbFromCookie(String cookie) {
        if (cookie == null || cookie.isEmpty()) {
            return null;
        }
        
        // 查找unb=后面的值
        String[] cookieParts = cookie.split(";\\s*");
        for (String part : cookieParts) {
            if (part.startsWith("unb=")) {
                return part.substring(4); // "unb=".length() = 4
            }
        }
        
        return null;
    }

    /**
     * 启动WebSocket连接请求DTO
     */
    @Data
    public static class StartWebSocketReqDTO {
        private Long xianyuAccountId;  // 账号ID
        private String accessToken;    // 可选：手动提供的accessToken
    }

    /**
     * 停止WebSocket连接请求DTO
     */
    @Data
    public static class StopWebSocketReqDTO {
        private Long xianyuAccountId;  // 账号ID
    }

    /**
     * 获取WebSocket状态请求DTO
     */
    @Data
    public static class GetWebSocketStatusReqDTO {
        private Long xianyuAccountId;  // 账号ID
    }

    /**
     * 清除验证等待状态请求DTO
     */
    @Data
    public static class ClearCaptchaWaitReqDTO {
        private Long xianyuAccountId;  // 账号ID
    }

    /**
     * 创建滑块验证会话请求DTO
     */
    @Data
    public static class StartCaptchaSessionReqDTO {
        private Long xianyuAccountId;  // 账号ID
        private String captchaUrl;     // 风控验证链接
    }

    /**
     * 回放滑块轨迹请求DTO
     */
    @Data
    public static class ReplayCaptchaDragReqDTO {
        private Long xianyuAccountId;             // 账号ID
        private String sessionId;                 // 验证会话ID
        private java.util.List<CaptchaDragPointDTO> points; // 拖动轨迹
    }

    /**
     * 滑块轨迹坐标DTO
     */
    @Data
    public static class CaptchaDragPointDTO {
        private double x;      // 浏览器横坐标
        private double y;      // 浏览器纵坐标
        private int delayMs;   // 与上一坐标的间隔
    }

    /**
     * 关闭滑块验证会话请求DTO
     */
    @Data
    public static class CloseCaptchaSessionReqDTO {
        private Long xianyuAccountId;  // 账号ID
        private String sessionId;      // 验证会话ID
    }
    
    /**
     * 手动刷新Token请求DTO
     */
    @Data
    public static class RefreshTokenReqDTO {
        private Long xianyuAccountId;  // 账号ID
    }
    
    /**
     * 手动更新Token请求DTO
     */
    @Data
    public static class UpdateTokenReqDTO {
        private Long xianyuAccountId;    // 账号ID
        private String websocketToken;   // WebSocket Token
    }
    
    /**
     * 手动刷新Token响应DTO
     */
    @Data
    public static class RefreshTokenRespDTO {
        private Boolean mh5tkRefreshed;   // _m_h5_tk是否刷新成功
        private Boolean wsTokenRefreshed; // websocket_token是否刷新成功
        private String message;           // 提示信息
    }

    /**
     * WebSocket状态响应DTO
     */
    @Data
    public static class WebSocketStatusRespDTO {
        private Long xianyuAccountId;  // 账号ID
        private Boolean connected;     // 是否已连接
        private String status;         // 连接状态描述
        private Integer cookieStatus;  // Cookie状态 1:有效 2:过期 3:失效
        private Boolean cookieConfigured;         // Cookie是否已配置，不返回凭证正文
        private Boolean mh5TkConfigured;          // H5 Token是否已配置
        private Boolean websocketTokenConfigured; // WebSocket Token是否已配置
        private String cookieText;
        private String mh5Tk;
        private String websocketToken;
        private Long tokenExpireTime;  // Token过期时间戳（毫秒）
        private Boolean tokenExpiryKnown;
        private Long tokenLastRefreshTime;
        private String tokenRenewalState;
        private String tokenRenewalMessage;
        private Long tokenRenewalUpdatedAt;
        private Long tokenRenewalNextRetryAt;
        private Boolean captchaRequired;
        private String captchaUrl;
        private Boolean captchaRemoteEnabled;
        private String captchaRemoteUrl;
        private Integer captchaRemotePort;
        private Boolean autoDeliveryOn; // 是否有商品开启了自动发货
        private Boolean autoReplyOn;     // 是否有商品开启了自动回复
    }
    
    /**
     * 滑块验证信息响应DTO
     */
    @Data
    public static class CaptchaInfoDTO {
        private Boolean needCaptcha;  // 是否需要验证
        private String captchaUrl;    // 验证链接
        private Boolean remoteBrowserEnabled;
        private String remoteBrowserUrl;
        private Integer remoteBrowserPort;
        private String message;       // 提示信息
    }

    @Data
    public static class CaptchaVerificationRespDTO {
        private Boolean connected;
        private Boolean needCaptcha;
        private String captchaUrl;
        private Boolean captchaRemoteEnabled;
        private String captchaRemoteUrl;
        private Integer captchaRemotePort;
        private String message;
    }
    
    /**
     * 发送消息请求DTO
     */
    @Data
    public static class SendMessageReqDTO {
        private Long xianyuAccountId;  // 账号ID
        private String cid;            // 会话ID（不带@goofish后缀）
        private String toId;           // 接收方用户ID（不带@goofish后缀）
        private String text;           // 消息文本内容
        private String xyGoodsId;      // 闲鱼商品ID
    }
    
    /**
     * 发送图片消息请求DTO
     */
    @Data
    public static class SendImageMessageReqDTO {
        private Long xianyuAccountId;  // 账号ID
        private String cid;            // 会话ID（不带@goofish后缀）
        private String toId;           // 接收方用户ID（不带@goofish后缀）
        private String imageUrl;       // 图片URL
        private Integer width;         // 兼容旧客户端，服务端会重新读取真实宽度
        private Integer height;        // 兼容旧客户端，服务端会重新读取真实高度
        private String xyGoodsId;      // 闲鱼商品ID（可选）
    }
    
    /**
     * 刷新Cookie请求DTO
     */
    @Data
    public static class RefreshCookieReqDTO {
        private Long xianyuAccountId;  // 账号ID
    }
    
    /**
     * 检查登录状态请求DTO
     */
    @Data
    public static class CheckLoginReqDTO {
        private Long xianyuAccountId;  // 账号ID
    }
}
