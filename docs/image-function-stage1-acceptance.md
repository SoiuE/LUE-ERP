# 图片功能阶段一验收记录

日期：2026-10-17
验收范围：图片资产模型、存储抽象、资产 API、授权与审计
需求基线：`docs/image-function-requirements.md`、`docs/ai-image-function-development-instructions.md`
阶段 0 盘点：`docs/image-function-stage0-inventory.md`

## 结论

阶段一代码实现和可运行的自动化验证已完成。数据库迁移、真实 MinIO 上传/读取及运行中的 API 权限验收尚未执行，因此这些运行环境项目列为待部署验收，不宣称已经端到端通过。

## 实现内容与证据

### 数据模型

- 新增 `backend/src/main/resources/db/migration/V22__image_assets.sql`，没有编辑已有 Flyway 迁移。
- `image_assets` 保存 `UPLOAD`、`EXTERNAL_URL`、`ARCHIVED_URL` 来源、URL/对象键、文件名、MIME、大小、宽高、状态、创建人及时间。外链与对象存储字段受数据库 CHECK 约束；数据库不保存图片二进制或临时签名 URL。
- `image_asset_links` 以 `SKU`、`WAREHOUSE_GOODS` 关联图片资产，保存用途、主图标记、排序、JSON 标签、备注、状态、创建人及时间。唯一索引限制每个对象仅有一个活动主图；关联停用采用软停用。
- 新增权限 `menu.images` 和 `button.images.manage`。迁移及 `BootstrapData` 默认将两项权限授予 `ADMIN`、`OPERATOR`。

### API 与权限

控制器：`backend/src/main/java/com/lue/erp/images/ImageAssetController.java`；业务逻辑及审计：`ImageAssetService.java`。

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| `GET` | `/api/images` | 分页查询；可按来源、状态、对象类型/ID、用途、标签和关键词过滤 |
| `GET` | `/api/images/{id}` | 查询资产及关联详情 |
| `POST` | `/api/images/uploads` | 上传图片并创建 `UPLOAD` 资产 |
| `POST` | `/api/images/external-links` | 登记 HTTP/HTTPS 外链；不由服务端抓取 |
| `POST` | `/api/images/links` | 关联 SKU 或仓库货品 |
| `PATCH` | `/api/images/links/{linkId}` | 更新用途、主图、排序、标签和备注 |
| `DELETE` | `/api/images/links/{linkId}` | 软解除该关联，不删除共享资产或对象文件 |
| `POST` | `/api/images/{id}/deactivate` | 软停用资产及其活动关联 |
| `GET` | `/api/images/{id}/content` | 登录态下受控读取上传/归档文件；响应禁止缓存 |

读取和内容访问由服务端检查 `menu.images`；所有写操作还检查 `button.images.manage`。授权发生在服务访问存储或数据库之前。所有资产创建、外链登记、关联、关联更新/解除和停用操作写入 `operation_logs`，审计详情不包含图片二进制、外链 URL 或存储凭据。接口复用 Bearer 登录态和统一 `ApiResponse`；内容读取为二进制响应。

### 文件存储与输入校验

- `ImageStorage` 定义存储抽象；`MinioImageStorage` 提供 MinIO 实现；`ImageStorageConfiguration` 在 `lue.images.storage.type=minio` 时启用 MinIO 客户端，否则使用不可用实现并拒绝需要文件存储的操作。
- 对象键由服务端随机生成，不使用用户文件名作为路径。上传验证在 `ImageContentValidator`：限制 JPEG/PNG/GIF、检查文件签名和实际解码类型、限制文件字节数、像素总量和宽高；文件名仅保留为元数据。
- URL 校验只接受 HTTP/HTTPS，拒绝 URL 用户信息。外链在浏览器直连展示，不进行服务端抓取，也没有任意 URL 图片代理。
- 新增 MinIO Java SDK 依赖于 `backend/pom.xml`。

### SKU 导入兼容与边界

`ImportCenterService` 的 SKU Excel `image_url` 校验拒绝非法或非 HTTP/HTTPS 链接；提交导入时将有效链接创建或复用为 `EXTERNAL_URL` 资产并关联 SKU 主图。该兼容不执行历史 SKU 数据回填。手机现场附件仍使用 `warehouse_operation_attachments` 和原 API/关联流程，没有被复制进商品图片资产表。未修改业务页面或拣货、采购、库存流程。

## 验证记录

