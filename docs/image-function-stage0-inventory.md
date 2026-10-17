# 图片功能阶段 0：只读盘点与实施切分

盘点日期：2026-10-17
依据：`docs/image-function-requirements.md` 与当前仓库代码、Flyway 迁移、部署配置和测试源码。

## 范围与限制

本报告是仓库源码盘点，不代表对运行中数据库的核验。本次未连接数据库、未检查实际连接目标、未运行迁移或测试，也没有执行数据写入或清理。

当前业务数据按需求定义为测试数据，无需保留或为其编写业务数据迁移、映射及历史兼容工具。后续数据库结构变更仍应使用新的 Flyway 向前迁移，不编辑已存在迁移；不应进行无关的全库清理或重置。

## 1. SKU 图片字段、Excel 流程和主数据 API

### 字段与页面

- `backend/src/main/resources/db/migration/V2__master_data.sql` 在 `skus` 表定义 `image_url VARCHAR(1000)`，只承载单个 URL 文本，没有多图关系或图片元数据。
- `backend/src/main/java/com/lue/erp/masterdata/MasterDataService.java` 的 SKU 字段白名单包含 `image_url`，列表映射为 `imageUrl`；通用主数据创建/更新可写此字段。现有校验包含字符串长度限制，没有 HTTP/HTTPS 协议或 URL 格式检查。
- `frontend/src/views/MasterDataView.vue` 的 SKU 表单提供“商品图片 URL”文本框；SKU 列表没有图片列。
- `backend/src/main/java/com/lue/erp/masterdata/WarehouseGoodsController.java` 的 `GET /api/skus/{skuId}/product-details` 要求 `master.skus.view`，返回的 SKU 详情字段当前不含 `image_url`。有 `master.mappings.view` 权限时才附带店铺映射。

### Excel 导入、校验、模板和导出

- `backend/src/main/java/com/lue/erp/imports/ImportCenterService.java` 的 SKU 模板字段和 `SKU_FIELDS` 均包含 `image_url`，模板标签为“商品图片链接”。`validateSku` 对该字段只校验最多 1000 个字符。
- 导入链路为上传 `.xlsx`、逐行校验并生成预览批次、用户确认后提交。`writeSku` 将 `image_url` 直接写入/更新 `skus.image_url`；强制重导入也覆盖该字段。
- `backend/src/main/java/com/lue/erp/imports/ImportCenterController.java` 的 `POST /api/import-batches` 校验 `menu.imports` 与 `button.imports.manage`；模板、原文件和失败明细分别提供下载端点。`frontend/src/views/ImportCenterView.vue` 的 SKU 预览列含 `image_url`。
- 当前没有 SKU 主数据 Excel 导出 API/流程。利润报表导出是独立功能，不是 SKU 档案导出。

### 主数据 API

- `GET /api/master/skus`：分页、关键词、状态筛选；查看权限 `master.skus.view`。
- `POST /api/master/skus`、`PUT /api/master/skus/{id}`、停用/软删除：查看权限加 `master.data.manage`。
- SKU 详情、组成清单版本、组成预览及货品选择 API 集中在 `WarehouseGoodsController.java`；组成清单权限包含 `master.compositions.view`、`master.skus.view`、`button.compositions.manage`。

## 2. 仓库货品表、接口、页面和权限

- `backend/src/main/resources/db/migration/V12__warehouse_goods_and_sku_compositions.sql` 定义 `warehouse_goods`，字段包括货品编码、名称、规格、条码、单位、默认供应商、参考采购价、状态、备注等；当前没有图片字段。
- `sku_composition_items.warehouse_goods_id` 将销售 SKU 的版本化组成清单关联到仓库货品。销售 SKU 与仓库货品是不同对象。
- `backend/src/main/java/com/lue/erp/masterdata/WarehouseGoodsController.java` 提供货品分页搜索、供应商 lookup、创建、更新、停用接口：`GET/POST /api/warehouse-goods`、`GET /api/warehouse-goods/lookups`、`PUT /api/warehouse-goods/{id}`、`POST /api/warehouse-goods/{id}/deactivate`。
- 读取权限为 `master.goods.view`；维护还要求 `button.goods.manage`。接口写操作记录到 `operation_logs`。
- `frontend/src/views/WarehouseGoodsView.vue` 是货品列表和编辑对话框，含库位查看入口，没有图片列或货品详情页。路由在 `frontend/src/router.ts`，入口为 `/warehouse-goods`。
- `frontend/src/views/SkuProductDetailsView.vue` 是 SKU 详情入口，提供组成清单版本和货品选择器。选择器通过 `/api/warehouse-goods?status=ACTIVE` 拉取货品，目前显示编码、名称和单位，没有图片。
- `frontend/src/views/AdminLayout.vue` 的仓库货品菜单权限为 `master.goods.view`；当前没有图片库菜单。

