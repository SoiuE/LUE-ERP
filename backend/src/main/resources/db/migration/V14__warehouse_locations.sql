-- Stage 2 is an empty, independent warehouse-goods location model. V1 SKU stock and flows are untouched.
CREATE TABLE warehouse_location_nodes (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  warehouse_id BIGINT NOT NULL,
  parent_id BIGINT NULL,
  node_kind ENUM('AREA','SHELF','LEVEL') NOT NULL,
  node_code VARCHAR(40) NOT NULL,
  node_name VARCHAR(120) NOT NULL,
  parent_key BIGINT GENERATED ALWAYS AS (COALESCE(parent_id,0)) STORED,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_location_node_sibling (warehouse_id,parent_key,node_kind,node_code),
  UNIQUE KEY uk_location_node_warehouse_id (warehouse_id,id),
  KEY idx_location_node_parent (warehouse_id,parent_id),
  CONSTRAINT fk_location_node_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(id),
  CONSTRAINT fk_location_node_parent FOREIGN KEY (warehouse_id,parent_id) REFERENCES warehouse_location_nodes(warehouse_id,id),
  CONSTRAINT chk_location_node_root CHECK ((node_kind='AREA' AND parent_id IS NULL) OR (node_kind<>'AREA' AND parent_id IS NOT NULL))
);

CREATE TABLE warehouse_locations (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  warehouse_id BIGINT NOT NULL,
  level_id BIGINT NOT NULL,
  location_code VARCHAR(80) NOT NULL,
  slot_code VARCHAR(40) NOT NULL,
  location_name VARCHAR(120) NULL,
  scan_code VARCHAR(100) NULL,
  location_type ENUM('STORAGE','PRIMARY_PICK','PENDING_PUTAWAY','TEMPORARY','QUARANTINE') NOT NULL,
  status ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  note VARCHAR(1000) NULL,
  created_by BIGINT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_location_warehouse_code (warehouse_id,location_code),
  UNIQUE KEY uk_location_level_slot (warehouse_id,level_id,slot_code),
  UNIQUE KEY uk_location_warehouse_scan (warehouse_id,scan_code),
  UNIQUE KEY uk_location_warehouse_id (warehouse_id,id),
  KEY idx_location_level (warehouse_id,level_id),
  CONSTRAINT fk_location_level FOREIGN KEY (warehouse_id,level_id) REFERENCES warehouse_location_nodes(warehouse_id,id),
  CONSTRAINT fk_location_creator FOREIGN KEY (created_by) REFERENCES users(id)
);

CREATE TABLE warehouse_goods_location_assignments (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  warehouse_id BIGINT NOT NULL,
  warehouse_goods_id BIGINT NOT NULL,
  location_id BIGINT NOT NULL,
  is_primary_pick BOOLEAN NOT NULL DEFAULT FALSE,
  status ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  active_primary_goods_id BIGINT GENERATED ALWAYS AS (CASE WHEN is_primary_pick=TRUE AND status='ACTIVE' THEN warehouse_goods_id ELSE NULL END) STORED,
  created_by BIGINT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_goods_location (warehouse_goods_id,location_id),
  UNIQUE KEY uk_goods_primary_per_warehouse (warehouse_id,active_primary_goods_id),
  KEY idx_assignment_warehouse_good (warehouse_id,warehouse_goods_id,status),
  CONSTRAINT fk_assignment_good FOREIGN KEY (warehouse_goods_id) REFERENCES warehouse_goods(id),
  CONSTRAINT fk_assignment_location FOREIGN KEY (warehouse_id,location_id) REFERENCES warehouse_locations(warehouse_id,id),
  CONSTRAINT fk_assignment_creator FOREIGN KEY (created_by) REFERENCES users(id)
);

-- NULL location_id is deliberately labelled HISTORICAL_UNLOCATED. No physical bin is invented.
-- Rows are not backfilled in this migration. Later stock operations must write business documents and flows.
CREATE TABLE warehouse_goods_location_balances (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  warehouse_id BIGINT NOT NULL,
  warehouse_goods_id BIGINT NOT NULL,
  location_id BIGINT NULL,
  position_status ENUM('LOCATED','HISTORICAL_UNLOCATED') NOT NULL,
  position_key BIGINT GENERATED ALWAYS AS (COALESCE(location_id,0)) STORED,
  available_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
  locked_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
  pending_putaway_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
  abnormal_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_goods_location_balance (warehouse_id,warehouse_goods_id,position_key),
  KEY idx_balance_location (warehouse_id,location_id),
  CONSTRAINT fk_balance_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(id),
  CONSTRAINT fk_balance_good FOREIGN KEY (warehouse_goods_id) REFERENCES warehouse_goods(id),
  CONSTRAINT fk_balance_location FOREIGN KEY (warehouse_id,location_id) REFERENCES warehouse_locations(warehouse_id,id),
  CONSTRAINT chk_balance_position CHECK ((position_status='HISTORICAL_UNLOCATED' AND location_id IS NULL) OR (position_status='LOCATED' AND location_id IS NOT NULL))
);

-- Only tasks with an explicit bin reference belong here. Existing V1 dispatches have no bin evidence.
CREATE TABLE warehouse_location_task_claims (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  warehouse_id BIGINT NOT NULL,
  location_id BIGINT NOT NULL,
  task_type VARCHAR(40) NOT NULL,
  source_type VARCHAR(40) NOT NULL,
  source_id BIGINT NOT NULL,
  status ENUM('OPEN','DONE','CANCELLED') NOT NULL DEFAULT 'OPEN',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_location_task_source (task_type,source_type,source_id,location_id),
  KEY idx_location_task_open (warehouse_id,location_id,status),
  CONSTRAINT fk_location_task_location FOREIGN KEY (warehouse_id,location_id) REFERENCES warehouse_locations(warehouse_id,id)
);

INSERT IGNORE INTO permissions(code,name,type,path) VALUES
 ('warehouse.locations.view','仓库库位档案','MENU','/warehouse-locations'),
 ('button.locations.manage','维护仓库库位','BUTTON',NULL),
 ('warehouse.location_stock.view','货品库位库存查询','MENU','/location-inventory');
INSERT IGNORE INTO role_permissions(role_id,permission_id)
 SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('warehouse.locations.view','button.locations.manage','warehouse.location_stock.view') WHERE r.code IN ('ADMIN','OPERATOR');
INSERT IGNORE INTO role_permissions(role_id,permission_id)
 SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('warehouse.locations.view','warehouse.location_stock.view') WHERE r.code='WAREHOUSE';
