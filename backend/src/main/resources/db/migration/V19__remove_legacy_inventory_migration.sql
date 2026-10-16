-- Remove the test-only legacy SKU inventory migration feature and its rehearsal data.
DROP TABLE IF EXISTS legacy_inventory_migration_references;
DROP TABLE IF EXISTS legacy_inventory_migration_movements;
DROP TABLE IF EXISTS legacy_inventory_migration_items;
DROP TABLE IF EXISTS legacy_inventory_migration_mappings;
DROP TABLE IF EXISTS legacy_inventory_migration_batches;

DELETE rp FROM role_permissions rp
JOIN permissions p ON p.id=rp.permission_id
WHERE p.code IN (
  'inventory.migration.view',
  'button.inventory.migration.manage',
  'button.inventory.migration.execute',
  'menu.inventory'
);
DELETE FROM permissions
WHERE code IN (
  'inventory.migration.view',
  'button.inventory.migration.manage',
  'button.inventory.migration.execute',
  'menu.inventory'
);

-- These legacy SKU records were test-only. Goods-based purchase and stock rows use
-- sku_id = NULL and remain intact.
DELETE fs FROM fulfillment_shortages fs
WHERE EXISTS (SELECT 1 FROM purchase_order_items poi WHERE poi.id=fs.purchase_order_item_id AND poi.sku_id IS NOT NULL)
   OR EXISTS (SELECT 1 FROM purchase_order_items poi WHERE poi.purchase_order_id=fs.purchase_order_id AND poi.sku_id IS NOT NULL);
DELETE FROM purchase_receipts WHERE sku_id IS NOT NULL;
DELETE FROM purchase_order_items WHERE sku_id IS NOT NULL;
DELETE po FROM purchase_orders po
WHERE NOT EXISTS (SELECT 1 FROM purchase_order_items poi WHERE poi.purchase_order_id=po.id);
DELETE FROM inventory_flows;
DELETE FROM inventory_stocks;