## 3. 手机现场照片上传、读取和附件关系

现场照片属于业务操作附件，不是商品标准图片。

- `backend/src/main/resources/db/migration/V9__mobile_warehouse_operations.sql` 定义 `warehouse_operation_attachments`，包含 `operation_log_id`、上传者、原始文件名、存储文件名、MIME 类型、文件大小、创建时间；外键关联 `operation_logs` 和 `users`，没有 SKU 或仓库货品关联。
- `backend/src/main/java/com/lue/erp/warehouse/MobileWarehouseController.java` 的 `POST /api/mobile/attachments` 接收 multipart 文件，按 MIME 接受 JPEG、PNG、WebP、HEIC/HEIF，限制 10 MB。文件写入 `lue.warehouse.photo-dir`，默认 `./data/warehouse-attachments`，服务端以 UUID 命名并写入附件元数据。
- `GET /api/mobile/attachments/{id}` 通过本地存储读取并以内联图片响应，设置 `no-store`。上传和读取均要求 `menu.mobile`。
- 手机端 `frontend/src/views/mobile/mobileHelpers.ts` 的 `uploadWarehousePhoto` 执行上传；`MobilePurchaseSheet.vue`、`MobileGoodsTasksView.vue`、`MobilePackageView.vue` 等将返回的附件 ID 随对应业务操作提交。
- `MobileWarehouseController.audited` 写操作日志后，将当前用户上传且尚未关联的附件设置 `operation_log_id`。附件生命周期和引用对象是操作记录，和商品图册/图像搜索索引边界明确。

## 4. 部署、上传、认证、统一响应和审计

- `deploy/docker-compose.yml` 已包含 MySQL、Redis、MinIO。MinIO 镜像为 `minio/minio:RELEASE.2024-10-13T13-34-11Z`，数据卷为 `minio_data`，映射端口 9000 和 9001，用户/密码来自 `MINIO_ROOT_USER`、`MINIO_ROOT_PASSWORD`，并设有弱默认值。现有手机照片代码使用本地目录，没有 MinIO 客户端调用或 MinIO 连接配置。
- `backend/src/main/resources/application.yml` 的 Spring multipart 默认限制为文件 50 MB、请求 52 MB；现场照片接口另有 10 MB 限制。配置还包括 `LUE_DB_URL`、`LUE_DB_USERNAME`、`LUE_DB_PASSWORD`、Redis 参数、Flyway 开关和认证密钥等。Flyway 默认关闭。
- `backend/src/main/java/com/lue/erp/auth/SecurityConfig.java` 使用无状态认证；`AuthFilter` 从 Bearer Token 加载用户。除登录、健康检查等白名单外，API 要求登录。菜单和按钮权限还由服务端 `AuthService.requirePermission` 校验。
- `backend/src/main/java/com/lue/erp/common/api/ApiResponse.java` 的统一响应为 `{success, code, message, data}`；`ApiExceptionHandler.java` 为常见异常返回统一结构。文件读取端点用 `ResponseEntity` 返回二进制内容。
- `operation_logs` 由 `V1__auth_and_audit.sql` 建立。各业务 controller/service 通常写入动作、资源类型/ID、JSON 详情、IP 和成功标志。图片操作应复用审计模式，日志不得包含二进制或存储凭据。
- 权限命名示例：`master.skus.view`、`master.goods.view`、`button.goods.manage`、`menu.mobile`。新增权限需服务端校验；前端路由和按钮隐藏只负责交互。

