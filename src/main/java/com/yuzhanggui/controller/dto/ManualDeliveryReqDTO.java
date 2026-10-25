package com.yuzhanggui.controller.dto;

import lombok.Data;

@Data
public class ManualDeliveryReqDTO {
    private Long xianyuAccountId;
    private String orderId;
    private String content;
}
