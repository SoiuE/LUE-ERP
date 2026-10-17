# 图片功能阶段 2 验收记录

日期：2026-10-17
范围：PC 图片库、SKU 与仓库货品多图维护、预览、`image_url` 导入兼容
依据：`docs/image-function-requirements.md`、`docs/image-function-stage0-inventory.md`、`docs/image-function-stage1-acceptance.md`

## 结论

阶段 2 的代码实现、前端类型检查与构建、后端编译与可运行的自动化测试已完成。数据库迁移、真实 MinIO 读写、浏览器端操作和不同账号的运行环境权限验收尚未执行，不能据此宣称端到端验收通过。阶段 1 中已存在的运行环境限制仍适用。

## 实现与检查

| 验收项 | 代码结果与证据 |
| --- | --- |
| 图片库入口与权限 | `/images` 路由和侧栏入口使用 `menu.images`；维护按钮使用 `button.images.manage`。图片 API 自身继续执行服务端权限校验。见 `frontend/src/router.ts`、`frontend/src/views/AdminLayout.vue`、`backend/src/main/java/com/lue/erp/images/ImageAssetController.java`。 |
| 筛选、分页和网格 | 按对象编码/名称关键词、对象类型、用途、标签、状态查询；分页展示图片与关联对象编码/名称。服务端对象列表按主图、顺序排序。见 `frontend/src/components/ImageManager.vue`、`backend/src/main/java/com/lue/erp/images/ImageAssetService.java`。 |
| 图片维护 | 通过阶段 1 API 上传、登记外链、关联已有资产、修改关联用途/标签/备注/主图/顺序、解除关联和停用。外链替换通过创建新资产、建立新关联、解除旧关联实现。见 `frontend/src/components/ImageManager.vue`。 |
| SKU 与货品多图 | SKU 详情新增图片页签；仓库货品列表增加多图维护对话框。编码、名称、规格等原有文本继续显示。SKU 主数据表单不再把单一 URL 输入框作为多图维护入口。见 `frontend/src/views/SkuProductDetailsView.vue`、`frontend/src/views/WarehouseGoodsView.vue`、`frontend/src/views/MasterDataView.vue`。 |
| 预览和失败处理 | 网格懒加载，图片查看器可关闭并切换前后图片；外链失败显示占位，维护入口仍可使用。上传图通过登录态读取新增的 320 像素缩略图端点，大图预览读取原图端点。见 `frontend/src/components/ImageAssetPicture.vue`、`backend/src/main/java/com/lue/erp/images/ImageAssetController.java`。 |
| `image_url` 导入 | 审计确认现有 SKU 导入在新建或强制重导入时仍按原字段集合写入 `skus.image_url`；阶段 1 将合法链接创建或复用为 `EXTERNAL_URL` 资产，重复关联复用已有活动关联。未调整其他 SKU 字段的导入写入规则。见 `backend/src/main/java/com/lue/erp/imports/ImportCenterService.java`。 |
| 现场附件边界 | 未修改仓库操作附件 API、表或采购与拣货流程；现场附件未纳入商品图片库。 |

检查阶段 1 查询代码时发现关键词条件缺少一个右括号，已在 `ImageAssetService` 中修复。变更范围还包括图片关联结果中的对象编码和名称，便于图片库识别。

## 验证命令与真实结果

- `cd frontend && npm run build`：通过，包含 `vue-tsc -b` 与 Vite 生产构建。Vite 提示主包体积超过 500 kB；这是构建警告。
- `cd backend && mvn -o "-Dmaven.repo.local=target/m2-cache" "-DforkCount=0" test`：`BUILD SUCCESS`。25 项测试中 5 项通过、0 项失败、20 项因缺少隔离数据库环境变量而跳过；该命令完成后端源码编译。
- `git diff --check`：通过。
- `cd backend && mvn -o "-Dmaven.repo.local=target/m2-cache" "-DskipTests" package`：失败；离线 Maven 缓存缺少 `maven-jar-plugin:3.4.2`，因此没有取得 JAR 打包成功结果。

## 尚需运行环境验收与限制

1. 在核对数据库目标后应用阶段 1 的 V22 迁移，用具备和不具备图片权限的账号核验上传、外链、筛选分页、受控读取、主图唯一性、排序、解除关联和停用；确认真实 MinIO 上传及原图/缩略图读取。
2. 使用 SKU Excel 新建和强制重导入，检查 `image_url` 显示、重复 URL 关联和其他 SKU 字段写入结果。当前测试没有连接数据库，不能将源码检查等同于运行结果。
3. 外链不由服务端抓取；浏览器网格虽然懒加载，但外链仍直接加载其原始 URL，无法保证外链传输的是缩略尺寸。上传图已使用缩略图端点。失效、禁止热链或跨站策略导致的加载失败会显示占位，需在浏览器实际环境复核。
4. 图片资产创建与关联是两次阶段 1 API 调用；若第二次失败，资产可能保持未关联，可在图片库中再次关联。未新增跨接口事务或外链归档服务。

本轮未实施以图搜图，未改仓库拣货、采购或手机业务流程。