## 5. 图片库和业务页面现状

- `frontend/src/router.ts`、`frontend/src/views/AdminLayout.vue` 当前没有图片库路由或菜单。
- SKU 档案：`frontend/src/views/MasterDataView.vue`；SKU 详情和组成选择：`frontend/src/views/SkuProductDetailsView.vue`。
- 仓库货品档案：`frontend/src/views/WarehouseGoodsView.vue`。当前没有独立货品详情页；其库位链接不是货品详情入口。
- 手机条码组件：`frontend/src/views/mobile/MobileBarcodeSearch.vue`。它用于扫码输入/识别，不是图片查询页。手机主页、收货/上架任务及拣货分别位于 `MobileHomeView.vue`、`MobileGoodsTasksView.vue`、`MobileFulfillmentView.vue`。
- 采购收货：PC `frontend/src/views/GoodsProcurementView.vue`；手机 `frontend/src/views/mobile/MobilePurchaseSheet.vue`，任务汇总页也包括 `MobileGoodsTasksView.vue`。
- PC 发货拣货：`frontend/src/views/FulfillmentView.vue`；手机发货拣货：`frontend/src/views/mobile/MobileFulfillmentView.vue`。后端履约接口由 `FulfillmentController.java`、`FulfillmentGoodsService.java` 等提供。现有任务展示编码、名称、数量、库位，未返回或显示商品图片。
- 盘点/移库等移动仓库操作主要在 `MobileGoodsTasksView.vue`，当前现场照片作为独立业务附件。

## 6. 现有依赖能力核实

依据 `backend/pom.xml` 和 `frontend/package.json`：

- 已有 Spring Boot Web/Security/JDBC/Validation、Flyway、MyBatis、MySQL、Spring Data Redis、Apache POI；前端已有 Vue 3、Element Plus、Vant、ZXing。
- 未发现 MinIO/S3 对象存储 Java 客户端、图片处理/缩略图依赖、RabbitMQ/Kafka 等队列客户端或图像服务客户端。
- Redis 依赖不能据此推断已有任务队列。代码和依赖清单中没有发现图片任务处理服务。后续归档/缩略图/索引需明确选择任务实现和部署方式。

## 7. 目标模型、API、权限和前端方案建议

以下是方案建议，未实施。

### 数据模型

1. `image_assets` 保存图片资产本身：来源类型 `UPLOAD`、`EXTERNAL_URL`、`ARCHIVED_URL`；对象键或 URL、来源 URL、文件名、MIME、大小、宽高、状态、外链检查/归档状态、创建者和时间、索引状态/版本等。数据库不存二进制；对象键不使用短期签名 URL。
2. `image_asset_links` 保存资产与业务对象关系：对象类型（至少 SKU、WAREHOUSE_GOODS）、对象 ID、资产 ID、用途、是否主图、顺序、标签、备注、创建者和时间。支持一图多对象，并保证同一对象主图唯一、排序稳定。
3. 解除关联、停用和替换要留审计信息；对象存储文件走可追踪的回收策略，解除单条关联不得误删其他对象仍在用的资产。
4. `warehouse_operation_attachments` 继续表达现场操作附件，不自动转成商品资产或搜索索引。
5. 旧 `skus.image_url` 只作为导入兼容入口。考虑到现存数据均为测试数据，不建议添加历史行转换/映射工具；后续新导入的合法链接可写成 `EXTERNAL_URL` 资产及 SKU 关联。

### API 边界建议

- `GET /api/images`：分页，按对象类型/编码名称/用途/标签/状态查询。
- `POST /api/images/uploads`：上传及服务端格式、文件签名、大小、像素校验。
- `POST /api/images/external-links`：登记 HTTP(S) 链接，默认不下载。
- `GET/PATCH /api/images/{id}`：受控元数据读取和维护；关联可用独立 link/unlink API 或聚合更新 API。
- `GET /api/images/{id}/content`：服务端鉴权读取，或返回短时受控访问地址；不向普通员工开放 MinIO 管理或存储端口。
- `POST /api/images/{id}/archive`：阶段 4 用户触发的外链归档，明确状态、错误、重试及幂等行为。
- SKU/货品和作业列表接口返回缩略图标识或受控 URL，不返回原图二进制。以图搜商品 API 延后，现场附件默认排除。

