CREATE TABLE IF NOT EXISTS warehouses (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  warehouse_code VARCHAR(40) NOT NULL,
  warehouse_name VARCHAR(120) NOT NULL,
  is_default BOOLEAN NOT NULL DEFAULT FALSE,
  status ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_warehouses_code (warehouse_code)
);

INSERT IGNORE INTO warehouses(id,warehouse_code,warehouse_name,is_default) VALUES (1,'LOCAL-DEFAULT','默认本地仓',TRUE);

ALTER TABLE inventory_stocks DROP FOREIGN KEY fk_inventory_stocks_sku,
  DROP INDEX uk_inventory_stocks_sku,
  ADD COLUMN warehouse_id BIGINT NOT NULL DEFAULT 1 AFTER sku_id,
  ADD INDEX idx_inventory_stocks_sku (sku_id),
  ADD UNIQUE KEY uk_inventory_stocks_warehouse_sku (warehouse_id,sku_id),
  ADD CONSTRAINT fk_inventory_stocks_sku_v11 FOREIGN KEY (sku_id) REFERENCES skus(id),
  ADD CONSTRAINT fk_inventory_stocks_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(id);

ALTER TABLE package_stocks DROP FOREIGN KEY fk_package_stocks_material,
  DROP INDEX uk_package_stocks_material,
  ADD COLUMN warehouse_id BIGINT NOT NULL DEFAULT 1 AFTER material_id,
  ADD INDEX idx_package_stocks_material (material_id),
  ADD UNIQUE KEY uk_package_stocks_warehouse_material (warehouse_id,material_id),
  ADD CONSTRAINT fk_package_stocks_material_v11 FOREIGN KEY (material_id) REFERENCES package_materials(id),
  ADD CONSTRAINT fk_package_stocks_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(id);

ALTER TABLE inventory_flows ADD COLUMN warehouse_id BIGINT NOT NULL DEFAULT 1 AFTER sku_id,
  ADD CONSTRAINT fk_inventory_flows_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(id);
ALTER TABLE package_flows ADD COLUMN warehouse_id BIGINT NOT NULL DEFAULT 1 AFTER material_id,
  ADD CONSTRAINT fk_package_flows_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(id);
ALTER TABLE purchase_orders ADD COLUMN warehouse_id BIGINT NOT NULL DEFAULT 1 AFTER supplier_id,
  ADD CONSTRAINT fk_purchase_orders_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(id);

CREATE TABLE fulfillment_import_batches (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  batch_no VARCHAR(40) NOT NULL,
  document_type ENUM('JIT_ORDER','PLATFORM_WAREHOUSE_STOCKING') NOT NULL,
  original_filename VARCHAR(255) NOT NULL,
  stored_file_path VARCHAR(1000) NOT NULL,
  file_sha256 CHAR(64) NOT NULL,
  status ENUM('PREVIEWED','IMPORTED','PARTIAL','FAILED') NOT NULL DEFAULT 'PREVIEWED',
  total_rows INT NOT NULL DEFAULT 0,
  created_rows INT NOT NULL DEFAULT 0,
  updated_rows INT NOT NULL DEFAULT 0,
  duplicate_rows INT NOT NULL DEFAULT 0,
  unmatched_rows INT NOT NULL DEFAULT 0,
  error_rows INT NOT NULL DEFAULT 0,
  created_by BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  committed_at TIMESTAMP NULL,
  UNIQUE KEY uk_fulfillment_import_batch_no (batch_no),
  INDEX idx_fulfillment_import_hash (document_type,file_sha256),
  CONSTRAINT fk_fulfillment_import_user FOREIGN KEY (created_by) REFERENCES users(id)
);

CREATE TABLE fulfillment_documents (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  document_no VARCHAR(40) NOT NULL,
  document_type ENUM('JIT_ORDER','PLATFORM_WAREHOUSE_STOCKING') NOT NULL,
  platform VARCHAR(40) NOT NULL DEFAULT 'ALIEXPRESS',
  shop_id BIGINT NULL,
  shop_identifier VARCHAR(160) NOT NULL,
  source_document_id VARCHAR(160) NOT NULL,
  source_status VARCHAR(80) NULL,
  group_batch_id VARCHAR(160) NULL,
  target_platform_warehouse VARCHAR(200) NULL,
  warehouse_id BIGINT NOT NULL DEFAULT 1,
  local_status ENUM('SKU_UNMATCHED','READY_TO_PICK','PICKING','SHORTAGE','PARTIALLY_SHIPPED','SHIPPED','CANCELLED','NEEDS_REVIEW') NOT NULL DEFAULT 'READY_TO_PICK',
  first_dispatch_warehouse_locked BOOLEAN NOT NULL DEFAULT FALSE,
  platform_updated_at DATETIME NULL,
  appointment_no VARCHAR(160) NULL,
  pickup_start DATETIME NULL,
  pickup_end DATETIME NULL,
  platform_snapshot JSON NULL,
  created_from_batch_id BIGINT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_fulfillment_source (document_type,platform,shop_identifier,source_document_id),
  UNIQUE KEY uk_fulfillment_document_no (document_no),
  INDEX idx_fulfillment_status (document_type,local_status,updated_at),
  INDEX idx_fulfillment_appointment (pickup_start,pickup_end),
  CONSTRAINT fk_fulfillment_shop FOREIGN KEY (shop_id) REFERENCES shops(id),
  CONSTRAINT fk_fulfillment_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(id),
  CONSTRAINT fk_fulfillment_batch FOREIGN KEY (created_from_batch_id) REFERENCES fulfillment_import_batches(id)
);

