INSERT IGNORE INTO permissions(code,name,type,path) VALUES
 ('warehouse.goods.receive','货品采购收货','API',NULL);
UPDATE permissions SET name='货品采购与确认' WHERE code='warehouse.goods.purchase';
INSERT IGNORE INTO role_permissions(role_id,permission_id)
 SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code='warehouse.goods.receive'
 WHERE r.code IN ('ADMIN','OPERATOR','WAREHOUSE');
INSERT IGNORE INTO role_permissions(role_id,permission_id)
 SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code='menu.purchases'
 WHERE r.code='WAREHOUSE';
