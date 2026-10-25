package com.yuzhanggui.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuzhanggui.common.ResultObject;
import com.yuzhanggui.entity.XianyuGoodsAutoDeliveryConfig;
import com.yuzhanggui.entity.XianyuGoodsSku;
import com.yuzhanggui.entity.XianyuKamiConfig;
import com.yuzhanggui.mapper.XianyuGoodsAutoDeliveryConfigMapper;
import com.yuzhanggui.mapper.XianyuKamiConfigMapper;
import com.yuzhanggui.controller.dto.AutoDeliveryConfigReqDTO;
import com.yuzhanggui.controller.dto.AutoDeliveryConfigRespDTO;
import com.yuzhanggui.controller.dto.AutoDeliveryConfigQueryReqDTO;
import com.yuzhanggui.service.AutoDeliveryConfigService;
import com.yuzhanggui.service.GoodsSkuService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AutoDeliveryConfigServiceImpl implements AutoDeliveryConfigService {
    
    @Autowired
    private XianyuGoodsAutoDeliveryConfigMapper autoDeliveryConfigMapper;

    @Autowired
    private GoodsSkuService goodsSkuService;

    @Autowired
    private XianyuKamiConfigMapper kamiConfigMapper;
    
    @Override
    public ResultObject<AutoDeliveryConfigRespDTO> saveOrUpdateConfig(AutoDeliveryConfigReqDTO reqDTO) {
        try {
            String skuId = normalizeSkuId(reqDTO.getSkuId());
            reqDTO.setSkuId(skuId);
            String validationError = validateSkuDeliveryConfig(reqDTO, skuId);
            if (validationError != null) {
                return ResultObject.failed(validationError);
            }
            XianyuGoodsAutoDeliveryConfig existingConfig = null;
            if (skuId != null && !skuId.isEmpty()) {
                existingConfig = autoDeliveryConfigMapper
                        .findByAccountIdAndGoodsIdAndSkuId(reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), skuId);
            }
            if (existingConfig == null) {
                existingConfig = autoDeliveryConfigMapper
                        .findByAccountIdAndGoodsIdNoSku(reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId());
                if (existingConfig != null && skuId != null && !skuId.isEmpty()) {
                    existingConfig = null;
                }
            }
            
            XianyuGoodsAutoDeliveryConfig config;
            if (existingConfig != null) {
                config = existingConfig;
                config.setDeliveryMode(reqDTO.getDeliveryMode());
                config.setSkuId(reqDTO.getSkuId());
                config.setSkuName(reqDTO.getSkuName());
                config.setAutoDeliveryContent(reqDTO.getAutoDeliveryContent());
                config.setKamiConfigIds(reqDTO.getKamiConfigIds());
                config.setKamiDeliveryTemplate(reqDTO.getKamiDeliveryTemplate());
                config.setAutoDeliveryImageUrl(reqDTO.getAutoDeliveryImageUrl());
                config.setXianyuGoodsId(reqDTO.getXianyuGoodsId());
                if (reqDTO.getAutoConfirmShipment() != null) {
                    config.setAutoConfirmShipment(reqDTO.getAutoConfirmShipment());
                }
                config.setZeroInputCount(normalizeZeroInputCount(reqDTO.getZeroInputCount()));
                
                autoDeliveryConfigMapper.updateById(config);
                log.info("更新自动发货配置成功，ID: {}", config.getId());
            } else {
                config = new XianyuGoodsAutoDeliveryConfig();
                BeanUtils.copyProperties(reqDTO, config);
                if (config.getSkuId() == null) {
                    config.setSkuId(null);
                }
                
                autoDeliveryConfigMapper.insert(config);
                log.info("创建自动发货配置成功，ID: {}", config.getId());
            }
            
            AutoDeliveryConfigRespDTO respDTO = new AutoDeliveryConfigRespDTO();
            BeanUtils.copyProperties(config, respDTO);
            
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("保存自动发货配置失败", e);
            return ResultObject.failed("保存自动发货配置失败: " + e.getMessage());
        }
    }
    
    @Override
    public ResultObject<AutoDeliveryConfigRespDTO> getConfig(AutoDeliveryConfigQueryReqDTO reqDTO) {
        try {
            log.info("开始查询自动发货配置: xianyuAccountId={}, xyGoodsId={}, skuId={}", 
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), reqDTO.getSkuId());
            
            XianyuGoodsAutoDeliveryConfig config = null;
            
            if (reqDTO.getXyGoodsId() != null && !reqDTO.getXyGoodsId().trim().isEmpty()) {
                String skuId = reqDTO.getSkuId();
                if (skuId != null && !skuId.isEmpty()) {
                    config = autoDeliveryConfigMapper.findByAccountIdAndGoodsIdAndSkuId(
                            reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), skuId);
                } else {
                    config = autoDeliveryConfigMapper.findByAccountIdAndGoodsIdNoSku(
                            reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId());
                }
            } else {
                List<XianyuGoodsAutoDeliveryConfig> configs = autoDeliveryConfigMapper
                        .findByAccountId(reqDTO.getXianyuAccountId());
                config = configs.isEmpty() ? null : configs.get(0);
            }
            
            if (config == null) {
                return ResultObject.success(null);
            }
            
            AutoDeliveryConfigRespDTO respDTO = new AutoDeliveryConfigRespDTO();
            BeanUtils.copyProperties(config, respDTO);
            
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("查询自动发货配置失败", e);
            return ResultObject.failed("查询自动发货配置失败: " + e.getMessage());
        }
    }
    
    @Override
    public ResultObject<List<AutoDeliveryConfigRespDTO>> getConfigsByGoodsId(Long xianyuAccountId, String xyGoodsId) {
        try {
            List<XianyuGoodsAutoDeliveryConfig> configs = autoDeliveryConfigMapper
                    .findByAccountIdAndGoodsId(xianyuAccountId, xyGoodsId);
            
            List<AutoDeliveryConfigRespDTO> respDTOs = configs.stream()
                    .map(config -> {
                        AutoDeliveryConfigRespDTO respDTO = new AutoDeliveryConfigRespDTO();
                        BeanUtils.copyProperties(config, respDTO);
                        return respDTO;
                    })
                    .collect(Collectors.toList());
            
            return ResultObject.success(respDTOs);
        } catch (Exception e) {
            log.error("查询商品自动发货配置列表失败", e);
            return ResultObject.failed("查询商品自动发货配置列表失败: " + e.getMessage());
        }
    }
    
    @Override
    public ResultObject<List<AutoDeliveryConfigRespDTO>> getConfigsByAccountId(Long xianyuAccountId) {
        try {
            List<XianyuGoodsAutoDeliveryConfig> configs = autoDeliveryConfigMapper
                    .findByAccountId(xianyuAccountId);
            
            List<AutoDeliveryConfigRespDTO> respDTOs = configs.stream()
                    .map(config -> {
                        AutoDeliveryConfigRespDTO respDTO = new AutoDeliveryConfigRespDTO();
                        BeanUtils.copyProperties(config, respDTO);
                        return respDTO;
                    })
                    .collect(Collectors.toList());
            
            return ResultObject.success(respDTOs);
        } catch (Exception e) {
            log.error("查询账号自动发货配置列表失败", e);
            return ResultObject.failed("查询账号自动发货配置列表失败: " + e.getMessage());
        }
    }
    
    @Override
    public ResultObject<Void> deleteSkuConfig(Long xianyuAccountId, String xyGoodsId, String skuId) {
        String normalizedSkuId = normalizeSkuId(skuId);
        if (xianyuAccountId == null || xyGoodsId == null || xyGoodsId.isBlank() || normalizedSkuId == null) {
            return ResultObject.failed("账号、商品和规格不能为空");
        }
        int deleted = autoDeliveryConfigMapper.deleteByAccountIdAndGoodsIdAndSkuId(
                xianyuAccountId, xyGoodsId.trim(), normalizedSkuId);
        return ResultObject.success(null, deleted > 0 ? "规格配置已删除，后续订单将暂停自动发货" : "该规格当前未配置自动发货");
    }

    private String validateSkuDeliveryConfig(AutoDeliveryConfigReqDTO request, String skuId) {
        if (skuId != null) {
            XianyuGoodsSku sku = goodsSkuService.listByAccountIdAndXyGoodsId(
                            request.getXianyuAccountId(), request.getXyGoodsId()).stream()
                    .filter(item -> skuId.equals(item.getSkuId()))
                    .findFirst()
                    .orElse(null);
            if (sku == null) {
                return "该规格不存在或不属于当前商品，请先重新同步商品";
            }
            if (request.getSkuName() == null || request.getSkuName().isBlank()) {
                request.setSkuName(sku.getDisplayName() == null || sku.getDisplayName().isBlank()
                        ? sku.getValueText() : sku.getDisplayName());
            }
        }
        if (Integer.valueOf(2).equals(request.getDeliveryMode())) {
            if (request.getKamiConfigIds() == null || request.getKamiConfigIds().isBlank()) {
                return "卡密发货必须选择一个卡密库";
            }
            for (String value : request.getKamiConfigIds().split(",")) {
                Long configId;
                try {
                    configId = Long.valueOf(value.trim());
                } catch (Exception exception) {
                    return "卡密库配置格式不正确";
                }
                XianyuKamiConfig config = kamiConfigMapper.selectById(configId);
                if (config == null) {
                    return "选择的卡密库不存在或已删除";
                }
                if (config.getXianyuAccountId() != null
                        && !request.getXianyuAccountId().equals(config.getXianyuAccountId())) {
                    return "选择的卡密库不属于当前账号";
                }
            }
        }
        if (Integer.valueOf(4).equals(request.getDeliveryMode())) {
            request.setZeroInputCount(normalizeZeroInputCount(request.getZeroInputCount()));
        }
        return null;
    }

    private int normalizeZeroInputCount(Integer value) {
        return Math.max(1, Math.min(value == null ? 1 : value, 100));
    }

    private String normalizeSkuId(String skuId) {
        if (skuId == null) return null;
        String normalized = skuId.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    @Override
    public ResultObject<Void> deleteConfig(Long xianyuAccountId, String xyGoodsId) {
        try {
            LambdaQueryWrapper<XianyuGoodsAutoDeliveryConfig> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(XianyuGoodsAutoDeliveryConfig::getXianyuAccountId, xianyuAccountId)
                   .eq(XianyuGoodsAutoDeliveryConfig::getXyGoodsId, xyGoodsId);
            
            int deletedCount = autoDeliveryConfigMapper.delete(wrapper);
            
            if (deletedCount > 0) {
                log.info("删除自动发货配置成功，账号ID: {}, 商品ID: {}", xianyuAccountId, xyGoodsId);
                return ResultObject.success(null);
            } else {
                return ResultObject.failed("未找到对应的自动发货配置");
            }
        } catch (Exception e) {
            log.error("删除自动发货配置失败", e);
            return ResultObject.failed("删除自动发货配置失败: " + e.getMessage());
        }
    }
}
