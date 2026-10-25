package com.yuzhanggui.event.chatMessageEvent.lister;

import com.yuzhanggui.event.chatMessageEvent.ChatMessageData;
import com.yuzhanggui.event.chatMessageEvent.ChatMessageReceivedEvent;
import com.yuzhanggui.service.AccountService;
import com.yuzhanggui.service.ZeroBridgeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ChatMessageEventZeroBridgeListener {
    private final ZeroBridgeService zeroBridgeService;
    private final AccountService accountService;

    public ChatMessageEventZeroBridgeListener(ZeroBridgeService zeroBridgeService, AccountService accountService) {
        this.zeroBridgeService = zeroBridgeService;
        this.accountService = accountService;
    }

    @Order(5)
    @Async
    @EventListener
    public void handle(ChatMessageReceivedEvent event) {
        ChatMessageData message = event.getMessageData();
        if (message == null || message.getContentType() == null || message.getContentType() != 1) return;
        String ownUserId = accountService.getXianyuUserId(message.getXianyuAccountId());
        if (ownUserId != null && ownUserId.equals(message.getSenderUserId())) return;
        try {
            if (zeroBridgeService.collectBuyerMessage(message)) {
                log.info("【账号{}】买家消息已计入 Zero 提交进度: pnmId={}",
                        message.getXianyuAccountId(), message.getPnmId());
            }
        } catch (Exception e) {
            log.error("【账号{}】收集 Zero 下单信息失败: pnmId={}",
                    message.getXianyuAccountId(), message.getPnmId(), e);
        }
    }
}
