package com.yuzhanggui.controller;

import com.yuzhanggui.common.ResultObject;
import com.yuzhanggui.service.ZeroBridgeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/integrations/zero")
public class ZeroBridgeController {
    private final ZeroBridgeService zeroBridgeService;

    public ZeroBridgeController(ZeroBridgeService zeroBridgeService) {
        this.zeroBridgeService = zeroBridgeService;
    }

    @PostMapping("/callback")
    public ResponseEntity<Map<String, Object>> callback(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Zero-Event-Id", required = false) String eventId,
            @RequestHeader(value = "X-Zero-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Zero-Signature", required = false) String signature) {
        ZeroBridgeService.CallbackResult result = zeroBridgeService.acceptCallback(rawBody, eventId, timestamp, signature);
        return ResponseEntity.status(result.httpStatus()).body(Map.of(
                "ok", result.httpStatus() >= 200 && result.httpStatus() < 300,
                "message", result.message()));
    }

    @PostMapping("/test")
    public ResultObject<Boolean> test() {
        boolean ok = zeroBridgeService.testConnection();
        return ok ? ResultObject.success(true, "Zero 连接正常") : ResultObject.failed("无法连接 Zero，请检查地址、Token 和 Zero 映射配置");
    }
}
