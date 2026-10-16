# V2.0：旧 SKU 库存迁移工具与隔离演练

日期：2026-10-15（Asia/Shanghai）

## 范围与现状

依据 `docs/requirements-v2.0.md`、`docs/ai-development-steps-v2.0.md`、阶段 1 和阶段 2 验收报告。仓库中没有单独保存的阶段 0 报告；阶段 1 报告记录了阶段 0 盘点仅在聊天中完成。V11 将既有库存的 `warehouse_id` 回填为默认仓 1，因此这个字段本身不证明历史实物位置。现有 V1 采购、履约、库存和利润仍以内部销售 SKU 为库存对象。

本次新增迁移工具及 V15 元数据表。没有在 `lue_erp` 业务库执行 V15 或库存迁移，也没有重启正在运行的业务后端。旧 `inventory_stocks`、`inventory_flows` 和利润行始终保留原主键和内容；新货品余额与旧 SKU 余额属于两个不同模型，不得跨模型相加。

## 迁移模型与口径

- `legacy_inventory_migration_batches`：一个源仓库一个批次，唯一幂等键、迁移版本、状态、创建/执行/冲销人和时间。
- `legacy_inventory_migration_mappings`：每个源库存行对应唯一目标货品，目标货品在同批次也必须唯一。映射由用户手工指定，再逐行确认当前数量、更新时间及旧流水计数/最大 ID；没有自动猜测。
- `legacy_inventory_migration_items`：保留源库存 ID、源 SKU ID、源仓库、目标货品、各数量和源流水 ID 范围；`legacy_inventory_migration_movements` 独立记 APPLY/REVERSE，不写入或改号 V1 流水。
- 可用、锁定和异常量落在 `warehouse_goods_location_balances` 的 `HISTORICAL_UNLOCATED` 行，`location_id=NULL`。待入、待出量落在新的仓级 `warehouse_goods_pending_balances`，不伪装为待上架位或实物库位。
- 旧 `skus.purchase_price` 仅保存为 `legacy_purchase_price_reference`，`cost_basis=LEGACY_SKU_REFERENCE_UNVERIFIED`。没有采购批次证据时不生成实际批次成本、成本层或利润重算。
- 预览列出未映射、目标冲突或已建余额、待处理/锁定/异常量负数、可用负数未说明、最后旧流水结存与当前可用量不一致、确认后源数据变化等阻止原因。可用负数或流水差异必须有人工原因并逐行确认；其他负量先调查源数据。
- 执行在一个数据库事务内重新锁定源行，复核全部确认快照和目标空白状态；批次重复提交返回已有对账结果。对账逐行比较仓库、旧 SKU/源库存 ID、目标货品的五类数量，再汇总比较；负数单独列示。操作日志失败则整批回滚。
- 冲销只处理该批次创建的目标余额与待处理量，写入 REVERSE 迁移记录并保留 APPLY、映射和源记录。若源库存/流水、目标余额或目标货品其他库位余额变化，或者 `legacy_inventory_migration_references` 中已有后续业务引用，则拒绝自动冲销，提示冻结作业、导出报告并走有原因的人工调整单。后续 V2 业务模块必须在使用迁移库存时写入引用表。
- 冲销后的零余额行及全部审计记录继续保留；再次迁往同一货品不会自动覆盖这些行，需要另行审批设计。已停用仓库仍可只读预览，但在当前同仓映射策略下不能提交。V11 的默认仓 1 若不代表真实仓库，须先取得业务证据并单独确认仓库转换方案，本工具不会猜测或跨仓迁移。

## API、页面和权限

页面 `/inventory-migration` 提供仓库及批次选择、只读预览、逐行映射/确认、隔离执行、结果对账、冲销和 JSON 报告导出。接口位于 `/api/inventory-migrations`：`GET /preview`、`/warehouses`、`/goods`、`/batches`、`/batches/{id}/report`、`/capabilities`；`POST /batches`；`PUT /batches/{id}/mappings/{stockId}`；`POST /batches/{id}/mappings/{stockId}/confirm`、`/batches/{id}/execute`、`/batches/{id}/rollback`。

`inventory.migration.view` 控制所有只读 API；`button.inventory.migration.manage` 控制批次/映射/确认；`button.inventory.migration.execute` 控制执行和冲销。ADMIN 拥有全部权限，OPERATOR 可准备映射，其他角色默认没有权限。后端逐接口校验，不依赖页面按钮隐藏。

写 API 还强制同时满足 `LUE_LEGACY_MIGRATION_REHEARSAL_ENABLED=true` 和数据库名匹配 `lue_inventory_migration_rehearsal_*`。当前业务库 `lue_erp` 不符合名称；默认开关为 `false`。预览和报告保持只读。本阶段没有生产执行开关。

## 隔离副本演练与上线审批

1. 在业务库之外获取一致性备份，恢复到独立 MySQL 主机/实例，使用与业务库隔离的凭据和网络，并将副本 schema 命名为 `lue_inventory_migration_rehearsal_<编号>`。核对恢复前后的旧库存行数、按仓库和 SKU 的五类数量、旧流水 ID 范围及利润行；保留备份和校验值。不要让业务应用连接该副本。
2. 仅在副本运行 Flyway V15，设置演练开关为 `true`，启动单独的后端。先导出无批次只读预览，再建立批次、人工映射和逐行确认。把未映射、同货品冲突、负库存、旧流水结存差异和默认仓 1 的真实仓库证据交给库存负责人处理；阻止项清零前不执行。
3. 执行后导出批次 JSON 对账报告。要求所有 `rowBalanced=true`、批次 `balanced=true`，逐仓库、源库存 ID/旧 SKU、目标货品的可用/锁定/待入/待出/异常量分别守恒，负数分项清楚；旧库存、旧流水、利润快照不变。再演练重复执行、无后续引用冲销及有后续引用时拒绝冲销。
4. 业务上线前由仓库负责人确认货品/仓库映射与负数原因，财务负责人确认历史价格只为未验证参考口径，技术负责人确认备份、并发冻结、对账报告、回滚条件和后续业务引用登记。审批留存批次键、迁移版本、演练报告及签字记录。
5. 本次代码没有生产执行权限。将来若获批生产上线，须另行发布受控的生产执行授权，安排 V1 库存写入冻结或切换；V1 采购与履约当前仍会写旧 SKU 库存，未切换前不能把新旧余额相加或将演练结果视为实时可用库存。生产步骤需在维护窗口按已签字映射执行并立即复核，失败时按事先批准的备份/人工调整方案处理。

## 已执行验证

- `mvn -o -DskipTests package`：后端打包通过；该命令按参数跳过测试。
- `npm run build`：前端 TypeScript/Vue/Vite 构建通过，有现存大包体积提示。
- 隔离 MySQL 8.0.44 `127.0.0.1:33482` 中，测试创建全新 `lue_inventory_migration_rehearsal_*` schema，先迁移至 V14，写入合成旧库存/流水/利润，再执行 V15 和 Flyway validate。
- `mvn -o -Dtest=LegacyInventoryMigrationAcceptanceTest test`：4 项测试通过，失败 0、错误 0。覆盖预览冲突与数量异常、逐行映射确认、同批次重复执行、五类数量守恒、仓级待处理量查询、冲销、后续引用/源变化拒绝、演练开关拒绝以及审计失败的事务回滚。合成 V1 样本迁移前后与测试结束后全字段快照相同。
- 未使用真实业务数据库副本演练，未进行真实浏览器交互，也未获得生产上线批准。
