ALTER TABLE image_assets
  ADD COLUMN archive_status ENUM('NOT_REQUESTED','PENDING','PROCESSING','SUCCEEDED','FAILED') NOT NULL DEFAULT 'NOT_REQUESTED' AFTER height,
  ADD COLUMN archive_error VARCHAR(1000) NULL AFTER archive_status,
  ADD COLUMN archive_attempt_count INT NOT NULL DEFAULT 0 AFTER archive_error,
  ADD COLUMN archive_requested_by BIGINT NULL AFTER archive_attempt_count,
  ADD COLUMN archive_requested_at TIMESTAMP NULL AFTER archive_requested_by,
  ADD COLUMN archive_last_attempt_at TIMESTAMP NULL AFTER archive_requested_at,
  ADD COLUMN archive_completed_at TIMESTAMP NULL AFTER archive_last_attempt_at,
  ADD COLUMN thumbnail_object_key VARCHAR(512) NULL AFTER archive_completed_at,
  ADD COLUMN thumbnail_status ENUM('NOT_GENERATED','READY','FAILED') NOT NULL DEFAULT 'NOT_GENERATED' AFTER thumbnail_object_key,
  ADD COLUMN thumbnail_error VARCHAR(1000) NULL AFTER thumbnail_status,
  ADD COLUMN index_status ENUM('NOT_INDEXED','PENDING','INDEXED','FAILED') NOT NULL DEFAULT 'NOT_INDEXED' AFTER thumbnail_error,
  ADD COLUMN index_version VARCHAR(100) NULL AFTER index_status,
  ADD COLUMN index_error VARCHAR(1000) NULL AFTER index_version,
  ADD COLUMN index_requested_at TIMESTAMP NULL AFTER index_error,
  ADD COLUMN index_completed_at TIMESTAMP NULL AFTER index_requested_at,
  ADD INDEX idx_image_archive_queue (archive_status,archive_requested_at,id),
  ADD INDEX idx_image_index_queue (index_status,index_requested_at,id),
  ADD CONSTRAINT fk_image_assets_archive_requester FOREIGN KEY (archive_requested_by) REFERENCES users(id),
  ADD CONSTRAINT chk_image_archive_attempts CHECK (archive_attempt_count >= 0);

UPDATE image_assets
SET index_status='PENDING',index_requested_at=CURRENT_TIMESTAMP
WHERE source_type IN ('UPLOAD','ARCHIVED_URL') AND status='ACTIVE';
