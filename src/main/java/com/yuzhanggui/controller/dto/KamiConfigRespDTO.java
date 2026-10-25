package com.yuzhanggui.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class KamiConfigRespDTO {

    private Long id;

    private Long xianyuAccountId;

    private String aliasName;

    private Integer sourceType;

    private String fixedContent;

    private String deliveryTemplate;

    private String deliveryImageUrl;

    /** 每个账号单独上传后的闲鱼图片地址。 */
    private Map<Long, String> deliveryImageUrls;

    /** 当前已绑定此卡券库的商品数量。 */
    private Integer relatedGoodsCount;

    private String apiUrl;

    private String apiMethod;

    private String apiHeaders;

    private String apiRequestTemplate;

    private String apiResultPath;

    private Integer apiTimeoutSeconds;

    private Integer alertEnabled;

    private Integer alertThresholdType;

    private Integer alertThresholdValue;

    private String alertEmail;

    private Integer totalCount;

    private Integer usedCount;

    private Integer availableCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
