-- V2 goods purchases coexist with historical SKU purchases. No data is copied here.
ALTER TABLE purchase_order_items MODIFY sku_id BIGINT NULL;
ALTER TABLE purchase_order_items ADD warehouse_goods_id BIGINT NULL AFTER sku_id,
  ADD CONSTRAINT fk_purchase_item_goods FOREIGN KEY (warehouse_goods_id) REFERENCES warehouse_goods(id),
  ADD CONSTRAINT chk_purchase_item_object CHECK ((sku_id IS NULL) <> (warehouse_goods_id IS NULL));
ALTER TABLE purchase_receipts MODIFY sku_id BIGINT NULL;
ALTER TABLE purchase_receipts ADD warehouse_goods_id BIGINT NULL AFTER sku_id,
  ADD warehouse_id BIGINT NULL AFTER warehouse_goods_id,
  ADD actual_unit_price DECIMAL(18,4) NULL AFTER received_qty,
  ADD CONSTRAINT fk_purchase_receipt_goods FOREIGN KEY (warehouse_goods_id) REFERENCES warehouse_goods(id),
  ADD CONSTRAINT fk_purchase_receipt_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(id),
  ADD CONSTRAINT chk_purchase_receipt_object CHECK ((sku_id IS NULL) <> (warehouse_goods_id IS NULL));
ALTER TABLE fulfillment_shortages ADD required_goods_qty DECIMAL(18,4) NULL AFTER shortage_qty;

CREATE TABLE goods_purchase_commands (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, command_key VARCHAR(100) NOT NULL,
  command_type ENUM('CREATE','CONFIRM','RECEIVE','PUTAWAY','MOVE','OUTBOUND') NOT NULL,
  request_hash CHAR(64) NOT NULL, result_id BIGINT NOT NULL,
  result_json JSON NOT NULL, created_by BIGINT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_goods_command (command_type,command_key),
  CONSTRAINT fk_goods_command_user FOREIGN KEY (created_by) REFERENCES users(id)
);

CREATE TABLE warehouse_goods_costs (
  warehouse_id BIGINT NOT NULL, warehouse_goods_id BIGINT NOT NULL,
  quantity DECIMAL(18,4) NOT NULL DEFAULT 0,
  total_cost DECIMAL(24,4) NOT NULL DEFAULT 0,
  average_unit_cost DECIMAL(18,6) NOT NULL DEFAULT 0,
  cost_status ENUM('ACTUAL','UNVERIFIED') NOT NULL DEFAULT 'ACTUAL',
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (warehouse_id,warehouse_goods_id),
  CONSTRAINT fk_goods_cost_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(id),
  CONSTRAINT fk_goods_cost_good FOREIGN KEY (warehouse_goods_id) REFERENCES warehouse_goods(id),
  CONSTRAINT chk_goods_cost_quantity CHECK (quantity>=0),
  CONSTRAINT chk_goods_cost_total CHECK (total_cost>=0)
);

CREATE TABLE warehouse_goods_cost_layers (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  warehouse_id BIGINT NOT NULL, warehouse_goods_id BIGINT NOT NULL,
  purchase_receipt_id BIGINT NOT NULL,
  quantity DECIMAL(18,4) NOT NULL, actual_unit_price DECIMAL(18,4) NOT NULL,
  amount DECIMAL(24,4) NOT NULL,
  average_before DECIMAL(18,6) NOT NULL, average_after DECIMAL(18,6) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_goods_cost_receipt (purchase_receipt_id),
  CONSTRAINT fk_goods_layer_receipt FOREIGN KEY (purchase_receipt_id) REFERENCES purchase_receipts(id),
  CONSTRAINT fk_goods_layer_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(id),
  CONSTRAINT fk_goods_layer_good FOREIGN KEY (warehouse_goods_id) REFERENCES warehouse_goods(id)
);

