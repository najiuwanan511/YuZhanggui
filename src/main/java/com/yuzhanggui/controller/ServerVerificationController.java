package com.yuzhanggui.controller;

import com.yuzhanggui.common.ResultObject;
import com.yuzhanggui.service.impl.ServerVerificationService;
import com.yuzhanggui.service.impl.VerificationBrowserFactory;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/websocket/verification")
public class ServerVerificationController {
    private final ServerVerificationService service;
    public ServerVerificationController(ServerVerificationService service) { this.service = service; }
    public record Request(Long accountId, String sessionId, List<VerificationBrowserFactory.Point> points) {}

    @PostMapping("/start")
    public ResultObject<ServerVerificationService.View> start(@RequestBody Request request) {
        return result(() -> service.start(request.accountId()));
    }
    @PostMapping("/state")
    public ResultObject<ServerVerificationService.View> state(@RequestBody Request request) {
        return result(() -> service.state(request.accountId(), request.sessionId()));
    }
    @PostMapping("/input")
    public ResultObject<ServerVerificationService.View> input(@RequestBody Request request) {
        return result(() -> service.input(request.accountId(), request.sessionId(), request.points()));
    }
    @PostMapping("/close")
    public ResultObject<String> close(@RequestBody Request request) {
        return result(() -> { service.close(request.accountId(), request.sessionId()); return "验证会话已关闭"; });
    }
    private <T> ResultObject<T> result(Supplier<T> action) {
        try { return ResultObject.success(action.get()); }
        catch (IllegalArgumentException | IllegalStateException failure) { return ResultObject.failed(failure.getMessage()); }
    }
}
