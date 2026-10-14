CREATE TABLE purchase_orders (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  po_no VARCHAR(32) NOT NULL,
  supplier_id BIGINT NOT NULL,
  status ENUM('DRAFT','CONFIRMED','PARTIALLY_RECEIVED','RECEIVED','CLOSED') NOT NULL DEFAULT 'DRAFT',
  expected_arrival_date DATE NULL,
  note VARCHAR(1000) NULL,
  created_by BIGINT NULL,
  confirmed_by BIGINT NULL,
  confirmed_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_purchase_orders_po_no (po_no),
  INDEX idx_purchase_orders_supplier_status (supplier_id,status,created_at),
  CONSTRAINT fk_purchase_orders_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(id)
);

CREATE TABLE purchase_order_items (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  purchase_order_id BIGINT NOT NULL,
  sku_id BIGINT NOT NULL,
  ordered_qty DECIMAL(18,4) NOT NULL,
  confirmed_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
  received_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
  unit_price DECIMAL(18,4) NOT NULL DEFAULT 0,
  total_amount DECIMAL(18,4) NOT NULL DEFAULT 0,
  note VARCHAR(500) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_purchase_order_items_order (purchase_order_id),
  INDEX idx_purchase_order_items_sku (sku_id),
  CONSTRAINT fk_purchase_order_items_order FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders(id),
  CONSTRAINT fk_purchase_order_items_sku FOREIGN KEY (sku_id) REFERENCES skus(id)
);

CREATE TABLE purchase_receipts (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  receipt_no VARCHAR(32) NOT NULL,
  purchase_order_id BIGINT NOT NULL,
  purchase_order_item_id BIGINT NOT NULL,
  sku_id BIGINT NOT NULL,
  received_qty DECIMAL(18,4) NOT NULL,
  over_receipt_reason VARCHAR(500) NULL,
  received_by BIGINT NULL,
  received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_purchase_receipts_no (receipt_no),
  INDEX idx_purchase_receipts_order (purchase_order_id,received_at),
  CONSTRAINT fk_purchase_receipts_order FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders(id),
  CONSTRAINT fk_purchase_receipts_item FOREIGN KEY (purchase_order_item_id) REFERENCES purchase_order_items(id),
  CONSTRAINT fk_purchase_receipts_sku FOREIGN KEY (sku_id) REFERENCES skus(id)
);

CREATE TABLE inventory_stocks (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  sku_id BIGINT NOT NULL,
  available_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
  locked_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
  pending_inbound_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
  pending_outbound_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
  abnormal_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_inventory_stocks_sku (sku_id),
  CONSTRAINT fk_inventory_stocks_sku FOREIGN KEY (sku_id) REFERENCES skus(id)
);

CREATE TABLE inventory_flows (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  sku_id BIGINT NOT NULL,
  flow_type ENUM('PURCHASE_CONFIRM','PURCHASE_INBOUND','MANUAL_INBOUND','MANUAL_OUTBOUND','STOCK_COUNT','ADJUSTMENT') NOT NULL,
  quantity_delta DECIMAL(18,4) NOT NULL DEFAULT 0,
  available_before DECIMAL(18,4) NOT NULL,
  available_after DECIMAL(18,4) NOT NULL,
  pending_inbound_before DECIMAL(18,4) NOT NULL DEFAULT 0,
  pending_inbound_after DECIMAL(18,4) NOT NULL DEFAULT 0,
  reference_type VARCHAR(32) NULL,
  reference_id BIGINT NULL,
  reason VARCHAR(500) NULL,
  operator_id BIGINT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_inventory_flows_sku_created (sku_id,created_at),
  INDEX idx_inventory_flows_reference (reference_type,reference_id),
  CONSTRAINT fk_inventory_flows_sku FOREIGN KEY (sku_id) REFERENCES skus(id)
);

CREATE TABLE package_stocks (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  material_id BIGINT NOT NULL,
  available_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_package_stocks_material (material_id),
  CONSTRAINT fk_package_stocks_material FOREIGN KEY (material_id) REFERENCES package_materials(id)
);

CREATE TABLE package_flows (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  material_id BIGINT NOT NULL,
  material_category ENUM('PRODUCT_PACKAGING','SHIPPING_MATERIAL') NOT NULL,
  flow_type ENUM('MANUAL_INBOUND','MANUAL_OUTBOUND','STOCK_COUNT','ADJUSTMENT') NOT NULL,
  quantity_delta DECIMAL(18,4) NOT NULL,
  available_before DECIMAL(18,4) NOT NULL,
  available_after DECIMAL(18,4) NOT NULL,
  reference_type VARCHAR(32) NULL,
  reference_id BIGINT NULL,
  reason VARCHAR(500) NULL,
  operator_id BIGINT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_package_flows_material_created (material_id,created_at),
  CONSTRAINT fk_package_flows_material FOREIGN KEY (material_id) REFERENCES package_materials(id)
);

INSERT IGNORE INTO permissions(code,name,type,path) VALUES
 ('menu.purchases','采购管理','MENU','/purchases'),
 ('menu.inventory','本地仓库存','MENU','/inventory'),
 ('menu.package-stocks','包材库存','MENU','/package-stocks');
INSERT IGNORE INTO role_permissions(role_id,permission_id)
 SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('menu.purchases','menu.inventory','menu.package-stocks') WHERE r.code IN ('ADMIN','OPERATOR');
