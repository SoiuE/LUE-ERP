ALTER TABLE purchase_orders
  ADD COLUMN tracking_no VARCHAR(128) NULL AFTER po_no,
  ADD COLUMN order_no VARCHAR(128) NULL AFTER tracking_no,
  ADD COLUMN is_abnormal BOOLEAN NOT NULL DEFAULT FALSE AFTER note,
  ADD COLUMN abnormal_reason VARCHAR(1000) NULL AFTER is_abnormal,
  ADD COLUMN abnormal_marked_by BIGINT NULL AFTER abnormal_reason,
  ADD COLUMN abnormal_marked_at TIMESTAMP NULL AFTER abnormal_marked_by,
  ADD COLUMN abnormal_handled_by BIGINT NULL AFTER abnormal_marked_at,
  ADD COLUMN abnormal_handled_at TIMESTAMP NULL AFTER abnormal_handled_by,
  ADD COLUMN abnormal_handling_note VARCHAR(1000) NULL AFTER abnormal_handled_at,
  ADD INDEX idx_purchase_orders_tracking_no (tracking_no),
  ADD INDEX idx_purchase_orders_order_no (order_no),
  ADD INDEX idx_purchase_orders_abnormal (is_abnormal,status);
