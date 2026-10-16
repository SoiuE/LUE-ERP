CREATE TABLE warehouse_goods (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  goods_code VARCHAR(100) NOT NULL,
  goods_name VARCHAR(255) NOT NULL,
  specification VARCHAR(255) NULL,
  barcode VARCHAR(100) NULL,
  unit VARCHAR(32) NOT NULL,
  default_supplier_id BIGINT NULL,
  reference_purchase_price DECIMAL(18,4) NOT NULL DEFAULT 0.0000,
  status ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  note VARCHAR(1000) NULL,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  created_by BIGINT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_warehouse_goods_code (goods_code),
  INDEX idx_warehouse_goods_barcode (barcode),
  INDEX idx_warehouse_goods_status (status,is_deleted),
  CONSTRAINT fk_warehouse_goods_supplier FOREIGN KEY (default_supplier_id) REFERENCES suppliers(id),
  CONSTRAINT fk_warehouse_goods_creator FOREIGN KEY (created_by) REFERENCES users(id)
);

CREATE TABLE sku_composition_versions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  sku_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  status ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  effective_from DATE NULL,
  effective_to DATE NULL,
  change_reason VARCHAR(1000) NOT NULL,
  created_by BIGINT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_sku_composition_version (sku_id,version_no),
  INDEX idx_sku_composition_effective (sku_id,status,effective_from,effective_to),
  CONSTRAINT fk_sku_composition_sku FOREIGN KEY (sku_id) REFERENCES skus(id),
  CONSTRAINT fk_sku_composition_creator FOREIGN KEY (created_by) REFERENCES users(id),
  CONSTRAINT chk_sku_composition_dates CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to >= effective_from)
);

CREATE TABLE sku_composition_items (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  version_id BIGINT NOT NULL,
  warehouse_goods_id BIGINT NOT NULL,
  quantity DECIMAL(18,4) NOT NULL,
  substitute_for_item_id BIGINT NULL,
  note VARCHAR(500) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_sku_composition_items_version (version_id),
  INDEX idx_sku_composition_items_goods (warehouse_goods_id),
  CONSTRAINT fk_sku_composition_item_version FOREIGN KEY (version_id) REFERENCES sku_composition_versions(id),
  CONSTRAINT fk_sku_composition_item_goods FOREIGN KEY (warehouse_goods_id) REFERENCES warehouse_goods(id),
  CONSTRAINT fk_sku_composition_item_substitute FOREIGN KEY (substitute_for_item_id) REFERENCES sku_composition_items(id),
  CONSTRAINT chk_sku_composition_quantity CHECK (quantity > 0)
);

INSERT IGNORE INTO permissions(code,name,type,path) VALUES
 ('master.goods.view','仓库货品档案','MENU','/warehouse-goods'),
 ('master.compositions.view','销售 SKU 组成清单','API',NULL),
 ('button.goods.manage','维护仓库货品','BUTTON',NULL),
 ('button.compositions.manage','维护组成清单版本','BUTTON',NULL);
INSERT IGNORE INTO role_permissions(role_id,permission_id)
 SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('master.goods.view','master.compositions.view','button.goods.manage','button.compositions.manage') WHERE r.code IN ('ADMIN','OPERATOR');
