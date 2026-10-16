# V2.0 阶段 1：仓库货品与销售 SKU 组成版本验收

日期：2026-10-15（Asia/Shanghai）

## 基线与边界

依据 `docs/requirements-v2.0.md`、`docs/ai-development-steps-v2.0.md`、本聊天阶段 0 的代码调查及现有迁移实现。仓库中未发现另行保存的阶段 0 完整报告，不将提示词中的“已发送”视为数据库验收证据。

现有 `skus` 仍用于 V1 采购、库存、履约和利润；`shop_sku_mappings.sku_id` 继续指向 `skus.id`。本阶段新增独立货品与组成版本，未自动转换旧 SKU、推断货品关系或变更既有业务计算。

## 文件与数据模型

- `backend/src/main/resources/db/migration/V12__warehouse_goods_and_sku_compositions.sql`：独立的 `warehouse_goods`、`sku_composition_versions`、`sku_composition_items` 表与权限。
- `backend/src/main/resources/db/migration/V13__composition_deactivation_audit.sql`：新增版本停用原因、操作人和时间；不改写原始修改原因。
- `backend/src/main/java/com/lue/erp/masterdata/WarehouseGoodsController.java`：货品、SKU 详情、版本及需求展开接口，事务与操作日志。
- `backend/src/main/java/com/lue/erp/auth/BootstrapData.java`：新增权限及 ADMIN/OPERATOR 授权。
- `frontend/src/views/WarehouseGoodsView.vue`：货品查询、新建、编辑与停用。
- `frontend/src/views/SkuProductDetailsView.vue`：销售资料、现有店铺映射维护、组成版本与预览。
- `frontend/src/views/MasterDataView.vue`、`frontend/src/views/AdminLayout.vue`、`frontend/src/router.ts`：详情入口、导航和路由。
- `backend/src/test/java/com/lue/erp/masterdata/WarehouseGoodsAcceptanceTest.java`：可选择运行的独立 MySQL 验收测试。

V12、V13 只新增独立表、字段、外键与权限。V1 已有迁移文件未被编辑；没有业务数据回填。生产或已有开发数据库未执行这些迁移。本次实际迁移仅发生于新建的临时 MySQL 实例。

## API 与页面

| API | 用途 |
|---|---|
| `GET /api/warehouse-goods` | 分页、关键字、状态查询 |
| `GET /api/warehouse-goods/lookups` | 启用供应商选项 |
| `POST /api/warehouse-goods` | 新建货品 |
| `PUT /api/warehouse-goods/{id}` | 更新货品 |
| `POST /api/warehouse-goods/{id}/deactivate` | 停用货品并保留引用 |
| `GET /api/skus/{skuId}/product-details` | 按主键查销售资料与有权查看的精确 SKU 映射 |
| `GET /api/skus/{skuId}/compositions` | 查询全部历史版本及明细 |
| `POST /api/skus/{skuId}/compositions` | 只追加新版本 |
| `POST /api/skus/{skuId}/compositions/{versionId}/deactivate` | 停用版本、保存独立审计字段 |
| `GET /api/skus/{skuId}/compositions/preview` | `quantity` 指定销量；可选 `date` 按生效日选择，或 `versionId` 明确指定历史版本 |

货品页面：`/warehouse-goods`。SKU 中心“详情 / 组成”进入 `/skus/{skuId}/details`。店铺映射写操作复用原有 `/api/shop-sku-mappings`，不产生货品余额。

## 权限和版本规则

- 货品查询：`master.goods.view`；维护另需 `button.goods.manage`。
- 清单查询：`master.skus.view` 与 `master.compositions.view`；维护另需 `button.compositions.manage`。
- 映射查询与维护沿用 `master.mappings.view`、`master.data.manage`。没有查看权限的详情页签隐藏。
- 新权限默认授予 ADMIN、OPERATOR，未扩大仓库员工和只读角色的维护权限。
- 货品编码唯一；用量大于零，数量和参考价校验 `DECIMAL(18,4)` 范围。货品单位被清单引用后不能改写，避免历史数量含义变化。
- 每个销售 SKU 的版本号单调递增；锁定 SKU 和当前版本记录以处理并发。清单内容没有修改或删除接口，新版本不会覆盖任何旧版本。
- 生效区间按业务日期，结束日期包含当天。启用版本区间不得重叠；停用版本继续可查，并可明确指定版本预览。原始修改原因保留，停用另记原因、操作人和时间。
- 主要货品按用量乘以销售数量展开。替代件只记录为同一版本主要行的可选替代，不自动选择、不额外计入主要需求；拒绝自身替代、替代链、跨行错误引用和重复主要货品。
- 货品和版本不提供物理删除接口；外键进一步保护已引用记录。维护、新版本、停用均写入 `operation_logs`，日志失败时业务事务回滚。

## 实际验证

1. `npm run build`：通过 TypeScript/Vue 检查及 Vite 构建。保留 bundle 超过 500 kB 的体积提示。
2. 独立 MySQL 8.0.44，监听 `127.0.0.1:33479`，数据目录为 `backend/target/composition-acceptance-*`，未读取应用数据库配置。
3. 验收测试先迁移 V1–V11，写入合成旧库存（含负库存、锁定、待入/待出）与利润样本，取得快照；再应用 V12–V13 并执行 Flyway validate。
4. 设置 `LUE_COMPOSITION_TEST_URL=jdbc:mysql://127.0.0.1:33479/lue_composition_acceptance?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai`，运行 `mvn -o package`：**7 项测试通过，失败 0，错误 0，跳过 0；后端打包成功**。此前补齐了本地缺少的 Maven JUnit 运行器依赖。
5. 覆盖：多个销售 SKU 共用货品、单 SKU 多货品和替代展开；版本新增/停用/旧内容保留；错误用量及关系拒绝；编码唯一与引用单位保护；权限拒绝、日志失败事务回滚；并发版本编号；HTTP 查询超出前 100 条 SKU 的详情、禁止删除/覆盖版本。
6. 迁移前后及全部测试完成后，对 `inventory_stocks`、`inventory_flows`、包材库存/流水、采购单/明细/收货、订单、履约单/批次、结算、其他费用、`sku_profit_daily` 与旧 SKU 样本作全字段快照比较，结果一致。未新增任何旧库存或利润业务写入路径。

验收测试未设置上述环境变量时跳过；代码对地址作固定验收实例检查，测试使用新建 schema，不允许复用应用数据库配置。真实浏览器交互和正式业务数据库升级未执行。

`git diff --check` 通过（只有已有文件的 LF/CRLF 提示）。测试完成后，隔离 MySQL 已通过 mysqladmin 正常关闭；数据目录保留在忽略的 `backend/target` 下供追查。

## 后续阶段

本阶段未实现库位、货品库存余额或迁移、采购对象/成本改造、订单履约绑定版本和按组成扣库、利润取数改造、库存覆盖/缺货模拟。这些功能需要后续阶段实施；旧订单不会被自动追补组成版本。

现有商品/库存字段与历史记录继续按 V1 查询。新增货品参考价仅供主档维护；本阶段不产生实际采购批次成本或历史利润重算。
