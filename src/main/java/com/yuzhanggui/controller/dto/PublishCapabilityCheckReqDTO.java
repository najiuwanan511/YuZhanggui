package com.yuzhanggui.controller.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 商品发布能力只读检测请求。 */
@Data
public class PublishCapabilityCheckReqDTO {
    private Long accountId;
    private String title;
    private String description;
    /** Already uploaded platform images can improve category recognition. */
    private List<ProductPublishReqDTO.Image> images = new ArrayList<>();
    /** Previously selected platform fields, used to load dependent property options. */
    private List<ProductPublishReqDTO.PropertySelection> properties = new ArrayList<>();
}
