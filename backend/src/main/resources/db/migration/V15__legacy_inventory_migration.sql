-- Metadata only. This migration never copies or changes V1 inventory rows.
CREATE TABLE legacy_inventory_migration_batches (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  idempotency_key VARCHAR(100) NOT NULL,
  source_warehouse_id BIGINT NOT NULL,
  migration_version VARCHAR(32) NOT NULL DEFAULT 'V1_TO_GOODS_V1',
  status ENUM('DRAFT','EXECUTED','ROLLED_BACK') NOT NULL DEFAULT 'DRAFT',
  created_by BIGINT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  executed_by BIGINT NULL,
  executed_at TIMESTAMP(6) NULL,
  rolled_back_by BIGINT NULL,
  rolled_back_at TIMESTAMP(6) NULL,
  rollback_reason VARCHAR(1000) NULL,
  UNIQUE KEY uk_legacy_migration_key (idempotency_key),
  KEY idx_legacy_migration_warehouse (source_warehouse_id,status),
  CONSTRAINT fk_legacy_migration_warehouse FOREIGN KEY (source_warehouse_id) REFERENCES warehouses(id),
  CONSTRAINT fk_legacy_migration_creator FOREIGN KEY (created_by) REFERENCES users(id),
  CONSTRAINT fk_legacy_migration_executor FOREIGN KEY (executed_by) REFERENCES users(id),
  CONSTRAINT fk_legacy_migration_rollback_user FOREIGN KEY (rolled_back_by) REFERENCES users(id)
);

CREATE TABLE legacy_inventory_migration_mappings (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  batch_id BIGINT NOT NULL,
  source_stock_id BIGINT NOT NULL,
  source_sku_id BIGINT NOT NULL,
  target_goods_id BIGINT NOT NULL,
  anomaly_reason VARCHAR(1000) NULL,
  status ENUM('DRAFT','CONFIRMED') NOT NULL DEFAULT 'DRAFT',
  confirmed_by BIGINT NULL,
  confirmed_at TIMESTAMP(6) NULL,
  snapshot_available DECIMAL(18,4) NULL,
  snapshot_locked DECIMAL(18,4) NULL,
  snapshot_pending_inbound DECIMAL(18,4) NULL,
  snapshot_pending_outbound DECIMAL(18,4) NULL,
  snapshot_abnormal DECIMAL(18,4) NULL,
  snapshot_stock_updated_at TIMESTAMP NULL,
  snapshot_flow_count BIGINT NULL,
  snapshot_flow_max_id BIGINT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_legacy_mapping_source (batch_id,source_stock_id),
  UNIQUE KEY uk_legacy_mapping_target (batch_id,target_goods_id),
  CONSTRAINT fk_legacy_mapping_batch FOREIGN KEY (batch_id) REFERENCES legacy_inventory_migration_batches(id),
  CONSTRAINT fk_legacy_mapping_stock FOREIGN KEY (source_stock_id) REFERENCES inventory_stocks(id),
  CONSTRAINT fk_legacy_mapping_sku FOREIGN KEY (source_sku_id) REFERENCES skus(id),
  CONSTRAINT fk_legacy_mapping_good FOREIGN KEY (target_goods_id) REFERENCES warehouse_goods(id),
  CONSTRAINT fk_legacy_mapping_confirmer FOREIGN KEY (confirmed_by) REFERENCES users(id)
);

-- In-transit quantities have no physical bin. They must not be written as pending-putaway stock.
CREATE TABLE warehouse_goods_pending_balances (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  warehouse_id BIGINT NOT NULL,
  warehouse_goods_id BIGINT NOT NULL,
  pending_inbound_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
  pending_outbound_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_goods_pending_warehouse (warehouse_id,warehouse_goods_id),
  CONSTRAINT fk_goods_pending_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(id),
  CONSTRAINT fk_goods_pending_good FOREIGN KEY (warehouse_goods_id) REFERENCES warehouse_goods(id)
);

