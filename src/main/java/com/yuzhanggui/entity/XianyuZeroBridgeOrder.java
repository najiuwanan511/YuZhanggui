package com.yuzhanggui.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("xianyu_zero_bridge_order")
public class XianyuZeroBridgeOrder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long goodsOrderId;
    private Long xianyuAccountId;
    private String externalOrderId;
    private String xyGoodsId;
    private String skuId;
    private String buyerUserId;
    private String buyerUserName;
    private String sid;
    private Integer expectedCount;
    private Integer collectedCount;
    private String status;
    private String zeroResponse;
    private String resultSummary;
    private Integer submitAttempts;
    private LocalDateTime nextSubmitTime;
    private Integer replyAttempts;
    private LocalDateTime nextReplyTime;
    private String lastError;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
