# Excel 导入模板

当前维护两种静态 `.xlsx` 模板，可在后台「导入中心」按导入类型下载：

- `sku_import_template.xlsx`：SKU 档案，必填列 `internal_sku`、`product_name`。
- `shop_sku_mapping_template.xlsx`：店铺 SKU 映射，必填列 `shop_identifier`、`platform_sku`、`internal_sku`。

模板主表首行使用中文字段名，第二行是示例；“填写说明”工作表列出必填项、格式限制和示例值。上传时中文表头会映射为后端字段，旧版英文表头也暂时兼容。模板由后端 `/api/import-batches/templates/{importType}` 生成，避免下载模板与校验规则漂移。后续新增模板时，在导入类型注册中增加中文表头、字段规则和事务写入器，并同步维护填写说明及预览字段中文名。

订单/销量模板由 `/api/orders/template` 动态生成，表头为中文；支持中文表头和旧版英文表头导入，包含 JIT/WAREHOUSE、人民币金额及日期填写说明。
