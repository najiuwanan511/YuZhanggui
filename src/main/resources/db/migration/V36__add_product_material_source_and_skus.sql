ALTER TABLE xianyu_product_material
    ADD COLUMN source_account_id BIGINT NULL AFTER id,
    ADD COLUMN source_goods_id VARCHAR(100) NULL AFTER source_account_id,
    ADD COLUMN sku_property_name VARCHAR(30) NULL AFTER quantity,
    ADD COLUMN sku_specs_json TEXT NULL AFTER sku_property_name,
    ADD UNIQUE KEY uk_product_material_source (source_account_id, source_goods_id);
