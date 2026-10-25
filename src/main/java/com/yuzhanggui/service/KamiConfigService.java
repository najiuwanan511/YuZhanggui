package com.yuzhanggui.service;

import com.yuzhanggui.common.ResultObject;
import com.yuzhanggui.controller.dto.*;
import com.yuzhanggui.entity.XianyuKamiConfig;
import com.yuzhanggui.entity.XianyuKamiItem;

import java.util.List;

public interface KamiConfigService {

    ResultObject<KamiConfigRespDTO> createOrUpdateConfig(KamiConfigReqDTO reqDTO);

    ResultObject<List<KamiConfigRespDTO>> getConfigs();

    ResultObject<KamiConfigRespDTO> getConfigById(Long id);

    ResultObject<Void> deleteConfig(Long id);

    ResultObject<KamiItemRespDTO> addKamiItem(KamiItemReqDTO reqDTO);

    ResultObject<Integer> batchImportKamiItems(KamiBatchImportReqDTO reqDTO);

    ResultObject<List<KamiItemRespDTO>> getKamiItemsByConfigId(Long kamiConfigId);

    ResultObject<List<KamiItemRespDTO>> getKamiItemsByConfigIdWithFilter(KamiItemQueryReqDTO reqDTO);

    ResultObject<Void> deleteKamiItem(Long id);

    ResultObject<Void> resetKamiItem(Long id);
    ResultObject<Integer> batchDeleteKamiItems(List<Long> ids);

    ResultObject<Integer> batchResetKamiItems(List<Long> ids);
    ResultObject<Integer> clearUsedKamiItems(Long kamiConfigId);


    XianyuKamiItem acquireKami(Long kamiConfigId, String orderId);

    List<XianyuKamiItem> reserveKami(Long kamiConfigId, String orderId, int quantity);

    void commitReservation(String reservationOrderId, String businessOrderId, Long accountId,
                           String xyGoodsId, String buyerUserId, String buyerUserName);

    void releaseReservation(String orderId);

    void markReservationReviewRequired(String orderId);

    XianyuKamiConfig getConfig(Long kamiConfigId);

    /** 优先返回当前账号专属图片，旧配置则回退到历史共享图片。 */
    String resolveDeliveryImageUrl(XianyuKamiConfig config, Long accountId);

    ResultObject<List<KamiItemRespDTO>> exportKamiItems(KamiExportReqDTO reqDTO);

    ResultObject<KamiApiTestRespDTO> testApiConfig(KamiApiTestReqDTO reqDTO);

    ResultObject<List<KamiRelatedGoodsDTO>> getRelatedGoods(Long kamiConfigId);

    ResultObject<Integer> saveRelatedGoods(KamiRelatedGoodsSaveReqDTO reqDTO);
}
