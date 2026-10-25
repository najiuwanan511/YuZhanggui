package com.yuzhanggui.service;

import com.yuzhanggui.entity.XianyuGoodsSkuProperty;

import java.util.List;

public interface GoodsSkuPropertyService {

    List<XianyuGoodsSkuProperty> listByAccountIdAndXyGoodsId(Long xianyuAccountId, String xyGoodsId);

    void saveProperties(String xyGoodsId, Long xianyuAccountId, List<XianyuGoodsSkuProperty> propertyList);

    void deleteByAccountIdAndXyGoodsId(Long xianyuAccountId, String xyGoodsId);
}
