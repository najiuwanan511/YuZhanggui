package com.yuzhanggui.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhanggui.controller.dto.ProductMaterialDTO;
import com.yuzhanggui.controller.dto.ProductMaterialImportReqDTO;
import com.yuzhanggui.controller.dto.ProductMaterialSaveReqDTO;
import com.yuzhanggui.controller.dto.ProductPublishReqDTO;
import com.yuzhanggui.controller.dto.SyncSingleItemRespDTO;
import com.yuzhanggui.entity.XianyuGoodsInfo;
import com.yuzhanggui.entity.XianyuGoodsSku;
import com.yuzhanggui.entity.XianyuGoodsSkuProperty;
import com.yuzhanggui.entity.XianyuProductMaterial;
import com.yuzhanggui.exception.BusinessException;
import com.yuzhanggui.mapper.XianyuProductMaterialMapper;
import com.yuzhanggui.service.GoodsInfoService;
import com.yuzhanggui.service.GoodsSkuPropertyService;
import com.yuzhanggui.service.GoodsSkuService;
import com.yuzhanggui.service.ImageDimensionService;
import com.yuzhanggui.service.ItemDetailSyncService;
import com.yuzhanggui.service.ProductMaterialService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ProductMaterialServiceImpl implements ProductMaterialService {
    private static final Set<String> DELIVERY_MODES = Set.of("FREE", "FLAT", "NONE", "SELF_PICKUP");
    private static final int MAX_IMAGES = 9;
    private static final int MAX_SKUS = 20;

    private final XianyuProductMaterialMapper mapper;
    private final ObjectMapper objectMapper;
    private final GoodsInfoService goodsInfoService;
    private final GoodsSkuService goodsSkuService;
    private final GoodsSkuPropertyService goodsSkuPropertyService;
    private final ItemDetailSyncService itemDetailSyncService;
    private final ImageDimensionService imageDimensionService;

    public ProductMaterialServiceImpl(XianyuProductMaterialMapper mapper,
                                      ObjectMapper objectMapper,
                                      GoodsInfoService goodsInfoService,
                                      GoodsSkuService goodsSkuService,
                                      GoodsSkuPropertyService goodsSkuPropertyService,
                                      ItemDetailSyncService itemDetailSyncService,
                                      ImageDimensionService imageDimensionService) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.goodsInfoService = goodsInfoService;
        this.goodsSkuService = goodsSkuService;
        this.goodsSkuPropertyService = goodsSkuPropertyService;
        this.itemDetailSyncService = itemDetailSyncService;
        this.imageDimensionService = imageDimensionService;
    }

    @Override
    public List<ProductMaterialDTO> list(String keyword) {
        LambdaQueryWrapper<XianyuProductMaterial> query = new LambdaQueryWrapper<XianyuProductMaterial>()
                .orderByDesc(XianyuProductMaterial::getUpdateTime);
        if (StringUtils.hasText(keyword)) {
            String value = keyword.trim();
            query.and(wrapper -> wrapper.like(XianyuProductMaterial::getMaterialName, value)
                    .or().like(XianyuProductMaterial::getTitle, value));
        }
        return mapper.selectList(query).stream().map(this::toDto).toList();
    }

    @Override
    public ProductMaterialDTO get(Long id) {
        XianyuProductMaterial entity = id == null ? null : mapper.selectById(id);
        if (entity == null) throw new BusinessException(404, "商品素材不存在");
        return toDto(entity);
    }

    @Override
    @Transactional
    public ProductMaterialDTO save(ProductMaterialSaveReqDTO request) {
        List<ProductPublishReqDTO.SkuSpec> skuSpecs = validatedSkuSpecs(request);
        validate(request, skuSpecs);
        XianyuProductMaterial entity = request.getId() == null ? new XianyuProductMaterial() : mapper.selectById(request.getId());
        if (entity == null) throw new BusinessException(404, "商品素材不存在");
        if (request.getId() == null || request.getSourceAccountId() != null || StringUtils.hasText(request.getSourceGoodsId())) {
            entity.setSourceAccountId(request.getSourceAccountId());
            entity.setSourceGoodsId(trimToNull(request.getSourceGoodsId()));
        }
        entity.setMaterialName(request.getMaterialName().trim());
        entity.setTitle(request.getTitle().trim());
        entity.setDescription(trim(request.getDescription()));
        entity.setPrice(skuSpecs.isEmpty() ? request.getPrice() : skuSpecs.stream()
                .map(ProductPublishReqDTO.SkuSpec::getPrice).min(BigDecimal::compareTo).orElse(request.getPrice()));
        entity.setOriginalPrice(request.getOriginalPrice());
        entity.setQuantity(skuSpecs.isEmpty() ? request.getQuantity() : skuSpecs.stream()
                .mapToInt(ProductPublishReqDTO.SkuSpec::getQuantity).sum());
        entity.setSkuPropertyName(skuSpecs.isEmpty() ? null : request.getSkuPropertyName().trim());
        entity.setDeliveryMode(request.getDeliveryMode());
        entity.setPostFee(request.getPostFee());
        try {
            entity.setImagesJson(objectMapper.writeValueAsString(request.getImages()));
            entity.setSkuSpecsJson(skuSpecs.isEmpty() ? null : objectMapper.writeValueAsString(skuSpecs));
        } catch (Exception error) {
            throw new BusinessException(400, "商品素材数据无法保存");
        }
        if (request.getId() == null) mapper.insert(entity); else mapper.updateById(entity);
        return get(entity.getId());
    }

    @Override
    @Transactional
    public ProductMaterialDTO importFromGoods(ProductMaterialImportReqDTO request) {
        if (request == null || request.getXianyuAccountId() == null || !StringUtils.hasText(request.getXyGoodsId())) {
            throw new BusinessException(400, "来源账号和商品 ID 不能为空");
        }
        Long accountId = request.getXianyuAccountId();
        String goodsId = request.getXyGoodsId().trim();
        XianyuGoodsInfo source = requireOwnedGoods(accountId, goodsId);
        List<XianyuGoodsSku> skus = goodsSkuService.listByAccountIdAndXyGoodsId(accountId, goodsId);

        boolean cacheIncomplete = !StringUtils.hasText(source.getDetailInfo())
                || !StringUtils.hasText(source.getInfoPic())
                || (source.getSkuCount() != null && source.getSkuCount() > 1 && skus.size() < 2);
        if (!Boolean.FALSE.equals(request.getRefreshDetail()) || cacheIncomplete) {
            SyncSingleItemRespDTO syncResult = itemDetailSyncService.syncSingleItem(accountId, goodsId);
            source = requireOwnedGoods(accountId, goodsId);
            skus = goodsSkuService.listByAccountIdAndXyGoodsId(accountId, goodsId);
            boolean multiSkuMissing = source.getSkuCount() != null && source.getSkuCount() > 1 && skus.size() < 2;
            if (syncResult != null && !syncResult.isSuccess() && multiSkuMissing) {
                throw new BusinessException(409, StringUtils.hasText(syncResult.getMessage())
                        ? syncResult.getMessage() : "多规格信息同步失败，请更新账号凭证后重试");
            }
        }

        ProductMaterialSaveReqDTO material = new ProductMaterialSaveReqDTO();
        XianyuProductMaterial existing = mapper.selectOne(new LambdaQueryWrapper<XianyuProductMaterial>()
                .eq(XianyuProductMaterial::getSourceAccountId, accountId)
                .eq(XianyuProductMaterial::getSourceGoodsId, goodsId)
                .last("LIMIT 1"));
        if (existing != null) material.setId(existing.getId());
        material.setSourceAccountId(accountId);
        material.setSourceGoodsId(goodsId);
        String title = limited(trim(source.getTitle()), 60);
        if (!StringUtils.hasText(title)) throw new BusinessException(409, "来源商品没有可用标题，请先同步商品");
        material.setMaterialName(existing == null ? limited(title + "（已上架商品）", 120) : existing.getMaterialName());
        material.setTitle(title);
        material.setDescription(limited(StringUtils.hasText(source.getDetailInfo()) ? source.getDetailInfo().trim() : title, 5000));
        BigDecimal sourcePrice = parsePrice(source.getSoldPrice());
        material.setPrice(sourcePrice);
        material.setOriginalPrice(existing == null ? null : existing.getOriginalPrice());
        material.setQuantity(1);
        material.setDeliveryMode(existing == null ? "FREE" : existing.getDeliveryMode());
        material.setPostFee(existing == null ? null : existing.getPostFee());
        material.setImages(extractImages(source));

        List<ProductPublishReqDTO.SkuSpec> importedSkus = buildSkuSpecs(skus, sourcePrice);
        if (importedSkus.size() >= 2) {
            material.setSkuPropertyName(resolveSkuPropertyName(accountId, goodsId, skus));
            material.setSkuSpecs(importedSkus);
        }
        return save(material);
    }

    @Override
    public void delete(Long id) {
        if (id == null || mapper.deleteById(id) == 0) throw new BusinessException(404, "商品素材不存在");
    }

    private XianyuGoodsInfo requireOwnedGoods(Long accountId, String goodsId) {
        XianyuGoodsInfo source = goodsInfoService.getByXyGoodId(goodsId);
        if (source == null || !accountId.equals(source.getXianyuAccountId())) {
            throw new BusinessException(404, "该账号下未找到来源商品，请先同步商品列表");
        }
        return source;
    }

    private List<ProductPublishReqDTO.Image> extractImages(XianyuGoodsInfo source) {
        Map<String, ImageCandidate> candidates = new LinkedHashMap<>();
        if (StringUtils.hasText(source.getInfoPic())) {
            try {
                collectImages(objectMapper.readTree(source.getInfoPic()), candidates);
            } catch (Exception ignored) {
                addImageCandidate(candidates, source.getInfoPic(), 0, 0);
            }
        }
        addImageCandidate(candidates, source.getCoverPic(), 0, 0);
        List<ProductPublishReqDTO.Image> images = new ArrayList<>();
        for (ImageCandidate candidate : candidates.values()) {
            int width = candidate.width();
            int height = candidate.height();
            if (width <= 0 || height <= 0) {
                try {
                    ImageDimensionService.ImageDimensions dimensions = imageDimensionService.resolve(candidate.url());
                    if (dimensions != null && dimensions.isKnown()) {
                        width = dimensions.width();
                        height = dimensions.height();
                    }
                } catch (Exception ignored) {
                    // The caller gets an actionable error below if none of the source images can be resolved.
                }
            }
            if (width > 0 && height > 0) {
                ProductPublishReqDTO.Image image = new ProductPublishReqDTO.Image();
                image.setUrl(candidate.url());
                image.setWidth(width);
                image.setHeight(height);
                images.add(image);
                if (images.size() == MAX_IMAGES) break;
            }
        }
        if (images.isEmpty()) throw new BusinessException(409, "来源商品图片无法读取，请先同步商品或在素材编辑页重新上传图片");
        return images;
    }

    private void collectImages(JsonNode node, Map<String, ImageCandidate> candidates) {
        if (node == null || node.isNull() || candidates.size() >= MAX_IMAGES) return;
        if (node.isTextual()) {
            String value = node.asText().trim();
            if ((value.startsWith("[") || value.startsWith("{")) && value.length() > 2) {
                try {
                    collectImages(objectMapper.readTree(value), candidates);
                    return;
                } catch (Exception ignored) {
                    // Treat it as an ordinary string below.
                }
            }
            addImageCandidate(candidates, value, 0, 0);
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectImages(child, candidates));
            return;
        }
        if (!node.isObject()) return;
        String url = firstText(node, "url", "picUrl", "imageUrl", "imageURL");
        if (StringUtils.hasText(url)) {
            addImageCandidate(candidates, url, firstInt(node, "width", "picWidth"), firstInt(node, "height", "picHeight"));
        } else {
            node.elements().forEachRemaining(child -> collectImages(child, candidates));
        }
    }

    private String firstText(JsonNode node, String... keys) {
        for (String key : keys) {
            if (node.hasNonNull(key) && StringUtils.hasText(node.get(key).asText())) return node.get(key).asText();
        }
        return null;
    }

    private int firstInt(JsonNode node, String... keys) {
        for (String key : keys) {
            if (node.hasNonNull(key)) {
                try { return Integer.parseInt(node.get(key).asText()); } catch (NumberFormatException ignored) { }
            }
        }
        return 0;
    }

    private void addImageCandidate(Map<String, ImageCandidate> candidates, String rawUrl, int width, int height) {
        if (!StringUtils.hasText(rawUrl) || candidates.size() >= MAX_IMAGES) return;
        String url = rawUrl.trim();
        if (url.startsWith("//")) url = "https:" + url;
        if (!url.startsWith("https://") && !url.startsWith("http://")) return;
        candidates.putIfAbsent(url, new ImageCandidate(url, width, height));
    }

    private List<ProductPublishReqDTO.SkuSpec> buildSkuSpecs(List<XianyuGoodsSku> sourceSkus, BigDecimal fallbackPrice) {
        if (sourceSkus == null || sourceSkus.size() < 2) return List.of();
        List<XianyuGoodsSku> usable = sourceSkus.stream().limit(MAX_SKUS).toList();
        List<ProductPublishReqDTO.SkuSpec> result = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        int remainingQuantity = 999;
        for (int index = 0; index < usable.size(); index++) {
            XianyuGoodsSku sku = usable.get(index);
            String name = firstNonBlank(sku.getDisplayName(), sku.getValueText(), valuePart(sku.getPropertyText()), "规格" + (index + 1));
            name = limited(name.trim(), 50);
            String uniqueName = name;
            int suffix = 2;
            while (!names.add(uniqueName.toLowerCase(Locale.ROOT))) {
                String tail = "-" + suffix++;
                uniqueName = limited(name, 50 - tail.length()) + tail;
            }
            int rowsLeft = usable.size() - index - 1;
            int quantity = Math.max(1, sku.getQuantity() == null ? 1 : sku.getQuantity());
            quantity = Math.min(quantity, Math.max(1, remainingQuantity - rowsLeft));
            remainingQuantity -= quantity;
            BigDecimal price = sku.getPrice() == null || sku.getPrice() <= 0
                    ? fallbackPrice : BigDecimal.valueOf(sku.getPrice(), 2);
            ProductPublishReqDTO.SkuSpec spec = new ProductPublishReqDTO.SkuSpec();
            spec.setName(uniqueName);
            spec.setPrice(price);
            spec.setQuantity(quantity);
            result.add(spec);
        }
        return result;
    }

    private String resolveSkuPropertyName(Long accountId, String goodsId, List<XianyuGoodsSku> skus) {
        List<XianyuGoodsSkuProperty> properties = goodsSkuPropertyService.listByAccountIdAndXyGoodsId(accountId, goodsId);
        Set<String> propertyNames = new LinkedHashSet<>();
        for (XianyuGoodsSkuProperty property : properties) {
            if (StringUtils.hasText(property.getPropertyText())) propertyNames.add(property.getPropertyText().trim());
        }
        if (!propertyNames.isEmpty()) return limited(String.join("/", propertyNames), 30);
        for (XianyuGoodsSku sku : skus) {
            String text = sku.getPropertyText();
            if (StringUtils.hasText(text)) {
                int separator = Math.max(text.indexOf(':'), text.indexOf('：'));
                return limited((separator > 0 ? text.substring(0, separator) : text).trim(), 30);
            }
        }
        return "规格";
    }

    private String valuePart(String value) {
        if (!StringUtils.hasText(value)) return null;
        int separator = Math.max(value.indexOf(':'), value.indexOf('：'));
        return separator >= 0 && separator + 1 < value.length() ? value.substring(separator + 1) : value;
    }

    private BigDecimal parsePrice(String raw) {
        if (!StringUtils.hasText(raw)) throw new BusinessException(409, "来源商品价格无法读取，请先同步商品");
        try {
            String normalized = raw.replace(",", "").replaceAll("[^0-9.]", "");
            BigDecimal result = new BigDecimal(normalized).setScale(2, RoundingMode.HALF_UP);
            if (result.compareTo(BigDecimal.ZERO) <= 0) throw new NumberFormatException();
            return result;
        } catch (Exception error) {
            throw new BusinessException(409, "来源商品价格无法读取，请先同步商品");
        }
    }

    private List<ProductPublishReqDTO.SkuSpec> validatedSkuSpecs(ProductMaterialSaveReqDTO request) {
        if (request == null || request.getSkuSpecs() == null || request.getSkuSpecs().isEmpty()) return List.of();
        if (!StringUtils.hasText(request.getSkuPropertyName()) || request.getSkuPropertyName().trim().length() > 30) {
            throw new BusinessException(400, "规格名称不能为空且不能超过 30 个字符");
        }
        if (request.getSkuSpecs().size() < 2 || request.getSkuSpecs().size() > MAX_SKUS) {
            throw new BusinessException(400, "多规格商品必须包含 2 到 20 个规格");
        }
        List<ProductPublishReqDTO.SkuSpec> result = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        int totalQuantity = 0;
        for (ProductPublishReqDTO.SkuSpec source : request.getSkuSpecs()) {
            if (source == null || !StringUtils.hasText(source.getName()) || source.getName().trim().length() > 50) {
                throw new BusinessException(400, "规格值不能为空且不能超过 50 个字符");
            }
            String name = source.getName().trim();
            if (!names.add(name.toLowerCase(Locale.ROOT))) throw new BusinessException(400, "规格值不能重复");
            if (source.getPrice() == null || source.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException(400, "规格价格必须大于 0");
            }
            if (source.getQuantity() == null || source.getQuantity() < 1 || source.getQuantity() > 999) {
                throw new BusinessException(400, "规格库存必须在 1 到 999 之间");
            }
            totalQuantity += source.getQuantity();
            ProductPublishReqDTO.SkuSpec copy = new ProductPublishReqDTO.SkuSpec();
            copy.setName(name);
            copy.setPrice(source.getPrice());
            copy.setOriginalPrice(source.getOriginalPrice());
            copy.setQuantity(source.getQuantity());
            result.add(copy);
        }
        if (totalQuantity > 999) throw new BusinessException(400, "所有规格库存合计不能超过 999");
        return result;
    }

    private void validate(ProductMaterialSaveReqDTO request, List<ProductPublishReqDTO.SkuSpec> skuSpecs) {
        if (request == null || !StringUtils.hasText(request.getMaterialName()) || request.getMaterialName().trim().length() > 120) {
            throw new BusinessException(400, "素材名称不能为空且不能超过 120 个字符");
        }
        if (!StringUtils.hasText(request.getTitle()) || request.getTitle().trim().length() > 60) {
            throw new BusinessException(400, "商品标题不能为空且不能超过 60 个字符");
        }
        if (trim(request.getDescription()).length() > 5000) throw new BusinessException(400, "商品描述不能超过 5000 个字符");
        if (skuSpecs.isEmpty() && (request.getPrice() == null || request.getPrice().compareTo(BigDecimal.ZERO) <= 0)) {
            throw new BusinessException(400, "商品价格必须大于 0");
        }
        if (skuSpecs.isEmpty() && (request.getQuantity() == null || request.getQuantity() < 1 || request.getQuantity() > 999)) {
            throw new BusinessException(400, "库存必须在 1 到 999 之间");
        }
        if (!DELIVERY_MODES.contains(request.getDeliveryMode())) throw new BusinessException(400, "交付方式不正确");
        if (request.getImages() == null || request.getImages().size() > MAX_IMAGES) {
            throw new BusinessException(400, "商品图片最多 9 张");
        }
    }

    private ProductMaterialDTO toDto(XianyuProductMaterial entity) {
        ProductMaterialDTO dto = new ProductMaterialDTO();
        dto.setId(entity.getId());
        dto.setSourceAccountId(entity.getSourceAccountId());
        dto.setSourceGoodsId(entity.getSourceGoodsId());
        dto.setMaterialName(entity.getMaterialName());
        dto.setTitle(entity.getTitle());
        dto.setDescription(entity.getDescription());
        dto.setPrice(entity.getPrice());
        dto.setOriginalPrice(entity.getOriginalPrice());
        dto.setQuantity(entity.getQuantity());
        dto.setSkuPropertyName(entity.getSkuPropertyName());
        dto.setDeliveryMode(entity.getDeliveryMode());
        dto.setPostFee(entity.getPostFee());
        dto.setCreateTime(entity.getCreateTime());
        dto.setUpdateTime(entity.getUpdateTime());
        try {
            if (StringUtils.hasText(entity.getImagesJson())) {
                dto.setImages(objectMapper.readValue(entity.getImagesJson(), new TypeReference<List<ProductPublishReqDTO.Image>>() {}));
            }
        } catch (Exception ignored) {
            dto.setImages(List.of());
        }
        try {
            if (StringUtils.hasText(entity.getSkuSpecsJson())) {
                dto.setSkuSpecs(objectMapper.readValue(entity.getSkuSpecsJson(), new TypeReference<List<ProductPublishReqDTO.SkuSpec>>() {}));
            }
        } catch (Exception ignored) {
            dto.setSkuSpecs(List.of());
        }
        return dto;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (StringUtils.hasText(value)) return value;
        return "";
    }

    private String trim(String value) { return value == null ? "" : value.trim(); }
    private String trimToNull(String value) { return StringUtils.hasText(value) ? value.trim() : null; }
    private String limited(String value, int length) { return value.length() <= length ? value : value.substring(0, length); }

    private record ImageCandidate(String url, int width, int height) { }
}
