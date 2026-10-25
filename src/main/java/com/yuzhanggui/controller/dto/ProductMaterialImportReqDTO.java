package com.yuzhanggui.controller.dto;

import lombok.Data;

/** Imports a product already listed by one connected account into the reusable material library. */
@Data
public class ProductMaterialImportReqDTO {
    private Long xianyuAccountId;
    private String xyGoodsId;
    /** Refresh description and SKU information from Xianyu before importing. */
    private Boolean refreshDetail = true;
}