ALTER TABLE exceptions ADD COLUMN fulfillment_document_id BIGINT NULL AFTER order_summary_id,
  ADD CONSTRAINT fk_exceptions_fulfillment_document FOREIGN KEY (fulfillment_document_id) REFERENCES fulfillment_documents(id);

CREATE TABLE fulfillment_document_items (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  document_id BIGINT NOT NULL,
  source_line_id VARCHAR(160) NULL,
  source_line_key CHAR(64) NOT NULL,
  platform_sku_id VARCHAR(160) NULL,
  platform_sku VARCHAR(160) NOT NULL,
  sku_id BIGINT NULL,
  order_mode ENUM('JIT','WAREHOUSE') NOT NULL DEFAULT 'JIT',
  order_date DATE NULL,
  ordered_qty DECIMAL(18,4) NOT NULL,
  imported_state VARCHAR(80) NULL,
  platform_updated_at DATETIME NULL,
  raw_snapshot JSON NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_fulfillment_item_source (document_id,source_line_key),
  INDEX idx_fulfillment_item_sku (sku_id,platform_sku),
  CONSTRAINT fk_fulfillment_item_document FOREIGN KEY (document_id) REFERENCES fulfillment_documents(id),
  CONSTRAINT fk_fulfillment_item_sku FOREIGN KEY (sku_id) REFERENCES skus(id)
);

ALTER TABLE exceptions ADD COLUMN fulfillment_item_id BIGINT NULL AFTER fulfillment_document_id,
  ADD CONSTRAINT fk_exceptions_fulfillment_item FOREIGN KEY (fulfillment_item_id) REFERENCES fulfillment_document_items(id);

CREATE TABLE fulfillment_packages (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  document_id BIGINT NOT NULL,
  package_key CHAR(64) NOT NULL,
  group_batch_id VARCHAR(160) NULL,
  package_no VARCHAR(160) NULL,
  label_no VARCHAR(160) NULL,
  label_filename VARCHAR(255) NULL,
  appointment_no VARCHAR(160) NULL,
  pickup_start DATETIME NULL,
  pickup_end DATETIME NULL,
  platform_updated_at DATETIME NULL,
  platform_snapshot JSON NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_fulfillment_package_key (document_id,package_key),
  INDEX idx_fulfillment_package_appointment (appointment_no,pickup_start),
  CONSTRAINT fk_fulfillment_package_document FOREIGN KEY (document_id) REFERENCES fulfillment_documents(id)
);

CREATE TABLE fulfillment_package_items (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  package_id BIGINT NOT NULL,
  document_item_id BIGINT NOT NULL,
  packed_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
  UNIQUE KEY uk_fulfillment_package_item (package_id,document_item_id),
  CONSTRAINT fk_fulfillment_package_item_package FOREIGN KEY (package_id) REFERENCES fulfillment_packages(id),
  CONSTRAINT fk_fulfillment_package_item_line FOREIGN KEY (document_item_id) REFERENCES fulfillment_document_items(id)
);

CREATE TABLE fulfillment_import_rows (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  batch_id BIGINT NOT NULL,
  source_row_number INT NOT NULL,
  raw_data JSON NOT NULL,
  normalized_data JSON NOT NULL,
  validation_errors JSON NULL,
  row_status ENUM('READY','ERROR','UNMATCHED','CREATED','UPDATED','DUPLICATE') NOT NULL,
  document_id BIGINT NULL,
  item_id BIGINT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_fulfillment_import_row (batch_id,source_row_number),
  INDEX idx_fulfillment_import_row_status (batch_id,row_status),
  CONSTRAINT fk_fulfillment_import_row_batch FOREIGN KEY (batch_id) REFERENCES fulfillment_import_batches(id),
  CONSTRAINT fk_fulfillment_import_row_doc FOREIGN KEY (document_id) REFERENCES fulfillment_documents(id),
  CONSTRAINT fk_fulfillment_import_row_item FOREIGN KEY (item_id) REFERENCES fulfillment_document_items(id)
);

