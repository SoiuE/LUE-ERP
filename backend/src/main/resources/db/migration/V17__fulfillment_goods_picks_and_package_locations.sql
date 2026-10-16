-- Local fulfillment only. Platform import history, sales and profit tables are unchanged.
ALTER TABLE fulfillment_document_items ADD composition_version_id BIGINT NULL,
  ADD CONSTRAINT fk_fulfillment_item_composition FOREIGN KEY (composition_version_id) REFERENCES sku_composition_versions(id);
ALTER TABLE fulfillment_dispatch_items ADD composition_version_id BIGINT NULL,
  ADD CONSTRAINT fk_dispatch_item_composition FOREIGN KEY (composition_version_id) REFERENCES sku_composition_versions(id);

CREATE TABLE fulfillment_goods_tasks (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  dispatch_item_id BIGINT NOT NULL,
  warehouse_goods_id BIGINT NOT NULL,
  units_per_sku DECIMAL(18,4) NOT NULL,
  planned_qty DECIMAL(18,4) NOT NULL,
  picked_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
  shipped_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
  UNIQUE KEY uk_dispatch_goods_task (dispatch_item_id,warehouse_goods_id),
  CONSTRAINT fk_goods_task_dispatch_item FOREIGN KEY (dispatch_item_id) REFERENCES fulfillment_dispatch_items(id),
  CONSTRAINT fk_goods_task_good FOREIGN KEY (warehouse_goods_id) REFERENCES warehouse_goods(id),
  CONSTRAINT chk_goods_task_units CHECK (units_per_sku>0 AND planned_qty>0)
);

CREATE TABLE fulfillment_goods_picks (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  warehouse_id BIGINT NOT NULL,
  location_id BIGINT NOT NULL,
  picked_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
  shipped_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_goods_pick_task_location (task_id,location_id),
  CONSTRAINT fk_goods_pick_task FOREIGN KEY (task_id) REFERENCES fulfillment_goods_tasks(id),
  CONSTRAINT fk_goods_pick_location FOREIGN KEY (warehouse_id,location_id) REFERENCES warehouse_locations(warehouse_id,id)
);

ALTER TABLE warehouse_goods_stock_documents
  MODIFY document_type ENUM('RECEIPT','PUTAWAY','MOVE','OUTBOUND','FULFILLMENT_OUTBOUND') NOT NULL,
  ADD fulfillment_dispatch_id BIGINT NULL,
  ADD fulfillment_document_item_id BIGINT NULL,
  ADD CONSTRAINT fk_goods_stock_fulfillment_dispatch FOREIGN KEY (fulfillment_dispatch_id) REFERENCES fulfillment_dispatches(id),
  ADD CONSTRAINT fk_goods_stock_fulfillment_item FOREIGN KEY (fulfillment_document_item_id) REFERENCES fulfillment_document_items(id);
ALTER TABLE warehouse_goods_stock_flows
  MODIFY flow_type ENUM('RECEIPT','PUTAWAY_OUT','PUTAWAY_IN','MOVE_OUT','MOVE_IN','OUTBOUND','FULFILLMENT_OUTBOUND') NOT NULL;
ALTER TABLE warehouse_goods_costs DROP CHECK chk_goods_cost_quantity, DROP CHECK chk_goods_cost_total;
CREATE TABLE warehouse_goods_negative_cost_variances (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  purchase_receipt_id BIGINT NOT NULL,
  warehouse_id BIGINT NOT NULL,
  warehouse_goods_id BIGINT NOT NULL,
  covered_negative_qty DECIMAL(18,4) NOT NULL,
  variance_amount DECIMAL(24,4) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_negative_variance_receipt (purchase_receipt_id),
  CONSTRAINT fk_negative_variance_receipt FOREIGN KEY (purchase_receipt_id) REFERENCES purchase_receipts(id),
  CONSTRAINT fk_negative_variance_goods FOREIGN KEY (warehouse_goods_id) REFERENCES warehouse_goods(id),
  CONSTRAINT fk_negative_variance_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(id)
);
CREATE TABLE fulfillment_goods_outbounds (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  pick_id BIGINT NOT NULL,
  stock_document_id BIGINT NOT NULL,
  quantity DECIMAL(18,4) NOT NULL,
  unit_cost DECIMAL(18,6) NOT NULL,
  cost_amount DECIMAL(24,4) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_fulfillment_outbound_document (stock_document_id),
  CONSTRAINT fk_fulfillment_outbound_pick FOREIGN KEY (pick_id) REFERENCES fulfillment_goods_picks(id),
  CONSTRAINT fk_fulfillment_outbound_document FOREIGN KEY (stock_document_id) REFERENCES warehouse_goods_stock_documents(id)
);