CREATE TABLE legacy_inventory_migration_items (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  batch_id BIGINT NOT NULL,
  mapping_id BIGINT NOT NULL,
  source_stock_id BIGINT NOT NULL,
  source_warehouse_id BIGINT NOT NULL,
  source_sku_id BIGINT NOT NULL,
  target_goods_id BIGINT NOT NULL,
  target_balance_id BIGINT NOT NULL,
  target_pending_id BIGINT NOT NULL,
  available_qty DECIMAL(18,4) NOT NULL,
  locked_qty DECIMAL(18,4) NOT NULL,
  pending_inbound_qty DECIMAL(18,4) NOT NULL,
  pending_outbound_qty DECIMAL(18,4) NOT NULL,
  abnormal_qty DECIMAL(18,4) NOT NULL,
  source_stock_updated_at TIMESTAMP NOT NULL,
  source_flow_count BIGINT NOT NULL,
  source_flow_min_id BIGINT NULL,
  source_flow_max_id BIGINT NULL,
  legacy_purchase_price_reference DECIMAL(18,4) NULL,
  cost_basis ENUM('LEGACY_SKU_REFERENCE_UNVERIFIED') NOT NULL DEFAULT 'LEGACY_SKU_REFERENCE_UNVERIFIED',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_legacy_item_batch_source (batch_id,source_stock_id),
  UNIQUE KEY uk_legacy_item_batch_target (batch_id,target_goods_id),
  UNIQUE KEY uk_legacy_item_mapping (mapping_id),
  KEY idx_legacy_item_source (source_stock_id),
  CONSTRAINT fk_legacy_item_batch FOREIGN KEY (batch_id) REFERENCES legacy_inventory_migration_batches(id),
  CONSTRAINT fk_legacy_item_mapping FOREIGN KEY (mapping_id) REFERENCES legacy_inventory_migration_mappings(id),
  CONSTRAINT fk_legacy_item_source_stock FOREIGN KEY (source_stock_id) REFERENCES inventory_stocks(id),
  CONSTRAINT fk_legacy_item_source_sku FOREIGN KEY (source_sku_id) REFERENCES skus(id),
  CONSTRAINT fk_legacy_item_target_good FOREIGN KEY (target_goods_id) REFERENCES warehouse_goods(id),
  CONSTRAINT fk_legacy_item_target_balance FOREIGN KEY (target_balance_id) REFERENCES warehouse_goods_location_balances(id),
  CONSTRAINT fk_legacy_item_target_pending FOREIGN KEY (target_pending_id) REFERENCES warehouse_goods_pending_balances(id)
);

CREATE TABLE legacy_inventory_migration_movements (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  item_id BIGINT NOT NULL,
  movement_type ENUM('APPLY','REVERSE') NOT NULL,
  available_delta DECIMAL(18,4) NOT NULL,
  locked_delta DECIMAL(18,4) NOT NULL,
  pending_inbound_delta DECIMAL(18,4) NOT NULL,
  pending_outbound_delta DECIMAL(18,4) NOT NULL,
  abnormal_delta DECIMAL(18,4) NOT NULL,
  operator_id BIGINT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_legacy_movement_once (item_id,movement_type),
  CONSTRAINT fk_legacy_movement_item FOREIGN KEY (item_id) REFERENCES legacy_inventory_migration_items(id),
  CONSTRAINT fk_legacy_movement_operator FOREIGN KEY (operator_id) REFERENCES users(id)
);

-- Future stock writers must insert a reference before using migrated stock.
CREATE TABLE legacy_inventory_migration_references (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  item_id BIGINT NOT NULL,
  reference_type VARCHAR(64) NOT NULL,
  reference_id BIGINT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_legacy_migration_reference (item_id,reference_type,reference_id),
  CONSTRAINT fk_legacy_reference_item FOREIGN KEY (item_id) REFERENCES legacy_inventory_migration_items(id)
);

INSERT IGNORE INTO permissions(code,name,type,path) VALUES
 ('inventory.migration.view','旧库存迁移预览与对账','MENU','/inventory-migration'),
 ('button.inventory.migration.manage','维护旧库存迁移映射','BUTTON',NULL),
 ('button.inventory.migration.execute','执行与冲销旧库存迁移','BUTTON',NULL);
INSERT IGNORE INTO role_permissions(role_id,permission_id)
 SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('inventory.migration.view','button.inventory.migration.manage','button.inventory.migration.execute') WHERE r.code='ADMIN';
INSERT IGNORE INTO role_permissions(role_id,permission_id)
 SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('inventory.migration.view','button.inventory.migration.manage') WHERE r.code='OPERATOR';