### 权限、审计与前端组件建议

- 可新增 `master.images.view`、`button.images.manage`；如需独立控制外链归档、图像搜索，再增加清晰权限。所有内容读取、资产维护、关联、归档和搜索均在服务端验证权限及对象范围。
- 所有资产新增、关联/解除、主图变更、归档、停用写入 `operation_logs`，不记文件内容或密钥。
- PC 建立图片库、对象图片管理面板、图片选择器、预览查看器；复用 Element Plus。手机复用 Vant，缩略图不挤压编码/规格/数量/确认操作。
- 外链图片采用浏览器直接加载、懒加载、统一占位及 `error` 处理；失败不阻断业务。不要建立可代理任意 URL 的图片代理。按实际外链策略配置前端资源来源策略。

### 安全归档建议

归档服务只允许 HTTP/HTTPS；每次连接解析并验证 IP，拒绝环回、内网、链路本地及云元数据地址；限制重定向次数、连接/读取超时、响应体大小；验证响应 MIME 和文件签名，不信任 URL 后缀或用户声明的 MIME。默认用户显式触发，外链可用性检查异步限速，不能在列表请求中同步访问外站。原 URL、状态、失败信息及重试时间应可追踪。

## 8. 阶段顺序、验收点和部署影响

需求文档将实施划为阶段 1–5。本表按用户要求列出 1–6，其中阶段 6 是建议的上线稳定性收尾，不是原文已有阶段。

| 阶段 | 依赖与验收点 | 配置/部署影响 |
|---|---|---|
| 1 图片资产与存储基础 | 新增向前 Flyway；实现资产/关联、上传、外链登记、受控读取、主图/排序/用途/标签/备注、授权审计。验收：文件不入 MySQL；合法图片可上传/读；非法格式、超限和越权被拒；同一资产可关联多对象；SKU `image_url` 新导入兼容。 | MinIO 内部 endpoint、bucket、专用访问身份/密钥、内部网络/TLS、上传字节数/像素限制、访问策略。Compose 现有 MinIO 没有后端连接参数或 bucket 初始化配置；部署需补齐，生产不能用弱默认凭据。 |
| 2 图片库与档案 | 依赖阶段 1。验收：图库过滤分页预览；SKU 和货品能维护多图；主图唯一、回退规则稳定；外链失败占位可维护；现场照片仍独立。 | 前端外链资源策略；没有必须新增的服务。 |
| 3 高频业务展示 | 依赖阶段 1 的对象图片查询。依次接入 SKU/货品详情、组成选择、移动查询、采购收货、PC/手机拣货和盘点/异常。验收：缩略图可用且不影响作业字段；图片错误不阻断业务；现场照片仍是操作附件。 | 列表只取缩略图；设置适当缓存和失败回退。 |
| 4 外链归档、缩略图与索引前置 | 依赖阶段 1。验收：SSRF、大文件、超时、伪 MIME/签名被拦截；归档状态可追踪/重试；失败不影响原外链和业务；重复操作可识别。 | 抓取超时、体积、重定向、允许域（如采用）、缩略图尺寸、并发/重试、保留和回收配置。现有依赖无队列，需明确是受限同步处理还是引入任务服务。 |
| 5 以图搜商品 | 依赖资产模型和索引前置状态。验收：返回 Top 10、合并同对象多图命中、聚合库存、可跳转确认；确认前不写库存/采购/映射；现场图排除；搜索故障不影响核心流程。 | 部署 Python FastAPI 图片服务和选定向量索引；配置地址、鉴权、模型/索引版本、超时、失败重试和积压监控。候选模型/向量库待评估。 |
| 6 上线稳定性验收（建议） | 贯通权限矩阵、备份恢复、容量阈值、故障降级、附件边界、日志脱敏和核心流程验证。验收：MinIO/图片服务故障时采购、库存和拣货可继续；图片恢复和回收有可审计记录。 | 对象存储备份、容量/失败率告警、保留策略和运维指标。 |

