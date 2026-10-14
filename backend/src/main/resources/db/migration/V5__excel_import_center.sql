CREATE TABLE IF NOT EXISTS import_batch_sequences (
  sequence_date DATE PRIMARY KEY,
  sequence_value BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS import_batches (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  batch_no VARCHAR(32) NOT NULL,
  import_type VARCHAR(40) NOT NULL,
  original_filename VARCHAR(255) NOT NULL,
  stored_file_path VARCHAR(1000) NOT NULL,
  file_sha256 CHAR(64) NOT NULL,
  force_reimport BOOLEAN NOT NULL DEFAULT FALSE,
  status ENUM('PENDING','VALIDATING','FAILED','SUCCESS','PARTIAL_SUCCESS') NOT NULL DEFAULT 'PENDING',
  total_rows INT NOT NULL DEFAULT 0,
  valid_rows INT NOT NULL DEFAULT 0,
  error_rows INT NOT NULL DEFAULT 0,
  imported_rows INT NOT NULL DEFAULT 0,
  data_collect_task_id BIGINT NULL COMMENT 'TODO: link Excel import to data_collect_tasks when task center is implemented',
  error_message TEXT NULL,
  created_by BIGINT NULL,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  successful_file_hash CHAR(64) GENERATED ALWAYS AS (
    CASE WHEN status='SUCCESS' AND force_reimport=FALSE THEN file_sha256 ELSE NULL END
  ) STORED,
  UNIQUE KEY uk_import_batches_batch_no (batch_no),
  UNIQUE KEY uk_import_batches_successful_content (import_type, successful_file_hash),
  INDEX idx_import_batches_status_created (status, created_at),
  INDEX idx_import_batches_type_created (import_type, created_at),
  CONSTRAINT fk_import_batches_user FOREIGN KEY (created_by) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS raw_import_rows (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  batch_id BIGINT NOT NULL,
  source_row_number INT NOT NULL,
  raw_data JSON NOT NULL,
  normalized_data JSON NULL,
  validation_errors JSON NULL,
  row_status ENUM('VALID','ERROR','IMPORTED') NOT NULL DEFAULT 'VALID',
  imported_record_id BIGINT NULL,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_raw_import_rows_batch_row (batch_id, source_row_number),
  INDEX idx_raw_import_rows_status (batch_id, row_status),
  CONSTRAINT fk_raw_import_rows_batch FOREIGN KEY (batch_id) REFERENCES import_batches(id)
);
