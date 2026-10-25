package com.yuzhanggui.service;

import com.yuzhanggui.controller.dto.BuyerBlacklistReqDTO;
import com.yuzhanggui.entity.XianyuBuyerBlacklist;

import java.util.List;

public interface BuyerBlacklistService {
    boolean isBlacklisted(Long accountId, String buyerUserId);
    XianyuBuyerBlacklist findActive(Long accountId, String buyerUserId);
    List<XianyuBuyerBlacklist> list(Long accountId, String keyword);
    XianyuBuyerBlacklist save(BuyerBlacklistReqDTO request);
    void delete(Long id);
    String blockedMessage(Long accountId, String buyerUserId);
}
