CREATE TABLE image_assets (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  source_type ENUM('UPLOAD','EXTERNAL_URL','ARCHIVED_URL') NOT NULL,
  external_url VARCHAR(2048) NULL,
  source_url VARCHAR(2048) NULL,
  object_key VARCHAR(512) NULL,
  original_filename VARCHAR(255) NULL,
  content_type VARCHAR(100) NULL,
  file_size BIGINT NULL,
  width INT NULL,
  height INT NULL,
  status ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  created_by BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_image_assets_source_status (source_type,status),
  INDEX idx_image_assets_external_url (external_url(191)),
  INDEX idx_image_assets_created (created_at,id),
  CONSTRAINT fk_image_assets_creator FOREIGN KEY (created_by) REFERENCES users(id),
  CONSTRAINT chk_image_assets_storage CHECK (
    (source_type='EXTERNAL_URL' AND external_url IS NOT NULL AND object_key IS NULL) OR
    (source_type='UPLOAD' AND external_url IS NULL AND object_key IS NOT NULL) OR
    (source_type='ARCHIVED_URL' AND external_url IS NULL AND source_url IS NOT NULL AND object_key IS NOT NULL)
  ),
  CONSTRAINT chk_image_assets_dimensions CHECK (
    (width IS NULL AND height IS NULL) OR (width > 0 AND height > 0)
  ),
  CONSTRAINT chk_image_assets_size CHECK (file_size IS NULL OR file_size > 0)
);

CREATE TABLE image_asset_links (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  image_asset_id BIGINT NOT NULL,
  object_type ENUM('SKU','WAREHOUSE_GOODS') NOT NULL,
  object_id BIGINT NOT NULL,
  purpose ENUM('MAIN_IMAGE','PRODUCT_PHOTO','PACKAGING','WAREHOUSE_PHOTO','OTHER') NOT NULL DEFAULT 'OTHER',
  is_primary BOOLEAN NOT NULL DEFAULT FALSE,
  sort_order INT NOT NULL DEFAULT 0,
  tags JSON NULL,
  note VARCHAR(1000) NULL,
  status ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  primary_object_key VARCHAR(140) GENERATED ALWAYS AS (
    CASE WHEN is_primary=TRUE AND status='ACTIVE' THEN CONCAT(object_type,':',object_id) ELSE NULL END
  ) STORED,
  created_by BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_image_asset_object (image_asset_id,object_type,object_id),
  UNIQUE KEY uk_image_object_primary (primary_object_key),
  INDEX idx_image_links_object_sort (object_type,object_id,status,sort_order,id),
  INDEX idx_image_links_asset_status (image_asset_id,status),
  CONSTRAINT fk_image_links_asset FOREIGN KEY (image_asset_id) REFERENCES image_assets(id),
  CONSTRAINT fk_image_links_creator FOREIGN KEY (created_by) REFERENCES users(id),
  CONSTRAINT chk_image_links_sort_order CHECK (sort_order >= 0)
);

INSERT IGNORE INTO permissions(code,name,type,path) VALUES
 ('menu.images','商品图片库','MENU','/images'),
 ('button.images.manage','维护商品图片','BUTTON',NULL);
INSERT IGNORE INTO role_permissions(role_id,permission_id)
 SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('menu.images','button.images.manage') WHERE r.code IN ('ADMIN','OPERATOR');
