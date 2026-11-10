# V2.0 阶段 2：仓库库位与库存明细模型验收

日期：2026-10-15（Asia/Shanghai）

## 边界

本阶段复用 V11 `warehouses` 和阶段 1 `warehouse_goods`。V14 只新增独立库位、货品绑定、货品库位余额和显式库位任务占用模型；没有回填旧 SKU 数量，没有库存写入 API，也没有改变 V1 采购、履约、包材、利润算法。真实业务数据库没有执行本次迁移。

## 模型和规则

- `warehouse_location_nodes` 以 `AREA → SHELF → LEVEL` 表示区域/排、货架、层；`warehouse_locations` 为位。服务端验证父子层级及所属仓库。
- `warehouse_locations` 的 `(warehouse_id,location_code)`、`(warehouse_id,scan_code)` 唯一；同层 `(warehouse_id,level_id,slot_code)` 唯一。空扫码编码存为 NULL。类型含普通存储、主拣货、待上架、暂存、异常/待检；支持启停。
- `warehouse_goods_location_assignments` 将货品绑定多个位，可在每仓设最多一个启用主拣货位；主拣货绑定须指向 `PRIMARY_PICK` 类型。多个货品可复用同一位。
- `warehouse_goods_location_balances` 是新的货品库位明细数据模型。按仓、货品直接求和得到查询汇总；没有另存一份可能不一致的汇总余额。没有余额行显示“未建账”，不能解读成 V1 库存为零。
- `position_status='HISTORICAL_UNLOCATED'` 必须配 NULL `location_id`，明细显示“历史未定位”。本阶段不插入任何历史余额行，也不伪造货架。后续真实迁移只能在映射证据确认后处理。
- 停用库位时锁定该位并检查所有数量字段（含负数）、`warehouse_location_task_claims.status='OPEN'` 及有效货品绑定；命中时拒绝并提示移库/调整、完成任务或改配绑定。更改有库存/任务的库位实体层/位亦拒绝。现有 V1 履约没有库位外键，无法推断其占用的位；未来任务写入必须建立明确库位关联并遵守启用状态。
- 不提供库位、层级、余额或已引用记录的物理删除接口。主档写入、启停和绑定变更均写 `operation_logs`；日志失败时事务回滚。

## API 与页面

`WarehouseLocationsController` 提供 `/api/warehouse-locations/warehouses`、`/goods`、`/nodes`（GET/POST/PUT）、库位 GET/POST/PUT/启停、`/assignments`（GET/POST/停用）、`/stock-summary`、`/stock-details`。没有新增余额写入、采购或履约接口。

`/warehouse-locations` 提供层级和库位维护、货品绑定；`/location-inventory` 提供按仓库、货品、库位筛选的货品汇总和库位明细。仓库货品列表可跳转并按货品筛选。

权限：`warehouse.locations.view` 控制库位读 API/页面，`button.locations.manage` 控制所有主档与绑定写 API，`warehouse.location_stock.view` 控制余额查询。ADMIN、OPERATOR 获得查询与维护；WAREHOUSE 获得查询；VIEWER 未授权。后端每个入口自行校验权限，页面权限只负责可见性。

## 实际验证

- `mvn -o -DskipTests package`：后端编译、测试代码编译和打包通过；此命令按参数跳过测试。
- `npm run build`：前端 TypeScript/Vue 和 Vite 构建通过；现有产物体积超过 500 kB 提示保留。
- 在全新隔离 MySQL 8.0.44 实例 `127.0.0.1:33481` 中，测试创建独立 schema。Flyway 先迁移至 V13，写入合成 V1 库存（含负数、锁定和在途字段）及利润样本，再执行 V14 并 `validate`；迁移前后和测试结束后旧表全行快照一致。
- `LUE_LOCATION_TEST_URL=jdbc:mysql://127.0.0.1:33481/lue_location_acceptance?...` 下执行 `mvn -o -Dtest=WarehouseLocationsAcceptanceTest test`：3 项测试通过，失败 0，错误 0。覆盖层级/编码唯一、一个货品多位和多个货品同位、停用拦截、汇总与明细、历史未定位约束、权限与日志。测试直接插入的 V2 余额和任务仅为隔离 schema 合成样本，不是生产余额迁移。
- 独立实例已通过 `mysqladmin shutdown` 正常关闭。未做真实浏览器交互或业务数据库升级。

后续阶段需把采购/上架/移库/履约业务单据、货品流水、成本快照和库位余额事务性接入，并在对账后处理旧 SKU 到货品的余额迁移。本阶段不以新表余额代替 V1 SKU 库存数。
