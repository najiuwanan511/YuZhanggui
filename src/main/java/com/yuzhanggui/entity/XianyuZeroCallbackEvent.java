package com.yuzhanggui.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("xianyu_zero_callback_event")
public class XianyuZeroCallbackEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
    private Long bridgeOrderId;
    private String lineId;
    private String status;
    private String payloadHash;
    private String payloadJson;
    private LocalDateTime createdTime;
}
