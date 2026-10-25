package com.yuzhanggui.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("xianyu_zero_submission")
public class XianyuZeroSubmission {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long bridgeOrderId;
    private String lineId;
    private String pnmId;
    private String content;
    private LocalDateTime createdTime;
}
