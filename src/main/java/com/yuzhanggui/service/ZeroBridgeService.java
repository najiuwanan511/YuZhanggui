package com.yuzhanggui.service;

import com.yuzhanggui.entity.XianyuGoodsAutoDeliveryConfig;
import com.yuzhanggui.entity.XianyuGoodsOrder;
import com.yuzhanggui.event.chatMessageEvent.ChatMessageData;

public interface ZeroBridgeService {
    boolean isEnabled();
    boolean hasActiveSession(Long accountId, String sid, String buyerId);
    void initializeCollection(XianyuGoodsOrder order, XianyuGoodsAutoDeliveryConfig config, int buyNum);
    boolean collectBuyerMessage(ChatMessageData message);
    boolean testConnection();
    CallbackResult acceptCallback(String rawBody, String eventId, String timestamp, String signature);

    record CallbackResult(int httpStatus, String message) {}
}
