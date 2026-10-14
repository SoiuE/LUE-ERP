CREATE TABLE shop_sku_mappings (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  shop_id BIGINT NOT NULL,
  platform_sku VARCHAR(160) NOT NULL,
  platform_product_id VARCHAR(160),
  platform_product_name VARCHAR(255),
  sku_id BIGINT NOT NULL,
  default_order_mode ENUM('JIT','WAREHOUSE') NOT NULL DEFAULT 'JIT',
  status ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  active_platform_sku VARCHAR(160) GENERATED ALWAYS AS (
    CASE WHEN status='ACTIVE' AND is_deleted=FALSE THEN platform_sku ELSE NULL END
  ) STORED,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_shop_sku_mappings_active_shop_sku (shop_id, active_platform_sku),
  INDEX idx_shop_sku_mappings_platform_sku (platform_sku),
  INDEX idx_shop_sku_mappings_sku_id (sku_id),
  INDEX idx_shop_sku_mappings_status_deleted (status,is_deleted),
  CONSTRAINT fk_shop_sku_mappings_shop FOREIGN KEY (shop_id) REFERENCES shops(id),
  CONSTRAINT fk_shop_sku_mappings_sku FOREIGN KEY (sku_id) REFERENCES skus(id)
);
