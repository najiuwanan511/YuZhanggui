package com.yuzhanggui.controller.dto;

import com.yuzhanggui.entity.XianyuAccount;
import lombok.Data;

/**
 * 获取账号详情响应DTO
 */
@Data
public class GetAccountDetailRespDTO {
    private XianyuAccount account;       // 账号信息
}