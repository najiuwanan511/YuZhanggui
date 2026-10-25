package com.yuzhanggui.controller;

import com.yuzhanggui.common.ResultObject;
import com.yuzhanggui.controller.dto.NotificationChannelReqDTO;
import com.yuzhanggui.entity.SysNotificationChannel;
import com.yuzhanggui.service.NotificationChannelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/notification")
public class NotificationChannelController {

    @Autowired
    private NotificationChannelService notificationService;

    @GetMapping("/list")
    public ResultObject<List<SysNotificationChannel>> list() {
        return ResultObject.success(notificationService.list());
    }

    @PostMapping("/save")
    public ResultObject<Boolean> save(@RequestBody NotificationChannelReqDTO req) {
        SysNotificationChannel entity = new SysNotificationChannel();
        entity.setId(req.getId());
        entity.setType(req.getType());
        entity.setName(req.getName());
        entity.setConfig(req.getConfig());
        entity.setStatus(req.getStatus());
        return ResultObject.success(notificationService.saveOrUpdate(entity));
    }

    @DeleteMapping("/delete/{id}")
    public ResultObject<Boolean> delete(@PathVariable Long id) {
        return ResultObject.success(notificationService.removeById(id));
    }

    @PostMapping("/test")
    public ResultObject<String> test(@RequestBody NotificationChannelReqDTO req) {
        try {
            notificationService.sendTestMessage(req.getType(), req.getConfig());
            return ResultObject.success("测试请求已发送");
        } catch (Exception e) {
            return ResultObject.failed("测试发送失败: " + e.getMessage());
        }
    }
}
