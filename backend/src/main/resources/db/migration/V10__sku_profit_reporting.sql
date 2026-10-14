CREATE TABLE settlement_summaries (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  import_batch_id BIGINT NOT NULL,
  source_row_number INT NOT NULL,
  shop_id BIGINT NOT NULL,
  platform_order_no VARCHAR(160) NULL,
  platform_sku VARCHAR(160) NOT NULL,
  sku_id BIGINT NOT NULL,
  order_mode ENUM('JIT','WAREHOUSE') NOT NULL,
  settlement_date DATE NOT NULL,
  platform_fee DECIMAL(18,4) NOT NULL DEFAULT 0.0000,
  settlement_amount DECIMAL(18,4) NOT NULL DEFAULT 0.0000,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_settlement_batch_row (import_batch_id,source_row_number),
  INDEX idx_settlement_profit_key (settlement_date,shop_id,sku_id,order_mode,is_deleted),
  CONSTRAINT fk_settlement_batch FOREIGN KEY (import_batch_id) REFERENCES import_batches(id),
  CONSTRAINT fk_settlement_shop FOREIGN KEY (shop_id) REFERENCES shops(id),
  CONSTRAINT fk_settlement_sku FOREIGN KEY (sku_id) REFERENCES skus(id)
);

CREATE TABLE other_cost_imports (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  import_batch_id BIGINT NOT NULL,
  source_row_number INT NOT NULL,
  shop_id BIGINT NOT NULL,
  sku_id BIGINT NOT NULL,
  cost_date DATE NOT NULL,
  amount DECIMAL(18,4) NOT NULL,
  note VARCHAR(500) NULL,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_other_cost_batch_row (import_batch_id,source_row_number),
  INDEX idx_other_cost_profit_key (cost_date,shop_id,sku_id,is_deleted),
  CONSTRAINT fk_other_cost_batch FOREIGN KEY (import_batch_id) REFERENCES import_batches(id),
  CONSTRAINT fk_other_cost_shop FOREIGN KEY (shop_id) REFERENCES shops(id),
  CONSTRAINT fk_other_cost_sku FOREIGN KEY (sku_id) REFERENCES skus(id)
);

CREATE TABLE sku_profit_daily (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  profit_date DATE NOT NULL,
  shop_id BIGINT NULL,
  shop_key BIGINT GENERATED ALWAYS AS (COALESCE(shop_id,0)) STORED,
  sku_id BIGINT NOT NULL,
  order_mode ENUM('JIT','WAREHOUSE') NOT NULL,
  sales_qty DECIMAL(18,4) NOT NULL DEFAULT 0.0000,
  sales_revenue DECIMAL(18,4) NOT NULL DEFAULT 0.0000,
  platform_fee DECIMAL(18,4) NOT NULL DEFAULT 0.0000,
  purchase_cost DECIMAL(18,4) NOT NULL DEFAULT 0.0000,
  package_cost DECIMAL(18,4) NOT NULL DEFAULT 0.0000,
  refund_loss DECIMAL(18,4) NOT NULL DEFAULT 0.0000,
  other_cost DECIMAL(18,4) NOT NULL DEFAULT 0.0000,
  gross_profit DECIMAL(18,4) NOT NULL DEFAULT 0.0000,
  gross_margin DECIMAL(18,8) NULL,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  calculated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_sku_profit_daily_dimension (profit_date,shop_key,sku_id,order_mode),
  INDEX idx_sku_profit_daily_shop_date (shop_id,profit_date,is_deleted),
  INDEX idx_sku_profit_daily_sku_date (sku_id,profit_date,is_deleted),
  INDEX idx_sku_profit_daily_mode_date (order_mode,profit_date,is_deleted),
  CONSTRAINT fk_sku_profit_shop FOREIGN KEY (shop_id) REFERENCES shops(id),
  CONSTRAINT fk_sku_profit_sku FOREIGN KEY (sku_id) REFERENCES skus(id)
);

INSERT IGNORE INTO permissions(code,name,type,path) VALUES
 ('menu.profits','SKU 利润报表','MENU','/profits'),
 ('button.profits.import','利润数据导入','BUTTON',NULL),
 ('button.profits.recalculate','利润手动重算','BUTTON',NULL);
