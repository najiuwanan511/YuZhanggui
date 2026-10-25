package com.yuzhanggui.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhanggui.entity.*;
import com.yuzhanggui.event.chatMessageEvent.ChatMessageData;
import com.yuzhanggui.mapper.*;
import com.yuzhanggui.service.EnhancedMessageSendService;
import com.yuzhanggui.service.SysSettingService;
import com.yuzhanggui.service.ZeroBridgeService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.NoTransactionException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ZeroBridgeServiceImpl implements ZeroBridgeService {
    public static final String ENABLED_KEY = "zero_bridge_enabled";
    public static final String BASE_URL_KEY = "zero_bridge_base_url";
    public static final String TOKEN_KEY = "zero_bridge_api_token";
    public static final String CALLBACK_SECRET_KEY = "zero_bridge_callback_secret";

    private final XianyuZeroBridgeOrderMapper bridgeMapper;
    private final XianyuZeroSubmissionMapper submissionMapper;
    private final XianyuZeroCallbackEventMapper eventMapper;
    private final XianyuGoodsOrderMapper orderMapper;
    private final SysSettingService settingService;
    private final EnhancedMessageSendService messageSendService;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();

    public ZeroBridgeServiceImpl(XianyuZeroBridgeOrderMapper bridgeMapper,
                                 XianyuZeroSubmissionMapper submissionMapper,
                                 XianyuZeroCallbackEventMapper eventMapper,
                                 XianyuGoodsOrderMapper orderMapper,
                                 SysSettingService settingService,
                                 EnhancedMessageSendService messageSendService,
                                 ObjectMapper objectMapper) {
        this.bridgeMapper = bridgeMapper;
        this.submissionMapper = submissionMapper;
        this.eventMapper = eventMapper;
        this.orderMapper = orderMapper;
        this.settingService = settingService;
        this.messageSendService = messageSendService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean isEnabled() {
        return "true".equalsIgnoreCase(trim(settingService.getSettingValue(ENABLED_KEY)));
    }

    @Override
    public boolean hasActiveSession(Long accountId, String sid, String buyerId) {
        return accountId != null && !blank(sid) && !blank(buyerId)
                && bridgeMapper.countActiveSession(accountId, sid, normalizeUserId(buyerId)) > 0;
    }

    @Override
    @Transactional
    public void initializeCollection(XianyuGoodsOrder order, XianyuGoodsAutoDeliveryConfig config, int buyNum) {
        if (!isEnabled()) {
            throw new IllegalStateException("Zero 对接尚未启用，请先在系统设置中完成配置");
        }
        if (order == null || order.getId() == null || blank(order.getOrderId()) || blank(order.getBuyerUserId()) || blank(order.getSid())) {
            throw new IllegalStateException("订单缺少 Zero 对接所需的订单、买家或会话信息");
        }
        XianyuZeroBridgeOrder existing = bridgeMapper.selectByGoodsOrderId(order.getId());
        if (existing != null) {
            orderMapper.markZeroWaitingInput(order.getId());
            return;
        }

        int perItem = config.getZeroInputCount() == null ? 1 : Math.max(1, Math.min(config.getZeroInputCount(), 100));
        int expected = Math.max(1, Math.min(100, perItem * Math.max(1, buyNum)));
        XianyuZeroBridgeOrder bridge = new XianyuZeroBridgeOrder();
        bridge.setGoodsOrderId(order.getId());
        bridge.setXianyuAccountId(order.getXianyuAccountId());
        bridge.setExternalOrderId(order.getOrderId());
        bridge.setXyGoodsId(order.getXyGoodsId());
        bridge.setSkuId(order.getSkuId());
        bridge.setBuyerUserId(normalizeUserId(order.getBuyerUserId()));
        bridge.setBuyerUserName(order.getBuyerUserName());
        bridge.setSid(order.getSid());
        bridge.setExpectedCount(expected);
        bridge.setCollectedCount(0);
        bridge.setStatus("WAITING_INPUT");
        bridge.setSubmitAttempts(0);
        bridge.setReplyAttempts(0);
        bridgeMapper.insert(bridge);
        orderMapper.markZeroWaitingInput(order.getId());

        String prompt = "下单信息已收到✅\n\n📋 请逐条发送本次需要处理的内容\n📊 当前进度：0/" + expected
                + "\n❌ 还需：" + expected + " 个\n\n收齐后系统会自动提交，请不要重复发送。";
        sendBuyerMessage(bridge, prompt);
        log.info("【账号{}】Zero 收集会话已建立: orderId={}, expected={}",
                order.getXianyuAccountId(), order.getOrderId(), expected);
    }

    @Override
    @Transactional
    public boolean collectBuyerMessage(ChatMessageData message) {
        if (message == null || message.getContentType() == null || message.getContentType() != 1
                || blank(message.getMsgContent()) || blank(message.getSId()) || blank(message.getSenderUserId())) {
            return false;
        }
        String buyerId = normalizeUserId(message.getSenderUserId());
        XianyuZeroBridgeOrder bridge = bridgeMapper.selectWaitingSession(
                message.getXianyuAccountId(), message.getSId(), buyerId);
        if (bridge == null) return false;

        String pnmId = blank(message.getPnmId()) ? UUID.randomUUID().toString() : message.getPnmId().trim();
        if (submissionMapper.countMessage(bridge.getId(), pnmId) > 0) return true;
        String content = message.getMsgContent().trim();
        if (content.length() > 20_000) {
            sendBuyerMessage(bridge, "本条内容过长，请拆分后重新发送。");
            return true;
        }

        XianyuZeroSubmission submission = new XianyuZeroSubmission();
        submission.setBridgeOrderId(bridge.getId());
        submission.setLineId(pnmId);
        submission.setPnmId(pnmId);
        submission.setContent(content);
        try {
            submissionMapper.insert(submission);
        } catch (DuplicateKeyException duplicate) {
            return true;
        }
        if (bridgeMapper.incrementCollected(bridge.getId()) == 0) return true;
        bridge = bridgeMapper.selectById(bridge.getId());
        int collected = bridge.getCollectedCount() == null ? 0 : bridge.getCollectedCount();
        int expected = bridge.getExpectedCount() == null ? 1 : bridge.getExpectedCount();
        int remaining = Math.max(0, expected - collected);
        if (remaining == 0) {
            orderMapper.updateZeroProgress(bridge.getGoodsOrderId(), "ZERO_SUBMITTING", null, null);
        }
        String reply = remaining == 0
                ? "✅ 已收齐全部 " + expected + " 条下单信息，正在提交处理，请稍候…"
                : "下单信息已收到✅\n\n📋 本次内容：" + abbreviate(content, 120)
                    + "\n📊 当前进度：" + collected + "/" + expected
                    + "\n❌ 还需：" + remaining + " 个\n\n请继续逐条发送，收齐后系统会自动提交。";
        sendBuyerMessage(bridge, reply);
        return true;
    }

    @Override
    public boolean testConnection() {
        try {
            Request request = requestBuilder("/api/integrations/xianyu/health").get().build();
            try (Response response = httpClient.newCall(request).execute()) {
                return response.isSuccessful() && response.body() != null
                        && objectMapper.readTree(response.body().string()).path("ok").asBoolean(false);
            }
        } catch (Exception e) {
            log.warn("Zero 连通性测试失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    @Transactional
    public CallbackResult acceptCallback(String rawBody, String eventId, String timestamp, String signature) {
        String secret = trim(settingService.getSettingValue(CALLBACK_SECRET_KEY));
        if (secret.length() < 16) return new CallbackResult(503, "回调密钥尚未配置或长度不足 16 位");
        if (blank(rawBody) || blank(eventId) || blank(timestamp) || blank(signature)) {
            return new CallbackResult(400, "缺少回调正文或签名头");
        }
        if (rawBody.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) {
            return new CallbackResult(413, "回调正文不能超过 1 MB");
        }
        long seconds;
        try {
            seconds = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return new CallbackResult(401, "回调时间戳无效");
        }
        if (Math.abs(Instant.now().getEpochSecond() - seconds) > 300) {
            return new CallbackResult(401, "回调已过期");
        }
        String expected = "sha256=" + hmacSha256(secret, timestamp + "." + rawBody);
        if (!constantTimeEquals(expected, signature.trim())) {
            return new CallbackResult(401, "回调签名无效");
        }
        if (eventMapper.countByEventId(eventId.trim()) > 0) {
            return new CallbackResult(200, "重复事件已忽略");
        }

        try {
            JsonNode payload = objectMapper.readTree(rawBody);
            if (!eventId.trim().equals(payload.path("eventId").asText())) {
                return new CallbackResult(400, "事件编号不一致");
            }
            Long accountId = parseLong(payload.path("accountId").asText());
            String orderId = payload.path("orderId").asText();
            String lineId = payload.path("lineId").asText();
            String status = normalizeCallbackStatus(payload.path("status").asText());
            if (accountId == null || blank(orderId) || blank(lineId) || status == null) {
                return new CallbackResult(400, "回调订单字段或状态无效");
            }
            XianyuZeroBridgeOrder bridge = bridgeMapper.selectByExternalOrder(accountId, orderId);
            if (bridge == null) return new CallbackResult(404, "未找到对应闲鱼订单");
            if (!Objects.equals(normalizeUserId(bridge.getBuyerUserId()), normalizeUserId(payload.path("buyerId").asText()))
                    || !Objects.equals(bridge.getSid(), payload.path("chatId").asText())) {
                return new CallbackResult(409, "回调买家或会话与订单不匹配");
            }
            boolean lineExists = submissionMapper.selectByBridgeId(bridge.getId()).stream()
                    .anyMatch(item -> lineId.equals(item.getLineId()));
            if (!lineExists) return new CallbackResult(409, "回调明细不属于该订单");

            XianyuZeroCallbackEvent event = new XianyuZeroCallbackEvent();
            event.setEventId(eventId.trim());
            event.setBridgeOrderId(bridge.getId());
            event.setLineId(lineId);
            event.setStatus(status);
            event.setPayloadHash(sha256(rawBody));
            event.setPayloadJson(rawBody);
            try {
                eventMapper.insert(event);
            } catch (DuplicateKeyException duplicate) {
                return new CallbackResult(200, "重复事件已忽略");
            }

            if (("完成".equals(status) || "失败".equals(status))
                    && eventMapper.countTerminalLines(bridge.getId()) >= bridge.getExpectedCount()) {
                String summary = buildResultSummary(bridge, eventMapper.selectTerminalEvents(bridge.getId()));
                bridgeMapper.markResultReady(bridge.getId(), summary);
            }
            return new CallbackResult(200, "回调已接收");
        } catch (Exception e) {
            try {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            } catch (NoTransactionException ignored) {
                // Direct unit calls do not run through Spring's transactional proxy.
            }
            log.warn("处理 Zero 回调失败: eventId={}, error={}", eventId, e.getMessage());
            return new CallbackResult(400, "回调正文无法解析");
        }
    }

    @Transactional
    public void finalizeReply(XianyuZeroBridgeOrder bridge, boolean failed) {
        String finalStatus = failed ? "FAILED" : "COMPLETED";
        if (orderMapper.updateZeroResult(bridge.getGoodsOrderId(), finalStatus, failed ? -1 : 1,
                bridge.getResultSummary(), failed ? "Zero 返回处理失败" : null) == 0) {
            throw new IllegalStateException("闲鱼订单状态更新失败");
        }
        if (bridgeMapper.markReplySent(bridge.getId(), finalStatus) == 0) {
            throw new IllegalStateException("Zero 桥接订单状态更新失败");
        }
    }

    public void markOrderProgress(Long goodsOrderId, String status, String errorCode, String message) {
        orderMapper.updateZeroProgress(goodsOrderId, status, errorCode, message);
    }

    String submit(XianyuZeroBridgeOrder bridge) throws Exception {
        List<XianyuZeroSubmission> submissions = submissionMapper.selectByBridgeId(bridge.getId());
        List<Map<String, String>> items = submissions.stream()
                .map(item -> Map.of("lineId", item.getLineId(), "content", item.getContent()))
                .toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", bridge.getExternalOrderId());
        payload.put("accountId", String.valueOf(bridge.getXianyuAccountId()));
        payload.put("goodsId", bridge.getXyGoodsId());
        payload.put("skuId", Objects.toString(bridge.getSkuId(), ""));
        payload.put("buyerId", bridge.getBuyerUserId());
        payload.put("buyerName", Objects.toString(bridge.getBuyerUserName(), ""));
        payload.put("chatId", bridge.getSid());
        payload.put("saleAmount", 0);
        payload.put("items", items);
        String json = objectMapper.writeValueAsString(payload);
        Request request = requestBuilder("/api/integrations/xianyu/orders")
                .post(RequestBody.create(json, MediaType.get("application/json; charset=utf-8"))).build();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) throw new IllegalStateException("Zero HTTP " + response.code() + ": " + abbreviate(body, 300));
            JsonNode result = objectMapper.readTree(body);
            if (!result.path("ok").asBoolean(false)) throw new IllegalStateException("Zero 拒绝订单: " + abbreviate(body, 300));
            return body;
        }
    }

    JsonNode query(XianyuZeroBridgeOrder bridge) throws Exception {
        String json = objectMapper.writeValueAsString(Map.of(
                "orderId", bridge.getExternalOrderId(),
                "accountId", String.valueOf(bridge.getXianyuAccountId())));
        Request request = requestBuilder("/api/integrations/xianyu/orders/query")
                .post(RequestBody.create(json, MediaType.get("application/json; charset=utf-8"))).build();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) throw new IllegalStateException("Zero 查询 HTTP " + response.code());
            return objectMapper.readTree(body);
        }
    }

    boolean sendBuyerMessage(XianyuZeroBridgeOrder bridge, String text) {
        String buyer = normalizeUserId(bridge.getBuyerUserId());
        String chat = bridge.getSid().replace("@goofish", "");
        return messageSendService.sendMessageWithRetry(bridge.getXianyuAccountId(), chat, buyer, text, false)
                == EnhancedMessageSendService.MessageSendResult.SUCCESS;
    }

    private Request.Builder requestBuilder(String path) {
        String baseUrl = trim(settingService.getSettingValue(BASE_URL_KEY));
        String token = trim(settingService.getSettingValue(TOKEN_KEY));
        if (baseUrl.isEmpty() || token.isEmpty()) throw new IllegalStateException("Zero 地址或 API Token 尚未配置");
        while (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        HttpUrl url = HttpUrl.parse(baseUrl + path);
        if (url == null) throw new IllegalStateException("Zero 地址格式不正确");
        if (!"https".equalsIgnoreCase(url.scheme()) && !isPrivateHost(url.host())) {
            throw new IllegalStateException("远程 Zero 地址必须使用 HTTPS");
        }
        return new Request.Builder().url(url).header("X-Zero-Token", token);
    }

    private String buildResultSummary(XianyuZeroBridgeOrder bridge, List<XianyuZeroCallbackEvent> events) throws Exception {
        boolean failed = events.stream().anyMatch(event -> "失败".equals(event.getStatus()));
        List<String> details = new ArrayList<>();
        String project = "";
        String completedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Map<String, XianyuZeroCallbackEvent> latestByLine = new LinkedHashMap<>();
        for (XianyuZeroCallbackEvent event : events) latestByLine.put(event.getLineId(), event);
        for (XianyuZeroCallbackEvent event : latestByLine.values()) {
            JsonNode node = objectMapper.readTree(event.getPayloadJson());
            if (project.isBlank()) project = node.path("projectName").asText();
            String zeroNo = node.path("zeroOrderNo").asText();
            String content = node.path("content").asText();
            String remark = node.path("remark").asText();
            String line = "🧾 订单号：" + zeroNo + "\n🎫 邀请码：" + content;
            if ("失败".equals(event.getStatus()) && !remark.isBlank()) line += "\n📝 原因：" + remark;
            details.add(line);
            if (!node.path("completedAt").asText().isBlank()) completedAt = node.path("completedAt").asText();
        }
        return (failed ? "❌ 订单处理失败" : "✅ 订单完成🎉")
                + "\n────────────\n📦 项目：" + project
                + "\n📋 闲鱼订单：" + bridge.getExternalOrderId()
                + "\n" + String.join("\n────────────\n", details)
                + "\n⏰ 完成时间：" + completedAt
                + "\n请核对处理结果，如有问题请发送“人工”。";
    }

    private static String normalizeCallbackStatus(String status) {
        if ("完成".equals(status) || "成功".equals(status) || "COMPLETED".equalsIgnoreCase(status)) return "完成";
        if ("失败".equals(status) || "FAILED".equalsIgnoreCase(status)) return "失败";
        if ("处理中".equals(status) || "PROCESSING".equalsIgnoreCase(status)) return "处理中";
        return null;
    }

    private static boolean isPrivateHost(String host) {
        String value = host == null ? "" : host.toLowerCase(Locale.ROOT);
        return value.equals("localhost") || value.equals("host.docker.internal") || value.equals("127.0.0.1")
                || value.equals("::1") || value.startsWith("10.") || value.startsWith("192.168.")
                || value.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*");
    }

    private static String hmacSha256(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 初始化失败", e);
        }
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static Long parseLong(String value) {
        try { return Long.valueOf(value); } catch (Exception ignored) { return null; }
    }
    private static String normalizeUserId(String value) { return trim(value).replace("@goofish", ""); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String trim(String value) { return value == null ? "" : value.trim(); }
    private static String abbreviate(String value, int max) {
        String text = trim(value);
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
