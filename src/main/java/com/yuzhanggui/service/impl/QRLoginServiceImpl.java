package com.yuzhanggui.service.impl;

import com.yuzhanggui.controller.dto.QRLoginResponse;
import com.yuzhanggui.controller.dto.QRLoginSession;
import com.yuzhanggui.controller.dto.QRStatusResponse;
import com.yuzhanggui.service.QRLoginService;
import com.yuzhanggui.service.WebSocketService;
import com.yuzhanggui.utils.CookieUtils;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 二维码登录服务实现
 */
@Service
@Slf4j
public class QRLoginServiceImpl implements QRLoginService {
    
    private final Map<String, QRLoginSession> sessions = new ConcurrentHashMap<>();
    private final OkHttpClient httpClient;
    private final OkHttpClient qrStatusClient;
    private final Gson gson = new Gson();
    
    @Autowired
    private com.yuzhanggui.service.AccountService accountService;

    @Autowired(required = false)
    private WebSocketService webSocketService;

    @Autowired
    @Qualifier("taskExecutor")
    private Executor taskExecutor;
    
    private static final String HOST = "https://passport.goofish.com";
    private static final String API_MINI_LOGIN = HOST + "/mini_login.htm";
    private static final String API_GENERATE_QR = HOST + "/newlogin/qrcode/generate.do";
    private static final String API_SCAN_STATUS = HOST + "/newlogin/qrcode/query.do";
    private static final String API_FACE_CHECK = HOST + "/iv/photoVerify/check.do";
    private static final String API_H5_TK = "https://h5api.m.goofish.com/h5/mtop.gaia.nodejs.gaia.idle.data.gw.v2.index.get/1.0/";
    private static final long QR_SESSION_MAX_WAIT_MILLIS = 900000L;
    