CREATE TABLE package_location_balances (
  warehouse_id BIGINT NOT NULL, material_id BIGINT NOT NULL, location_id BIGINT NOT NULL,
  available_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (warehouse_id,material_id,location_id),
  CONSTRAINT fk_package_location_material FOREIGN KEY (material_id) REFERENCES package_materials(id),
  CONSTRAINT fk_package_location_bin FOREIGN KEY (warehouse_id,location_id) REFERENCES warehouse_locations(warehouse_id,id)
);
CREATE TABLE package_location_costs (
  warehouse_id BIGINT NOT NULL, material_id BIGINT NOT NULL,
  located_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
  total_cost DECIMAL(24,4) NOT NULL DEFAULT 0,
  average_unit_cost DECIMAL(18,6) NOT NULL DEFAULT 0,
  PRIMARY KEY (warehouse_id,material_id),
  CONSTRAINT fk_package_cost_material FOREIGN KEY (material_id) REFERENCES package_materials(id),
  CONSTRAINT fk_package_cost_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(id)
);
CREATE TABLE package_location_documents (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  document_type ENUM('POSITION','FULFILLMENT_CONSUMPTION') NOT NULL,
  idempotency_key VARCHAR(100) NULL,
  warehouse_id BIGINT NOT NULL, material_id BIGINT NOT NULL,
  location_id BIGINT NOT NULL,
  fulfillment_dispatch_id BIGINT NULL,
  fulfillment_document_item_id BIGINT NULL,
  quantity DECIMAL(18,4) NOT NULL,
  unit_cost_snapshot DECIMAL(18,6) NOT NULL,
  cost_amount_snapshot DECIMAL(24,4) NOT NULL,
  cost_source VARCHAR(100) NULL,
  reason VARCHAR(500) NULL,
  operator_id BIGINT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_package_position_key (document_type,idempotency_key),
  KEY idx_package_fulfillment (fulfillment_dispatch_id,fulfillment_document_item_id),
  CONSTRAINT fk_package_doc_material FOREIGN KEY (material_id) REFERENCES package_materials(id),
  CONSTRAINT fk_package_doc_location FOREIGN KEY (warehouse_id,location_id) REFERENCES warehouse_locations(warehouse_id,id),
  CONSTRAINT fk_package_doc_dispatch FOREIGN KEY (fulfillment_dispatch_id) REFERENCES fulfillment_dispatches(id),
  CONSTRAINT fk_package_doc_item FOREIGN KEY (fulfillment_document_item_id) REFERENCES fulfillment_document_items(id),
  CONSTRAINT fk_package_doc_operator FOREIGN KEY (operator_id) REFERENCES users(id)
);
CREATE TABLE package_location_flows (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  document_id BIGINT NOT NULL,
  warehouse_id BIGINT NOT NULL, material_id BIGINT NOT NULL, location_id BIGINT NOT NULL,
  flow_type ENUM('POSITION_IN','FULFILLMENT_OUT') NOT NULL,
  quantity_delta DECIMAL(18,4) NOT NULL,
  quantity_before DECIMAL(18,4) NOT NULL,
  quantity_after DECIMAL(18,4) NOT NULL,
  unit_cost_snapshot DECIMAL(18,6) NOT NULL,
  cost_amount_snapshot DECIMAL(24,4) NOT NULL,
  operator_id BIGINT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_package_location_flow (warehouse_id,material_id,location_id,created_at),
  CONSTRAINT fk_package_flow_document FOREIGN KEY (document_id) REFERENCES package_location_documents(id),
  CONSTRAINT fk_package_flow_location FOREIGN KEY (warehouse_id,location_id) REFERENCES warehouse_locations(warehouse_id,id),
  CONSTRAINT fk_package_flow_operator FOREIGN KEY (operator_id) REFERENCES users(id)
);
CREATE TABLE fulfillment_package_usage_decisions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  dispatch_item_id BIGINT NOT NULL,
  material_id BIGINT NOT NULL,
  suggested_qty DECIMAL(18,4) NOT NULL,
  actual_qty DECIMAL(18,4) NOT NULL,
  override_reason VARCHAR(500) NULL,
  operator_id BIGINT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_package_usage_item_material (dispatch_item_id,material_id),
  CONSTRAINT fk_package_usage_item FOREIGN KEY (dispatch_item_id) REFERENCES fulfillment_dispatch_items(id),
  CONSTRAINT fk_package_usage_material FOREIGN KEY (material_id) REFERENCES package_materials(id),
  CONSTRAINT fk_package_usage_operator FOREIGN KEY (operator_id) REFERENCES users(id)
);

INSERT IGNORE INTO permissions(code,name,type,path) VALUES
 ('button.fulfillment.package.position','包材库位定位及期初成本确认','BUTTON',NULL);
INSERT IGNORE INTO role_permissions(role_id,permission_id)
 SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code='button.fulfillment.package.position' WHERE r.code IN ('ADMIN','OPERATOR');