CREATE TABLE warehouse_goods_stock_documents (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  document_type ENUM('RECEIPT','PUTAWAY','MOVE','OUTBOUND') NOT NULL,
  warehouse_id BIGINT NOT NULL, warehouse_goods_id BIGINT NOT NULL,
  purchase_receipt_id BIGINT NULL,
  source_location_id BIGINT NULL, target_location_id BIGINT NULL,
  reason VARCHAR(500) NULL,
  quantity DECIMAL(18,4) NOT NULL,
  unit_cost_snapshot DECIMAL(18,6) NOT NULL,
  cost_amount_snapshot DECIMAL(24,4) NOT NULL,
  operator_id BIGINT NOT NULL, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_goods_document_receipt (purchase_receipt_id),
  CONSTRAINT fk_goods_document_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(id),
  CONSTRAINT fk_goods_document_good FOREIGN KEY (warehouse_goods_id) REFERENCES warehouse_goods(id),
  CONSTRAINT fk_goods_document_receipt FOREIGN KEY (purchase_receipt_id) REFERENCES purchase_receipts(id),
  CONSTRAINT fk_goods_document_source FOREIGN KEY (warehouse_id,source_location_id) REFERENCES warehouse_locations(warehouse_id,id),
  CONSTRAINT fk_goods_document_target FOREIGN KEY (warehouse_id,target_location_id) REFERENCES warehouse_locations(warehouse_id,id),
  CONSTRAINT fk_goods_document_operator FOREIGN KEY (operator_id) REFERENCES users(id),
  CONSTRAINT chk_goods_document_qty CHECK (quantity>0)
);

CREATE TABLE warehouse_goods_stock_flows (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  document_id BIGINT NOT NULL, warehouse_id BIGINT NOT NULL,
  warehouse_goods_id BIGINT NOT NULL, location_id BIGINT NOT NULL,
  flow_type ENUM('RECEIPT','PUTAWAY_OUT','PUTAWAY_IN','MOVE_OUT','MOVE_IN','OUTBOUND') NOT NULL,
  available_delta DECIMAL(18,4) NOT NULL DEFAULT 0,
  pending_putaway_delta DECIMAL(18,4) NOT NULL DEFAULT 0,
  available_before DECIMAL(18,4) NOT NULL, available_after DECIMAL(18,4) NOT NULL,
  pending_before DECIMAL(18,4) NOT NULL, pending_after DECIMAL(18,4) NOT NULL,
  unit_cost_snapshot DECIMAL(18,6) NOT NULL,
  cost_amount_snapshot DECIMAL(24,4) NOT NULL,
  operator_id BIGINT NOT NULL, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_goods_flow_lookup (warehouse_id,warehouse_goods_id,location_id,created_at),
  CONSTRAINT fk_goods_flow_document FOREIGN KEY (document_id) REFERENCES warehouse_goods_stock_documents(id),
  CONSTRAINT fk_goods_flow_location FOREIGN KEY (warehouse_id,location_id) REFERENCES warehouse_locations(warehouse_id,id),
  CONSTRAINT fk_goods_flow_good FOREIGN KEY (warehouse_goods_id) REFERENCES warehouse_goods(id),
  CONSTRAINT fk_goods_flow_operator FOREIGN KEY (operator_id) REFERENCES users(id)
);

INSERT IGNORE INTO permissions(code,name,type,path) VALUES
 ('warehouse.goods.purchase','货品采购收货','API',NULL),
 ('warehouse.goods.putaway','货品上架','API',NULL),
 ('warehouse.goods.move','货品移库','API',NULL),
 ('warehouse.goods.outbound','货品出库','API',NULL);
INSERT IGNORE INTO role_permissions(role_id,permission_id)
 SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('warehouse.goods.purchase','warehouse.goods.putaway','warehouse.goods.move','warehouse.goods.outbound') WHERE r.code IN ('ADMIN','OPERATOR');
INSERT IGNORE INTO role_permissions(role_id,permission_id)
 SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('warehouse.goods.putaway','warehouse.goods.move') WHERE r.code='WAREHOUSE';
