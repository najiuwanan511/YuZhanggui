package com.yuzhanggui.service;

import com.yuzhanggui.common.ResultObject;
import com.yuzhanggui.controller.dto.AutoDeliveryConfigReqDTO;
import com.yuzhanggui.controller.dto.AutoDeliveryConfigRespDTO;
import com.yuzhanggui.controller.dto.AutoDeliveryConfigQueryReqDTO;

import java.util.List;

public interface AutoDeliveryConfigService {
    
    ResultObject<AutoDeliveryConfigRespDTO> saveOrUpdateConfig(AutoDeliveryConfigReqDTO reqDTO);
    
    ResultObject<AutoDeliveryConfigRespDTO> getConfig(AutoDeliveryConfigQueryReqDTO reqDTO);
    
    ResultObject<List<AutoDeliveryConfigRespDTO>> getConfigsByGoodsId(Long xianyuAccountId, String xyGoodsId);
    
    ResultObject<List<AutoDeliveryConfigRespDTO>> getConfigsByAccountId(Long xianyuAccountId);
    
    ResultObject<Void> deleteConfig(Long xianyuAccountId, String xyGoodsId);

    ResultObject<Void> deleteSkuConfig(Long xianyuAccountId, String xyGoodsId, String skuId);
}
