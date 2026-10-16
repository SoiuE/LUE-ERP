-- Compatible metadata only: the order_summaries business_key and income are unchanged.
ALTER TABLE order_summaries ADD platform_line_no VARCHAR(160) NULL AFTER platform_order_no,
  ADD KEY idx_order_platform_match (shop_id,platform_order_no,platform_line_no,platform_sku,is_deleted);

CREATE TABLE fulfillment_order_matches (
  fulfillment_item_id BIGINT PRIMARY KEY,
  order_summary_id BIGINT NOT NULL,
  match_method ENUM('AUTO','MANUAL') NOT NULL,
  matched_by BIGINT NULL,
  matched_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_profit_fulfillment_item FOREIGN KEY (fulfillment_item_id) REFERENCES fulfillment_document_items(id),
  CONSTRAINT fk_profit_fulfillment_order FOREIGN KEY (order_summary_id) REFERENCES order_summaries(id),
  CONSTRAINT fk_profit_fulfillment_operator FOREIGN KEY (matched_by) REFERENCES users(id)
);

CREATE TABLE profit_match_issues (
  source_type ENUM('FULFILLMENT_ITEM','SETTLEMENT_LINE','COST_SOURCE') NOT NULL,
  source_id BIGINT NOT NULL,
  exception_id BIGINT NOT NULL,
  PRIMARY KEY (source_type,source_id),
  CONSTRAINT fk_profit_issue_exception FOREIGN KEY (exception_id) REFERENCES exceptions(id)
);

ALTER TABLE settlement_summaries
  MODIFY shop_id BIGINT NULL,
  MODIFY sku_id BIGINT NULL,
  MODIFY order_mode ENUM('JIT','WAREHOUSE') NULL,
  ADD platform_bill_no VARCHAR(160) NULL AFTER platform_order_no,
  ADD platform_line_no VARCHAR(160) NULL AFTER platform_bill_no,
  ADD platform_order_line_no VARCHAR(160) NULL AFTER platform_line_no,
  ADD adjustment_type VARCHAR(40) NULL AFTER platform_order_line_no,
  ADD adjustment_amount DECIMAL(18,4) NULL AFTER adjustment_type,
  ADD settlement_complete BOOLEAN NOT NULL DEFAULT FALSE AFTER adjustment_amount,
  ADD source_fingerprint CHAR(64) NULL AFTER settlement_complete,
  ADD active_fingerprint CHAR(64) GENERATED ALWAYS AS (CASE WHEN is_deleted=FALSE THEN source_fingerprint ELSE NULL END) STORED,
  ADD order_summary_id BIGINT NULL AFTER active_fingerprint,
  ADD match_status ENUM('PENDING','MATCHED','MANUAL','UNMAPPED','DUPLICATE') NOT NULL DEFAULT 'PENDING' AFTER order_summary_id,
  ADD match_reason VARCHAR(500) NULL AFTER match_status,
  ADD UNIQUE KEY uk_active_settlement_fingerprint (active_fingerprint),
  ADD KEY idx_settlement_order (order_summary_id,settlement_date,is_deleted),
  ADD KEY idx_settlement_bill (shop_id,platform_bill_no,platform_line_no),
  ADD CONSTRAINT fk_settlement_order FOREIGN KEY (order_summary_id) REFERENCES order_summaries(id);

CREATE TABLE order_profit_facts (
  order_summary_id BIGINT PRIMARY KEY,
  order_date DATE NOT NULL,
  shop_id BIGINT NOT NULL,
  sku_id BIGINT NOT NULL,
  order_mode ENUM('JIT','WAREHOUSE') NOT NULL,
  sales_qty DECIMAL(18,4) NOT NULL,
  sales_revenue DECIMAL(18,4) NOT NULL,
  platform_fee DECIMAL(18,4) NOT NULL,
  purchase_cost DECIMAL(18,4) NOT NULL,
  package_cost DECIMAL(18,4) NOT NULL,
  refund_loss DECIMAL(18,4) NOT NULL,
  other_cost DECIMAL(18,4) NOT NULL DEFAULT 0,
  gross_profit DECIMAL(18,4) NOT NULL,
  cost_basis ENUM('ACTUAL','MIXED_ESTIMATE','LEGACY_REFERENCE','COST_UNMATCHED') NOT NULL,
  settlement_status ENUM('ESTIMATED','PARTIALLY_SETTLED','SETTLED') NOT NULL,
  estimated_platform_fee DECIMAL(18,4) NOT NULL,
  estimated_refund_loss DECIMAL(18,4) NOT NULL,
  settlement_adjustment DECIMAL(18,4) NOT NULL DEFAULT 0,
  settlement_cash_amount DECIMAL(18,4) NOT NULL DEFAULT 0,
  settlement_difference DECIMAL(18,4) NOT NULL DEFAULT 0,
  fulfilled_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
  calculated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  KEY idx_order_profit_dimensions (order_date,shop_id,sku_id,order_mode),
  CONSTRAINT fk_order_profit_order FOREIGN KEY (order_summary_id) REFERENCES order_summaries(id),
  CONSTRAINT fk_order_profit_shop FOREIGN KEY (shop_id) REFERENCES shops(id),
  CONSTRAINT fk_order_profit_sku FOREIGN KEY (sku_id) REFERENCES skus(id)
);

ALTER TABLE sku_profit_daily
  ADD actual_cost_orders INT NOT NULL DEFAULT 0,
  ADD mixed_cost_orders INT NOT NULL DEFAULT 0,
  ADD legacy_cost_orders INT NOT NULL DEFAULT 0,
  ADD cost_unmatched_orders INT NOT NULL DEFAULT 0,
  ADD estimated_orders INT NOT NULL DEFAULT 0,
  ADD partial_settlement_orders INT NOT NULL DEFAULT 0,
  ADD settled_orders INT NOT NULL DEFAULT 0,
  ADD settlement_difference DECIMAL(18,4) NOT NULL DEFAULT 0;

-- Existing aggregate rows have no confirmed source link yet. Preserve their figures and mark their provenance.
UPDATE sku_profit_daily SET legacy_cost_orders=1 WHERE is_deleted=FALSE;
UPDATE settlement_summaries SET match_status='UNMAPPED',match_reason='历史结算明细缺少稳定账单号及明细号，需重新映射' WHERE platform_bill_no IS NULL OR platform_line_no IS NULL;

CREATE TABLE profit_recalculation_runs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  trigger_type ENUM('MANUAL','SCHEDULED','ORDER_IMPORT','SETTLEMENT_IMPORT','MATCH_REPAIR','OTHER_COST_IMPORT') NOT NULL,
  date_from DATE NOT NULL,
  date_to DATE NOT NULL,
  shop_id BIGINT NULL,
  sku_id BIGINT NULL,
  requested_by BIGINT NULL,
  status ENUM('RUNNING','SUCCESS','FAILED') NOT NULL DEFAULT 'RUNNING',
  affected_order_rows INT NOT NULL DEFAULT 0,
  affected_daily_rows INT NOT NULL DEFAULT 0,
  failure_reason VARCHAR(1000) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  completed_at TIMESTAMP(6) NULL,
  KEY idx_profit_recalc_date (date_from,date_to,created_at),
  CONSTRAINT fk_profit_recalc_user FOREIGN KEY (requested_by) REFERENCES users(id)
);