全局 Spring multipart 当前为 50 MB 文件/52 MB 请求；现场照片接口为 10 MB。新商品图片上传应独立配置合理上限，并与反向代理和前端限制一致。

## 9. 具体候选文件清单

实施时可能涉及的主要文件（最终范围依阶段评审确定）：

- 数据库：新增 `backend/src/main/resources/db/migration/V22__image_assets.sql`。仓库迁移文件最高为 V21；执行前仍需核对目标环境连接配置和实际 Flyway 状态。本次未连接数据库。
- 后端新增：图片 controller/service、存储适配、格式/URL/SSRF 校验、任务状态处理等类；具体包结构待阶段 1 定稿。
- 后端修改：`backend/src/main/java/com/lue/erp/imports/ImportCenterService.java`、`masterdata/MasterDataService.java`、`masterdata/WarehouseGoodsController.java`、`warehouse/FulfillmentGoodsService.java`、`warehouse/FulfillmentController.java`、采购相关 controller/service、`warehouse/MobileWarehouseController.java`（如抽取共用存储接口，保持附件语义和兼容端点）。
- 配置和依赖：`backend/src/main/resources/application.yml`、`backend/pom.xml`、`deploy/docker-compose.yml` 及对应部署环境变量。
- 前端基础：新增图库页和可复用图片组件；修改 `frontend/src/router.ts`、`frontend/src/views/AdminLayout.vue`、`frontend/src/api.ts`。
- 前端档案/业务：`MasterDataView.vue`、`SkuProductDetailsView.vue`、`WarehouseGoodsView.vue`、`mobile/MobileBarcodeSearch.vue`、`mobile/MobileGoodsTasksView.vue`、`mobile/MobileFulfillmentView.vue`、`GoodsProcurementView.vue`、`mobile/MobilePurchaseSheet.vue`、`FulfillmentView.vue`；盘点/异常入口视设计涉及 `ExceptionsView.vue` 和移动任务页面。
- 验收测试可沿用现有 `backend/src/test/java/com/lue/erp/...` 隔离数据库模式，覆盖权限、资产关联、上传/读取、安全校验、导入兼容、附件边界及故障降级。本次未运行测试。

## 10. 当前相关测试

已存在的相关验收测试源码：

- `backend/src/test/java/com/lue/erp/masterdata/WarehouseGoodsAcceptanceTest.java`
- `backend/src/test/java/com/lue/erp/warehouse/PurchaseReceivingAcceptanceTest.java`
- `backend/src/test/java/com/lue/erp/warehouse/FulfillmentGoodsAcceptanceTest.java`
- `backend/src/test/java/com/lue/erp/warehouse/WarehouseLocationsAcceptanceTest.java`
- `backend/src/test/java/com/lue/erp/auth/ProcurementErrorResponseTest.java`

覆盖货品与组成清单、采购收货及权限、履约货品任务、库位和统一错误响应等。未发现图片资产/对象存储专项测试。

## 11. 测试数据与历史兼容决定

- 不需要兼容或保留当前测试 SKU、测试货品、历史图片 URL 或现场照片数据；不编写历史业务数据迁移、映射工具。
- 保留需求指定的程序级兼容：SKU Excel 模板继续含 `image_url`，合法 URL 在新能力中成为外链图片来源/资产关联。
- 现场操作附件 API/表继续服务原业务操作，不因新增图片库而批量迁移或复制附件。
- 不建议或执行无关全库清理、重置。

## 结论

阶段 0 源码盘点已完成。下一步应在阶段 1 开始前核对实际数据库连接配置与目标环境，明确 MinIO bucket/凭据注入方式和上传约束，再设计新增 Flyway 表及服务端 API。当前系统已经有 MinIO 部署声明，但没有图片对象存储客户端、图片处理依赖或已知队列实现；这几项是阶段 1/4 需要明确补充的基础设施决策。
