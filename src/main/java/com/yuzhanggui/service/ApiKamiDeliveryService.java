package com.yuzhanggui.service;

import com.yuzhanggui.controller.dto.KamiApiTestReqDTO;
import com.yuzhanggui.controller.dto.KamiApiTestRespDTO;
import com.yuzhanggui.entity.XianyuKamiConfig;
import com.yuzhanggui.service.delivery.DeliveryContext;

/** 外部卡券供应商接口调用与订单级结果缓存。 */
public interface ApiKamiDeliveryService {

    String acquire(XianyuKamiConfig config, DeliveryContext context);

    KamiApiTestRespDTO test(KamiApiTestReqDTO request);
}
