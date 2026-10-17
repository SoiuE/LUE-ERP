ALTER TABLE image_assets
  MODIFY COLUMN index_status ENUM('NOT_INDEXED','PENDING','PROCESSING','INDEXED','FAILED') NOT NULL DEFAULT 'NOT_INDEXED',
  ADD COLUMN index_attempt_count INT NOT NULL DEFAULT 0 AFTER index_error,
  ADD COLUMN index_last_attempt_at TIMESTAMP NULL AFTER index_attempt_count,
  ADD CONSTRAINT chk_image_index_attempts CHECK (index_attempt_count >= 0);

INSERT IGNORE INTO permissions(code,name,type,path) VALUES
 ('menu.image-search','以图搜商品','MENU','/image-search'),
 ('button.image-search.manage','管理图片搜索索引','BUTTON',NULL);

INSERT IGNORE INTO role_permissions(role_id,permission_id)
 SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('menu.image-search','button.image-search.manage')
 WHERE r.code IN ('ADMIN','OPERATOR');
