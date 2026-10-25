package com.yuzhanggui.service;

import com.yuzhanggui.controller.dto.ProductMaterialDTO;
import com.yuzhanggui.controller.dto.ProductMaterialImportReqDTO;
import com.yuzhanggui.controller.dto.ProductMaterialSaveReqDTO;

import java.util.List;

public interface ProductMaterialService {
    List<ProductMaterialDTO> list(String keyword);
    ProductMaterialDTO get(Long id);
    ProductMaterialDTO save(ProductMaterialSaveReqDTO request);
    ProductMaterialDTO importFromGoods(ProductMaterialImportReqDTO request);
    void delete(Long id);
}