CREATE TABLE fulfillment_dispatches (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  dispatch_no VARCHAR(40) NOT NULL,
  document_id BIGINT NOT NULL,
  dispatch_sequence INT NOT NULL,
  idempotency_key VARCHAR(100) NOT NULL,
  warehouse_id BIGINT NOT NULL,
  status ENUM('PICKING','PICKED','HANDED_OFF','CANCELLED') NOT NULL DEFAULT 'PICKING',
  handoff_reference VARCHAR(160) NULL,
  note VARCHAR(1000) NULL,
  created_by BIGINT NOT NULL,
  handed_off_by BIGINT NULL,
  handed_off_at DATETIME NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_fulfillment_dispatch_no (dispatch_no),
  UNIQUE KEY uk_fulfillment_dispatch_idempotency (document_id,idempotency_key),
  UNIQUE KEY uk_fulfillment_dispatch_sequence (document_id,dispatch_sequence),
  INDEX idx_fulfillment_dispatch_status (status,created_at),
  CONSTRAINT fk_fulfillment_dispatch_document FOREIGN KEY (document_id) REFERENCES fulfillment_documents(id),
  CONSTRAINT fk_fulfillment_dispatch_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(id),
  CONSTRAINT fk_fulfillment_dispatch_creator FOREIGN KEY (created_by) REFERENCES users(id),
  CONSTRAINT fk_fulfillment_dispatch_handoff FOREIGN KEY (handed_off_by) REFERENCES users(id)
);

CREATE TABLE fulfillment_dispatch_items (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  dispatch_id BIGINT NOT NULL,
  document_item_id BIGINT NOT NULL,
  planned_qty DECIMAL(18,4) NOT NULL,
  picked_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
  shipped_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
  UNIQUE KEY uk_fulfillment_dispatch_line (dispatch_id,document_item_id),
  CONSTRAINT fk_fulfillment_dispatch_item_dispatch FOREIGN KEY (dispatch_id) REFERENCES fulfillment_dispatches(id),
  CONSTRAINT fk_fulfillment_dispatch_item_source FOREIGN KEY (document_item_id) REFERENCES fulfillment_document_items(id)
);

CREATE TABLE fulfillment_shortages (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  document_item_id BIGINT NOT NULL,
  shortage_qty DECIMAL(18,4) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  status ENUM('OPEN','PURCHASE_LINKED','RECEIVED','RESOLVED','CANCELLED') NOT NULL DEFAULT 'OPEN',
  purchase_order_id BIGINT NULL,
  purchase_order_item_id BIGINT NULL,
  created_by BIGINT NOT NULL,
  resolved_at DATETIME NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_fulfillment_shortage_status (status,created_at),
  CONSTRAINT fk_fulfillment_shortage_item FOREIGN KEY (document_item_id) REFERENCES fulfillment_document_items(id),
  CONSTRAINT fk_fulfillment_shortage_po FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders(id),
  CONSTRAINT fk_fulfillment_shortage_poi FOREIGN KEY (purchase_order_item_id) REFERENCES purchase_order_items(id),
  CONSTRAINT fk_fulfillment_shortage_user FOREIGN KEY (created_by) REFERENCES users(id)
);

ALTER TABLE inventory_flows MODIFY COLUMN flow_type ENUM('PURCHASE_CONFIRM','PURCHASE_INBOUND','MANUAL_INBOUND','MANUAL_OUTBOUND','JIT_OUTBOUND','STOCKING_OUTBOUND','STOCK_COUNT','ADJUSTMENT') NOT NULL;
ALTER TABLE package_flows MODIFY COLUMN flow_type ENUM('MANUAL_INBOUND','MANUAL_OUTBOUND','JIT_CONSUMPTION','STOCKING_CONSUMPTION','STOCK_COUNT','ADJUSTMENT') NOT NULL;

INSERT IGNORE INTO permissions(code,name,type,path) VALUES
 ('menu.fulfillment','本地履约','MENU','/fulfillment'),
 ('button.fulfillment.import','导入平台组包预约','BUTTON',NULL),
 ('button.fulfillment.operate','本地拣货与交接','BUTTON',NULL);
INSERT IGNORE INTO role_permissions(role_id,permission_id)
 SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('menu.fulfillment','button.fulfillment.import','button.fulfillment.operate') WHERE r.code IN ('ADMIN','OPERATOR');
INSERT IGNORE INTO role_permissions(role_id,permission_id)
 SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('menu.fulfillment','button.fulfillment.operate') WHERE r.code='WAREHOUSE';
