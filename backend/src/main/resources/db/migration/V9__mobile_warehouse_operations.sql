ALTER TABLE inventory_flows
  MODIFY COLUMN flow_type ENUM('PURCHASE_CONFIRM','PURCHASE_INBOUND','MANUAL_INBOUND','MANUAL_OUTBOUND','JIT_OUTBOUND','STOCKING_OUTBOUND','STOCK_COUNT','ADJUSTMENT') NOT NULL;

ALTER TABLE package_flows
  MODIFY COLUMN flow_type ENUM('MANUAL_INBOUND','MANUAL_OUTBOUND','JIT_CONSUMPTION','STOCK_COUNT','ADJUSTMENT') NOT NULL;

CREATE TABLE warehouse_operation_attachments (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  operation_log_id BIGINT NULL,
  uploaded_by BIGINT NOT NULL,
  original_name VARCHAR(255) NOT NULL,
  stored_file_name VARCHAR(100) NOT NULL,
  content_type VARCHAR(100) NOT NULL,
  file_size BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_warehouse_attachments_log (operation_log_id),
  INDEX idx_warehouse_attachments_user_time (uploaded_by,created_at),
  CONSTRAINT fk_warehouse_attachments_log FOREIGN KEY (operation_log_id) REFERENCES operation_logs(id),
  CONSTRAINT fk_warehouse_attachments_user FOREIGN KEY (uploaded_by) REFERENCES users(id)
);

INSERT IGNORE INTO permissions(code,name,type,path) VALUES ('menu.mobile','手机仓库','MENU','/mobile');
