package com.yuzhanggui.service;

import com.yuzhanggui.controller.dto.SyncProgressRespDTO;
import com.yuzhanggui.controller.dto.ItemDTO;
import com.yuzhanggui.controller.dto.SyncSingleItemRespDTO;
import java.util.List;

public interface ItemDetailSyncService {
    String startSync(Long accountId, List<ItemDTO> items);

    /**
     * Runs through Spring's async proxy. It must be exposed on this interface
     * because the default async proxy is a JDK proxy.
     */
    void executeSync(String syncId, Long accountId, List<ItemDTO> items, String cookieStr);

    SyncProgressRespDTO getProgress(String syncId);
    void cancelSync(String syncId);
    boolean isSyncing(Long accountId);
    SyncSingleItemRespDTO syncSingleItem(Long accountId, String itemId);
}
