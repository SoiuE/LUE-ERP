-- Keep the original version creation reason intact when a version is deactivated.
ALTER TABLE sku_composition_versions
  ADD COLUMN deactivation_reason VARCHAR(1000) NULL,
  ADD COLUMN deactivated_at TIMESTAMP NULL,
  ADD COLUMN deactivated_by BIGINT NULL,
  ADD CONSTRAINT fk_composition_deactivated_by FOREIGN KEY (deactivated_by) REFERENCES users(id);