    public QRLoginServiceImpl() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
        // 登录确认时 Passport 可能用 302 携带最终 Cookie。状态请求必须保留
        // 这次原始响应，否则自动跳转后的 HTML 会被当作 JSON 解析。
        this.qrStatusClient = httpClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }
    
    private Headers generateHeaders() {
        // 注意：不要手动设置Accept-Encoding，让OkHttp自动处理gzip
        return new Headers.Builder()
                .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36")
                .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .add("Upgrade-Insecure-Requests", "1")
                .build();
    }
    
    private Headers generateApiHeaders() {
        // 注意：不要手动设置Accept-Encoding，让OkHttp自动处理gzip
        return new Headers.Builder()
                .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36")
                .add("Accept", "application/json, text/plain, */*")
                .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .add("Referer", "https://passport.goofish.com/")
                .add("Origin", "https://passport.goofish.com")
                .build();
    }

    
    /**
     * 获取_m_h5_tk token
     * 这个token是闲鱼API调用必需的，用于签名验证
     */
    private void getMh5tk(QRLoginSession session) throws IOException {
        log.info("开始获取_m_h5_tk token...");
        
        Map<String, Object> data = new HashMap<>();
        data.put("bizScene", "home");
        String dataStr = gson.toJson(data);
        long t = System.currentTimeMillis();
        String appKey = "34839810";
        
        // 第一次请求获取cookie
        Request request = new Request.Builder()
                .url(API_H5_TK)
                .headers(generateApiHeaders())
                .get()
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            mergeResponseCookies(session, response);
            if (response.isSuccessful()) {
                // 获取 _m_h5_tk（注意下划线前缀）
                String mh5tk = session.getCookies().get("_m_h5_tk");
                String token = "";
                if (mh5tk != null && mh5tk.contains("_")) {
                    token = mh5tk.split("_")[0];
                    log.info("已提取到_m_h5_tk token（值已隐藏）");
                } else {
                    log.warn("未找到_m_h5_tk，当前cookies: {}", session.getCookies().keySet());
                }
                
                // 生成签名
                String signInput = token + "&" + t + "&" + appKey + "&" + dataStr;
                String sign = md5(signInput);
                
                // 构造请求参数
                HttpUrl url = HttpUrl.parse(API_H5_TK).newBuilder()
                        .addQueryParameter("jsv", "2.7.2")
                        .addQueryParameter("appKey", appKey)
                        .addQueryParameter("t", String.valueOf(t))
                        .addQueryParameter("sign", sign)
                        .addQueryParameter("v", "1.0")
                        .addQueryParameter("type", "originaljson")
                        .addQueryParameter("dataType", "json")
                        .addQueryParameter("timeout", "20000")
                        .addQueryParameter("api", "mtop.gaia.nodejs.gaia.idle.data.gw.v2.index.get")
                        .addQueryParameter("data", dataStr)
                        .build();
                
                // 第二次请求，刷新token
                Request request2 = new Request.Builder()
                        .url(url)
                        .headers(generateApiHeaders())
                        .header("Cookie", CookieUtils.formatCookies(session.getCookies()))
                        .post(RequestBody.create(new byte[0]))
                        .build();
                
                try (Response response2 = httpClient.newCall(request2).execute()) {
                    mergeResponseCookies(session, response2);
                    if (response2.isSuccessful()) {
                        log.info("_m_h5_tk获取成功: sessionId={}, cookies包含: {}", 
                                session.getSessionId(), session.getCookies().keySet());
                    } else {
                        log.warn("第二次请求失败，状态码: {}", response2.code());
                    }
                }
            } else {
                log.error("获取_m_h5_tk失败，状态码: {}", response.code());
            }
        }
    }
    
    /**
     * 获取登录参数
     */
    private Map<String, String> getLoginParams(QRLoginSession session) throws IOException {
        HttpUrl url = HttpUrl.parse(API_MINI_LOGIN).newBuilder()
                .addQueryParameter("lang", "zh_cn")
                .addQueryParameter("appName", "xianyu")
                .addQueryParameter("appEntrance", "web")
                .addQueryParameter("styleType", "vertical")
                .addQueryParameter("bizParams", "")
                .addQueryParameter("notLoadSsoView", "false")
                .addQueryParameter("notKeepLogin", "false")
                .addQueryParameter("isMobile", "false")
                .addQueryParameter("qrCodeFirst", "false")
                .addQueryParameter("stie", "77")
                .addQueryParameter("rnd", String.valueOf(Math.random()))
                .build();
        
        Request request = new Request.Builder()
                .url(url)
                .headers(generateHeaders())
                .header("Cookie", CookieUtils.formatCookies(session.getCookies()))
                .get()
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            mergeResponseCookies(session, response);
            if (response.isSuccessful()) {
                String html = response.body().string();
                log.debug("获取登录页面HTML长度: {}", html.length());
                
                // 正则匹配需要的json数据
                Pattern pattern = Pattern.compile("window\\.viewData\\s*=\\s*(\\{.*?\\});", Pattern.DOTALL);
                Matcher matcher = pattern.matcher(html);
                
                if (matcher.find()) {
                    String jsonString = matcher.group(1);
                    log.debug("已提取登录viewData，长度: {}（内容不写入日志）", jsonString.length());
                    
                    JsonObject viewData = gson.fromJson(jsonString, JsonObject.class);
                    JsonObject loginFormData = viewData.getAsJsonObject("loginFormData");
                    
                    if (loginFormData != null) {
                        Map<String, String> params = new HashMap<>();
                        loginFormData.entrySet().forEach(entry -> {
                            if (entry.getValue().isJsonPrimitive()) {
                                params.put(entry.getKey(), entry.getValue().getAsString());
                            } else {
                                params.put(entry.getKey(), entry.getValue().toString());
                            }
                        });
                        params.put("umidTag", "SERVER");
                        session.getParams().putAll(params);
                        log.info("获取登录参数成功: {}, 参数数量: {}", session.getSessionId(), params.size());
                        return params;
                    } else {
                        log.error("viewData中没有loginFormData字段，viewData keys: {}", viewData.keySet());
                    }
                } else {
                    log.error("未匹配到window.viewData，尝试查找其他模式");
                    // 尝试其他可能的模式
                    Pattern pattern2 = Pattern.compile("var\\s+viewData\\s*=\\s*(\\{.*?\\});", Pattern.DOTALL);
                    Matcher matcher2 = pattern2.matcher(html);
                    if (matcher2.find()) {
                        String jsonString = matcher2.group(1);
                        log.debug("使用备用模式提取到viewData");
                        JsonObject viewData = gson.fromJson(jsonString, JsonObject.class);
                        JsonObject loginFormData = viewData.getAsJsonObject("loginFormData");
                        if (loginFormData != null) {
                            Map<String, String> params = new HashMap<>();
                            loginFormData.entrySet().forEach(entry -> {
                                if (entry.getValue().isJsonPrimitive()) {
                                    params.put(entry.getKey(), entry.getValue().getAsString());
                                } else {
                                    params.put(entry.getKey(), entry.getValue().toString());
                                }
                            });
                            params.put("umidTag", "SERVER");
                            session.getParams().putAll(params);
                            log.info("获取登录参数成功(备用模式): {}", session.getSessionId());
                            return params;
                        }
                    }
                }
                
                // 如果都失败了，保存HTML用于调试
                log.error("无法提取登录参数，页面长度: {}（页面内容不写入日志）", html.length());
                
                // 尝试直接查找所有可能的参数
                Map<String, String> params = extractParamsFromHtml(html);
                if (!params.isEmpty()) {
                    params.put("umidTag", "SERVER");
                    session.getParams().putAll(params);
                    log.info("使用备用方法提取到参数: {}", params.keySet());
                    return params;
                }
                
                throw new RuntimeException("未找到loginFormData");
            }
            throw new RuntimeException("获取登录参数失败，HTTP状态码: " + response.code());
        }
    }

    
    @Override
    public QRLoginResponse generateQRCode() {
        try {
            // 创建新会话
            String sessionId = UUID.randomUUID().toString();
            QRLoginSession session = new QRLoginSession(sessionId);
            
            // 1. 获取m_h5_tk
            getMh5tk(session);
            
            // 2. 获取登录参数
            Map<String, String> loginParams = getLoginParams(session);
            
            // 3. 生成二维码
            HttpUrl.Builder urlBuilder = HttpUrl.parse(API_GENERATE_QR).newBuilder();
            loginParams.forEach(urlBuilder::addQueryParameter);
            
            Request request = new Request.Builder()
                    .url(urlBuilder.build())
                    .headers(generateApiHeaders())
                    .header("Cookie", CookieUtils.formatCookies(session.getCookies()))
                    .get()
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                mergeResponseCookies(session, response);
                if (response.isSuccessful()) {
                    String responseBody = response.body().string();
                    log.debug("二维码接口已返回响应，长度: {}（内容不写入日志）", responseBody.length());
                    
                    JsonObject results = gson.fromJson(responseBody, JsonObject.class);
                    JsonObject content = results.getAsJsonObject("content");
                    
                    if (content != null && content.get("success").getAsBoolean()) {
                        JsonObject data = content.getAsJsonObject("data");
                        
                        // 更新会话参数
                        session.getParams().put("t", data.get("t").getAsString());
                        session.getParams().put("ck", data.get("ck").getAsString());
                        
                        // 获取二维码内容
                        String qrContent = data.get("codeContent").getAsString();
                        session.setQrContent(qrContent);
                        
                        // 生成二维码图片（base64格式）
                        String qrDataUrl = generateQRCodeImage(qrContent);
                        session.setQrCodeUrl(qrDataUrl);
                        session.setStatus("waiting");
                        
                        // 保存会话
                        sessions.put(sessionId, session);
                        
                        // 启动状态监控
                        taskExecutor.execute(() -> monitorQRStatus(sessionId));
                        
                        log.info("二维码生成成功: {}", sessionId);
                        return new QRLoginResponse(true, sessionId, qrDataUrl, null);
                    } else {
                        return new QRLoginResponse(false, "获取登录二维码失败");
                    }
                }
            }
            
            return new QRLoginResponse(false, "生成二维码失败");
            
        } catch (Exception e) {
            log.error("二维码生成过程中发生异常", e);
            return new QRLoginResponse(false, "生成二维码失败: " + e.getMessage());
        }
    }
    
    /**
     * 从HTML中提取参数（备用方法）
     */
    private Map<String, String> extractParamsFromHtml(String html) {
        Map<String, String> params = new HashMap<>();
        
        // 尝试提取常见的参数
        String[] paramNames = {"appName", "appEntrance", "hsiz", "rnd", "bizParams", 
                               "isMobile", "lang", "returnUrl", "fromSite", "umidToken"};
        
        for (String paramName : paramNames) {
            Pattern pattern = Pattern.compile("\"" + paramName + "\"\\s*:\\s*\"([^\"]+)\"");
            Matcher matcher = pattern.matcher(html);
            if (matcher.find()) {
                params.put(paramName, matcher.group(1));
                log.debug("提取到参数 {}: {}", paramName, matcher.group(1));
            }
        }
        
        return params;
    }
    
    /**
     * 生成二维码图片（Base64格式）
     */
    private String generateQRCodeImage(String content) throws WriterException, IOException {
        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 2);
        
        BitMatrix bitMatrix = qrCodeWriter.encode(content, BarcodeFormat.QR_CODE, 300, 300, hints);
        BufferedImage qrImage = MatrixToImageWriter.toBufferedImage(bitMatrix);
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(qrImage, "PNG", baos);
        byte[] imageBytes = baos.toByteArray();
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        
        return "data:image/png;base64," + base64;
    }

    
    /**
     * 监控二维码状态
     */
    private void monitorQRStatus(String sessionId) {
        try {
            QRLoginSession session = sessions.get(sessionId);
            if (session == null) {
                return;
            }
            
            log.info("开始监控二维码状态: {}", sessionId);
            
            while (!session.isExpired()) {
                try {
                    // 检查会话是否还存在
                    if (!sessions.containsKey(sessionId)) {
                        break;
                    }
                    
                    // 轮询二维码状态
                    String qrCodeStatus = pollQRCodeStatus(session);
                    
                    if ("CONFIRMED".equals(qrCodeStatus) && "success".equals(session.getStatus())) {
                        // 登录确认
                        log.info("扫码登录成功: {}, UNB: {}", sessionId, session.getUnb());
                        break;
                    } else if ("VERIFICATION_REQUIRED".equals(qrCodeStatus)) {
                        if (completeFaceVerification(session)) {
                            log.info("人脸验证登录成功: {}, UNB: {}", sessionId, session.getUnb());
                        }
                        return;
                    } else if ("NEW".equals(qrCodeStatus)) {
                        // 二维码未被扫描，继续轮询
                    } else if ("EXPIRED".equals(qrCodeStatus)) {
                        if (shouldKeepWaitingAfterQRCodeExpired(session.getStatus())) {
                            // 风控验证会消费一次性二维码，后续返回 EXPIRED 不代表验证会话失效。
                            log.debug("二维码已消费，但安全验证仍在进行，继续等待: {}", sessionId);
                        } else {
                            session.setStatus("expired");
                            log.info("二维码已过期: {}", sessionId);
                            break;
                        }
                    } else if (isScannedStatus(qrCodeStatus)) {
                        // 二维码已被扫描，等待确认
                        if ("waiting".equals(session.getStatus())) {
                            session.setStatus("scanned");
                            log.info("二维码已扫描，等待确认: {}", sessionId);
                        }
                    } else if (isCancelledStatus(qrCodeStatus)) {
                        // 用户取消确认
                        session.setStatus("cancelled");
                        log.info("用户取消登录: {}", sessionId);
                        break;
                    } else {
                        // 平台偶尔会增加中间态，未知值不应直接当成用户取消。
                        log.warn("二维码返回未识别的中间状态，继续等待: sessionId={}, status={}",
                                sessionId, qrCodeStatus);
                    }
                    
                    Thread.sleep(800); // 每0.8秒检查一次
                    
                } catch (Exception e) {
                    log.error("监控二维码状态异常", e);
                    Thread.sleep(2000);
                }
            }
            
            // 超时处理
            if (session != null && !isTerminalSessionStatus(session.getStatus())) {
                session.setStatus("expired");
                log.info("二维码监控超时，标记为过期: {}", sessionId);
            }
            
        } catch (Exception e) {
            log.error("监控二维码状态失败", e);
            QRLoginSession session = sessions.get(sessionId);
            if (session != null) {
                session.setStatus("expired");
            }
        }
    }
    
    /**
     * 轮询二维码状态
     */
    private String pollQRCodeStatus(QRLoginSession session) throws IOException {
        FormBody.Builder formBuilder = new FormBody.Builder();
        session.getParams().forEach(formBuilder::add);
        
        Request request = new Request.Builder()
                .url(API_SCAN_STATUS)
                .headers(generateApiHeaders())
                .header("Cookie", CookieUtils.formatCookies(session.getCookies()))
                .post(formBuilder.build())
                .build();
        
        try (Response response = qrStatusClient.newCall(request).execute()) {
            mergeResponseCookies(session, response);
            if (response.isRedirect()) {
                if (hasAuthenticatedAccount(session)) {
                    completeLogin(session);
                    return "CONFIRMED";
                }
                log.warn("二维码状态请求发生跳转但尚未获得账号 Cookie: sessionId={}, location={}",
                        session.getSessionId(), response.header("Location"));
                return session.getStatus().equals("scanned") ? "SCANED" : "NEW";
            }
            if (response.isSuccessful()) {
                String responseBody = response.body().string();
                JsonObject results = gson.fromJson(responseBody, JsonObject.class);
                JsonObject content = results.getAsJsonObject("content");
                
                if (content != null) {
                    JsonObject data = content.getAsJsonObject("data");
                    if (data != null) {
                        updateSessionParams(session, data);
                        String qrCodeStatus = readQRCodeStatus(data);
                        
                        if ("CONFIRMED".equals(qrCodeStatus)) {
                            // 检查是否需要风控验证
                            if (data.has("iframeRedirect") && data.get("iframeRedirect").getAsBoolean()) {
                                String iframeUrl = data.has("iframeRedirectUrl") && !data.get("iframeRedirectUrl").isJsonNull()
                                        ? data.get("iframeRedirectUrl").getAsString() : null;
                                markVerificationRequired(session, iframeUrl);
                                return "VERIFICATION_REQUIRED";
                            } else {
                                completeLogin(session);
                            }
                        }
                        
                        return qrCodeStatus;
                    }
                }
            }
        }
        
        return "NEW";
    }

    private void markVerificationRequired(QRLoginSession session, String iframeUrl) {
        boolean firstNotification = !"verification_required".equals(session.getStatus())
                || !Objects.equals(session.getVerificationUrl(), iframeUrl);
        session.setVerificationUrl(iframeUrl);
        if (firstNotification) {
            session.setCreatedTime(System.currentTimeMillis());
            session.setExpireTime(QR_SESSION_MAX_WAIT_MILLIS);
            session.setVerificationQrCodeUrl(null);
            session.setVerificationMessage("正在准备人脸验证二维码...");
            log.warn("账号被风控，开始准备人脸验证二维码");
            log.warn("   - 会话ID: {}", session.getSessionId());
            log.warn("   - 已获取平台安全验证地址");
        }
        // Volatile 状态最后发布，确保状态查询线程先看到完整的验证上下文。
        session.setStatus("verification_required");
    }

    private boolean completeFaceVerification(QRLoginSession session) {
        String iframeUrl = session.getVerificationUrl();
        if (iframeUrl == null || iframeUrl.isBlank()) {
            session.setVerificationMessage("平台未返回安全验证地址，请改用 Cookie 更新");
            return false;
        }

        try {
            FollowedResponse normalPage = followVerificationRedirects(session, iframeUrl, generateHeaders(), 8);
            String htoken = extractFaceToken(normalPage.body(), normalPage.url().toString());
            String verifyModesUrl = extractVerificationModesUrl(normalPage.body(), normalPage.url());
            if (htoken == null || verifyModesUrl == null) {
                throw new IOException("未能从安全验证页面提取验证参数");
            }

            FollowedResponse identityPage = followVerificationRedirects(session, verifyModesUrl, generateHeaders(), 8);
            String faceQrContent = extractFaceQRCodeContent(identityPage.body());
            if (faceQrContent == null) {
                throw new IOException("未能从安全验证页面提取人脸二维码");
            }

            session.setVerificationQrCodeUrl(generateQRCodeImage(faceQrContent));
            session.setVerificationMessage("请使用闲鱼扫描人脸验证二维码，完成后系统会自动继续登录");
            log.info("人脸验证二维码已生成: {}", session.getSessionId());

            Headers faceHeaders = generateFaceVerificationHeaders(identityPage.url());
            String completionUrl = waitForFaceVerification(session, htoken, identityPage.url(), faceHeaders);
            if (completionUrl == null) {
                if (sessions.containsKey(session.getSessionId()) && session.isExpired()) {
                    session.setStatus("expired");
                    session.setVerificationMessage("人脸验证已超时，请重新生成二维码");
                }
                return false;
            }

            session.setVerificationMessage("人脸验证已通过，正在完成登录...");
            followVerificationRedirects(session, completionUrl, faceHeaders, 10);
            if (!hasAuthenticatedAccount(session)) {
                throw new IOException("人脸验证已通过，但未获得账号登录信息");
            }
            completeLogin(session);
            return "success".equals(session.getStatus());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("人脸验证登录处理被中断: sessionId={}", session.getSessionId());
            return false;
        } catch (Exception e) {
            log.error("人脸验证登录处理失败: sessionId={}", session.getSessionId(), e);
            if (sessions.containsKey(session.getSessionId())) {
                session.setVerificationMessage("自动完成人脸验证失败，请打开安全验证页面或改用 Cookie 更新");
            }
            return false;
        }
    }

    private String waitForFaceVerification(QRLoginSession session, String htoken, HttpUrl identityUrl,
                                           Headers headers)
            throws IOException, InterruptedException {
        HttpUrl checkUrl = Objects.requireNonNull(HttpUrl.parse(API_FACE_CHECK)).newBuilder()
                .addQueryParameter("htoken", htoken)
                .build();
        while (!session.isExpired() && sessions.containsKey(session.getSessionId())) {
            Request request = new Request.Builder()
                    .url(checkUrl)
                    .headers(headers)
                    .header("Cookie", CookieUtils.formatCookies(session.getCookies()))
                    .get()
                    .build();
            try (Response response = qrStatusClient.newCall(request).execute()) {
                mergeResponseCookies(session, response);
                if (response.isSuccessful() && response.body() != null) {
                    FaceCheckResult result = readFaceCheckResult(gson.fromJson(response.body().string(), JsonObject.class));
                    if ("3".equals(result.code()) && result.completionUrl() != null) {
                        String completionUrl = parseTrustedVerificationUrl(
                                result.completionUrl(), identityUrl).toString();
                        log.info("人脸验证已通过: {}", session.getSessionId());
                        return completionUrl;
                    }
                    if (!"0".equals(result.code()) && !result.code().isBlank()) {
                        log.warn("人脸验证返回中间状态: sessionId={}, code={}",
                                session.getSessionId(), result.code());
                    }
                }
            } catch (Exception e) {
                log.warn("人脸验证状态查询失败，将继续重试: sessionId={}, error={}",
                        session.getSessionId(), e.getMessage());
            }
            Thread.sleep(2000);
        }
        return null;
    }

    private Headers generateFaceVerificationHeaders(HttpUrl identityUrl) {
        return generateApiHeaders().newBuilder()
                .set("Accept", "application/json, text/javascript, */*; q=0.01")
                .set("X-Requested-With", "XMLHttpRequest")
                .set("Referer", identityUrl.toString())
                .build();
    }

    FollowedResponse followVerificationRedirects(QRLoginSession session, String rawUrl, Headers headers,
                                                   int maxRedirects) throws IOException {
        HttpUrl currentUrl = parseTrustedVerificationUrl(rawUrl, null);
        for (int redirect = 0; redirect <= maxRedirects; redirect++) {
            Request request = new Request.Builder()
                    .url(currentUrl)
                    .headers(headers)
                    .header("Cookie", CookieUtils.formatCookies(session.getCookies()))
                    .get()
                    .build();
            try (Response response = qrStatusClient.newCall(request).execute()) {
                mergeResponseCookies(session, response);
                if (response.isRedirect()) {
                    String location = response.header("Location");
                    if (location == null || location.isBlank()) {
                        throw new IOException("安全验证跳转缺少 Location");
                    }
                    currentUrl = parseTrustedVerificationUrl(location, currentUrl);
                    continue;
                }
                String body = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    throw new IOException("安全验证页面请求失败，HTTP " + response.code());
                }
                return new FollowedResponse(currentUrl, body, response.code());
            }
        }
        throw new IOException("安全验证页面跳转次数过多");
    }

    private static HttpUrl parseTrustedVerificationUrl(String rawUrl, HttpUrl baseUrl) throws IOException {
        HttpUrl url = baseUrl == null ? HttpUrl.parse(rawUrl) : baseUrl.resolve(rawUrl);
        if (url == null || !"https".equalsIgnoreCase(url.scheme()) || !isTrustedVerificationHost(url.host())) {
            throw new IOException("安全验证返回了不受信任的跳转地址");
        }
        return url;
    }

    static boolean isTrustedVerificationHost(String host) {
        String normalized = host == null ? "" : host.toLowerCase(Locale.ROOT);
        return normalized.equals("goofish.com") || normalized.endsWith(".goofish.com")
                || normalized.equals("taobao.com") || normalized.endsWith(".taobao.com");
    }

    static String extractFaceToken(String html, String fallbackUrl) {
        Matcher matcher = Pattern.compile("htoken=([A-Za-z0-9_-]+)").matcher(
                String.valueOf(html) + " " + String.valueOf(fallbackUrl));
        return matcher.find() ? matcher.group(1) : null;
    }

    static String extractVerificationModesUrl(String html, HttpUrl baseUrl) {
        if (html == null || baseUrl == null) return null;
        Matcher matcher = Pattern.compile(
                "[\\\"']((?:https://[^\\\"']+)?/iv/mini/verify_modes\\.htm\\?[^\\\"']*)[\\\"']")
                .matcher(decodePageValue(html));
        if (!matcher.find()) return null;
        String rawUrl = decodePageValue(matcher.group(1));
        if (rawUrl.endsWith("_umidfg=")) rawUrl += "1";
        HttpUrl resolved = baseUrl.resolve(rawUrl);
        return resolved == null ? null : resolved.toString();
    }

    static String extractFaceQRCodeContent(String html) {
        if (html == null) return null;
        Matcher matcher = Pattern.compile(
                "new\\s+Qrcode\\s*\\(\\s*\\{\\s*text\\s*:\\s*[\\\"']([^\\\"']+)[\\\"']",
                Pattern.CASE_INSENSITIVE).matcher(html);
        return matcher.find() ? decodePageValue(matcher.group(1)) : null;
    }

    private static String decodePageValue(String value) {
        return value.replace("&amp;", "&")
                .replace("&#38;", "&")
                .replace("\\u0026", "&")
                .replace("\\/", "/");
    }

    static FaceCheckResult readFaceCheckResult(JsonObject response) {
        if (response == null || !response.has("content") || !response.get("content").isJsonObject()) {
            return new FaceCheckResult("", null);
        }
        JsonObject content = response.getAsJsonObject("content");
        String code = content.has("code") && !content.get("code").isJsonNull()
                ? content.get("code").getAsString() : "";
        String url = content.has("url") && !content.get("url").isJsonNull()
                ? content.get("url").getAsString() : null;
        return new FaceCheckResult(code, url);
    }

    record FollowedResponse(HttpUrl url, String body, int code) {}

    record FaceCheckResult(String code, String completionUrl) {}

    private void completeLogin(QRLoginSession session) {
        log.info("扫码确认成功，开始保存账号信息: sessionId={}", session.getSessionId());
        if (!hasAuthenticatedAccount(session)) {
            session.setStatus("error");
            log.error("扫码确认后未获得账号标识 Cookie: sessionId={}, cookieFields={}",
                    session.getSessionId(), session.getCookies().keySet());
            return;
        }
        session.setStatus("success");
        saveCookieToDatabase(session);
    }

    private boolean hasAuthenticatedAccount(QRLoginSession session) {
        String unb = session.getUnb();
        return unb != null && !unb.isBlank();
    }

    private void mergeResponseCookies(QRLoginSession session, Response response) {
        for (Response current = response; current != null; current = current.priorResponse()) {
            mergeSetCookieHeaders(session, current.headers("Set-Cookie"));
        }
    }

    static void mergeSetCookieHeaders(QRLoginSession session, List<String> setCookieHeaders) {
        if (session == null || setCookieHeaders == null) return;
        for (String cookie : setCookieHeaders) {
            if (cookie == null || cookie.isBlank()) continue;
            String[] parts = cookie.split(";", 2)[0].split("=", 2);
            if (parts.length != 2 || parts[0].isBlank()) continue;
            session.getCookies().put(parts[0].trim(), parts[1]);
            if ("unb".equalsIgnoreCase(parts[0].trim()) && !parts[1].isBlank()) {
                session.setUnb(parts[1]);
            }
        }
    }

    private void updateSessionParams(QRLoginSession session, JsonObject data) {
        for (String key : List.of("t", "ck")) {
            if (data.has(key) && !data.get(key).isJsonNull()) {
                session.getParams().put(key, data.get(key).getAsString());
            }
        }
        for (String key : List.of("unb", "userId", "userid")) {
            if (data.has(key) && !data.get(key).isJsonNull()) {
                String accountId = data.get(key).getAsString();
                if (!accountId.isBlank()) {
                    session.setUnb(accountId);
                    session.getCookies().put("unb", accountId);
                    break;
                }
            }
        }
    }

    static String readQRCodeStatus(JsonObject data) {
        if (data == null) return "NEW";
        for (String key : List.of("qrCodeStatus", "qrcodeStatus", "status")) {
            if (data.has(key) && !data.get(key).isJsonNull()) {
                return data.get(key).getAsString().trim().toUpperCase(Locale.ROOT);
            }
        }
        return "NEW";
    }

    static boolean isScannedStatus(String status) {
        return "SCANED".equals(status) || "SCANNED".equals(status);
    }

    static boolean isCancelledStatus(String status) {
        return "CANCELED".equals(status) || "CANCELLED".equals(status) || "DENIED".equals(status);
    }

    static boolean isTerminalSessionStatus(String status) {
        return "success".equals(status)
                || "expired".equals(status)
                || "cancelled".equals(status)
                || "error".equals(status);
    }

    static boolean shouldKeepWaitingAfterQRCodeExpired(String sessionStatus) {
        return "verification_required".equals(sessionStatus);
    }

    
    @Override
    public QRStatusResponse getSessionStatus(String sessionId) {
        QRStatusResponse response = new QRStatusResponse();
        QRLoginSession session = sessions.get(sessionId);
        
        if (session == null) {
            response.setStatus("not_found");
            response.setMessage("会话不存在或已过期");
            return response;
        }
        
        if (session.isExpired() && !"success".equals(session.getStatus())) {
            session.setStatus("expired");
        }
        
        // 转换后端状态为前端期望的状态
        String frontendStatus = convertToFrontendStatus(session.getStatus());
        response.setStatus(frontendStatus);
        response.setSessionId(sessionId);
        
        // 根据状态设置详细的消息
        switch (session.getStatus()) {
            case "waiting":
                response.setMessage("等待扫码...");
                break;
            case "scanned":
                response.setMessage("已扫码，等待确认...");
                break;
            case "success":
                response.setMessage("登录成功！账号已添加");
                // 如果登录成功，返回Cookie信息
                if (!session.getCookies().isEmpty() && session.getUnb() != null) {
                    response.setCookies(CookieUtils.formatCookies(session.getCookies()));
                    response.setUnb(session.getUnb());
                }
                break;
            case "expired":
                response.setMessage("二维码已过期，请重新生成");
                break;
            case "cancelled":
                response.setMessage("用户取消登录");
                break;
            case "verification_required":
                response.setMessage(session.getVerificationMessage() == null
                        ? "正在准备人脸验证二维码..." : session.getVerificationMessage());
                response.setQrCodeUrl(session.getVerificationQrCodeUrl());
                if (session.getVerificationUrl() != null) {
                    response.setVerificationUrl(session.getVerificationUrl());
                }
                break;
            case "error":
                response.setMessage("扫码已确认，但登录信息获取失败，请重新扫码");
                break;
            default:
                response.setMessage("未知状态");
                break;
        }
        
        return response;
    }
    
    /**
     * 转换后端状态为前端期望的状态
     * 后端: waiting, scanned, success, expired, cancelled, verification_required
     * 前端: pending, scanned, confirmed, expired
     */
    private String convertToFrontendStatus(String backendStatus) {
        switch (backendStatus) {
            case "waiting":
                return "pending";
            case "success":
                return "confirmed";
            case "scanned":
            case "expired":
            case "cancelled":
            case "verification_required":
            case "error":
                return backendStatus;
            default:
                return "pending";
        }
    }
    
    @Override
    public Map<String, String> getSessionCookies(String sessionId) {
        QRLoginSession session = sessions.get(sessionId);
        if (session != null && "success".equals(session.getStatus())) {
            Map<String, String> result = new HashMap<>();
            result.put("cookies", CookieUtils.formatCookies(session.getCookies()));
            result.put("unb", session.getUnb());
            return result;
        }
        return null;
    }

    @Override
    public void cleanupExpiredSessions() {
        List<String> expiredSessions = new ArrayList<>();
        sessions.forEach((sessionId, session) -> {
            if (session.isExpired()) {
                expiredSessions.add(sessionId);
            }
        });
        
        expiredSessions.forEach(sessionId -> {
            sessions.remove(sessionId);
            log.info("清理过期会话: {}", sessionId);
        });
    }
    
    /**
     * 保存Cookie到数据库
     */
    private void saveCookieToDatabase(QRLoginSession session) {
        try {
            String unb = session.getUnb();
            if (unb == null || unb.isEmpty()) {
                log.error("❌ UNB为空，无法保存Cookie: sessionId={}", session.getSessionId());
                session.setStatus("error");
                return;
            }
            
            // 检查关键Cookie字段
            Map<String, String> cookies = session.getCookies();
            log.info("📝 准备保存Cookie到数据库，当前Cookie包含字段: {}", cookies.keySet());
            
            // 提取 _m_h5_tk
            String mH5Tk = cookies.get("_m_h5_tk");
            if (mH5Tk == null || mH5Tk.isEmpty()) {
                log.warn("⚠️ Cookie中缺少_m_h5_tk字段！这可能导致后续API调用失败");
            } else {
                log.info("✅ Cookie中已包含_m_h5_tk（值已隐藏）");
            }
            
            // 格式化Cookie字符串
            String cookieText = CookieUtils.formatCookies(cookies);
            log.info("📦 格式化后的Cookie长度: {}", cookieText.length());
            
            // 使用UNB作为账号备注（可以后续优化为用户自定义）
            String accountNote = "账号_" + unb.substring(0, Math.min(8, unb.length()));
            
            // 保存到数据库（包含 m_h5_tk）
            Long accountId = accountService.saveAccountAndCookie(accountNote, unb, cookieText, mH5Tk);
            
            if (accountId != null && accountId > 0) {
                log.info("✅ 扫码登录成功！Cookie已保存到数据库");
                log.info("   - 会话ID: {}", session.getSessionId());
                log.info("   - 账号ID: {}", accountId);
                log.info("   - UNB: {}", unb);
                log.info("   - Cookie字段数: {}", cookies.size());
                log.info("   - m_h5_tk: {}", mH5Tk != null ? "已保存" : "未提供");
                log.info("   - 账号备注: {}", accountNote);

                // QR 登录也是一次凭证更新。若账号之前因风控处于 -2/待验证状态，
                // 仅保存 Cookie 不会清理 Token 服务的内存验证状态，后续手动连接会被旧状态拦截。
                // 复用统一恢复流程，清理旧状态并自动重建该账号的 WebSocket。
                if (webSocketService != null) {
                    boolean reconnected = webSocketService.restartAfterCredentialUpdate(accountId);
                    if (reconnected) {
                        log.info("【账号{}】扫码凭证已生效，WebSocket已自动恢复", accountId);
                    } else {
                        log.warn("【账号{}】扫码凭证已保存，但WebSocket暂未恢复，请稍后刷新连接状态", accountId);
                    }
                }
            } else {
                log.error("❌ 保存Cookie失败：accountId为空");
                session.setStatus("error");
            }
            
        } catch (Exception e) {
            log.error("❌ 保存Cookie到数据库失败: sessionId={}", session.getSessionId(), e);
            session.setStatus("error");
        }
    }
    
    /**
     * MD5加密
     */
    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes());
            StringBuilder hexString = new StringBuilder();
            
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            
            return hexString.toString();
        } catch (Exception e) {
            log.error("MD5加密失败", e);
            return "";
        }
    }
}
