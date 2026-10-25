package com.yuzhanggui.service;

import com.yuzhanggui.controller.dto.ProductCopywritingReqDTO;
import com.yuzhanggui.controller.dto.ProductCopywritingRespDTO;

public interface ProductCopywritingService {
    ProductCopywritingRespDTO generate(ProductCopywritingReqDTO request);
}
