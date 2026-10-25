package com.yuzhanggui.service;

import com.yuzhanggui.controller.dto.ProductPublishReqDTO;
import com.yuzhanggui.controller.dto.ProductPublishRespDTO;
import com.yuzhanggui.controller.dto.ProductPublishLocationDTO;

import java.util.List;

public interface ProductPublishService {
    ProductPublishRespDTO publish(ProductPublishReqDTO request);
    List<ProductPublishLocationDTO> listLocations(Long accountId, Double longitude, Double latitude);
}