- 后端命令：`mvn -o "-Dmaven.repo.local=target/m2-cache" "-DforkCount=0" test`，结果 `BUILD SUCCESS`。25 个测试中 5 个通过、20 个因缺少隔离数据库环境变量而跳过。
- 图片专项通过项：`ImageContentValidatorTest` 3 项（签名/尺寸校验、拒绝非 HTTP(S) 或含凭据 URL、拒绝类型不符/超大尺寸）；`ImageAssetAuthorizationTest` 1 项（确认上传权限不足时不会访问存储或数据库）。另有 `ProcurementErrorResponseTest` 1 项通过。
- `git diff --check` 通过。
- 测试未连接目标业务数据库，也未启动 MinIO。未验证 MySQL 实际执行 V22、真实对象存储读写、HTTP API 端到端、运行环境中的角色授权行为。

## 数据库与迁移安全

开发环境配置检查记录：`backend/.env` 中的 `LUE_DB_URL` 指向 `127.0.0.1:3306/lue_erp`；`application.yml` 中 Flyway 默认关闭。本阶段未连接数据库，未执行迁移、写入或清理，也未对任何未知数据库做变更。现有业务数据按测试数据处理；没有编写历史数据映射或迁移工具，没有建议无关全库清理。部署前应由环境负责人核对实际运行配置及数据库目标，再安排应用 V22 的窗口。

## 部署配置与运行限制

配置定义见 `backend/src/main/resources/application.yml` 和 `backend/.env.example`：

| 变量 | 示例/默认值 | 用途 |
| --- | --- | --- |
| `LUE_IMAGE_STORAGE_TYPE` | `none` | 设为 `minio` 启用 MinIO 存储 |
| `LUE_MINIO_ENDPOINT` | `http://localhost:9000` | 后端可访问的 MinIO S3 API 地址；容器部署时应使用服务网络地址 |
| `LUE_MINIO_BUCKET` | `lue-erp-images` | 图片 bucket |
| `LUE_MINIO_ACCESS_KEY`、`LUE_MINIO_SECRET_KEY` | 空 | 通过部署密钥注入的专用凭据，不写入源码 |
| `LUE_MINIO_REGION` | `us-east-1` | MinIO 区域配置 |
| `LUE_IMAGE_MAX_FILE_SIZE` | `10485760` | 单文件字节上限，默认 10 MiB |
| `LUE_IMAGE_MAX_PIXELS` | `20000000` | 像素总量上限 |
| `LUE_IMAGE_MAX_DIMENSION` | `8000` | 单边像素上限 |

部署中的 MinIO 已在 `deploy/docker-compose.yml` 声明，但该 compose 没有后端服务配置；阶段一没有修改 compose。启用前需由部署方提供 bucket 和具备最小权限的服务账号/凭据，并确认网络连通。实现不会自动创建 bucket。默认 `LUE_IMAGE_STORAGE_TYPE=none` 时，外链资产可用，上传及归档内容读取需要配置文件存储后才能工作。

## 未纳入阶段一与后续验收

- 没有图片库菜单页/前端图片管理组件，也没有 SKU 或仓库货品表单改造；阶段二实施。
- 没有在各业务页展示图片，包括组成选择、手机查询、采购收货及 PC/手机拣货；阶段三实施。
- `ARCHIVED_URL` 已有模型约束，但本阶段没有归档/抓取 API；没有 SSRF 抓取器、缩略图任务、任务队列或图像搜索服务。
- 外链是否可达由浏览器加载结果决定；本阶段不做外链探测，不保证外链可用性。
- 迁移和对象存储尚未在集成环境执行。部署后应在隔离的验收环境先应用 V22，再用有/无图片权限的账号验证上传、登记、查询、关联/改主图/排序、解除关联、停用和受控读取；并确认停用/解除关联不会删除共享对象文件。

## 主要变更文件

- `backend/src/main/resources/db/migration/V22__image_assets.sql`
- `backend/src/main/java/com/lue/erp/images/ImageAssetController.java`
- `backend/src/main/java/com/lue/erp/images/ImageAssetService.java`
- `backend/src/main/java/com/lue/erp/images/ImageContentValidator.java`
- `backend/src/main/java/com/lue/erp/images/ImageStorage.java`
- `backend/src/main/java/com/lue/erp/images/MinioImageStorage.java`
- `backend/src/main/java/com/lue/erp/images/ImageStorageConfiguration.java`
- `backend/src/main/java/com/lue/erp/images/ImageStorageProperties.java`
- `backend/src/test/java/com/lue/erp/images/ImageAssetAuthorizationTest.java`
- `backend/src/test/java/com/lue/erp/images/ImageContentValidatorTest.java`
- `backend/src/main/java/com/lue/erp/imports/ImportCenterService.java`
- `backend/src/main/java/com/lue/erp/auth/BootstrapData.java`
- `backend/src/main/resources/application.yml`
- `backend/.env.example`
- `backend/pom.xml`
