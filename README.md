# LUE AliExpress 全托管 ERP

面向速卖通全托管多店铺运营的 ERP 系统。当前完成阶段 0、阶段 1 登录权限，以及阶段 2 店铺、SKU、供应商和包材主数据。

## 项目结构

```text
LUE ERP/
  backend/          Spring Boot 后端
  frontend/         Vue 3 + TypeScript 前端
  deploy/           Docker Compose 与部署配置
  data-templates/   Excel 导入模板目录
  docs/             需求与开发文档
```

## 环境要求

- JDK 17+
- Maven 3.9+
- Node.js 20+
- npm 10+
- Docker Desktop 或 Docker Engine（仅在本机启动 MySQL、Redis、MinIO 时需要）

## 开发与正式部署环境

当前开发可以在目标局域网之外进行，无需连接最终部署网络。前后端可在开发电脑分别启动；Docker 仅在需要本机启动 MySQL、Redis、MinIO 或验证 Compose 时需要。

系统计划部署到企业局域网内的服务器，员工通过浏览器访问该服务器的局域网 IP 或内部 DNS 名称。正式部署时再按现场网络配置访问地址和防火墙，只开放 Web 访问所需端口；数据库、Redis、MinIO 不应直接对员工客户端开放。请勿把正式服务器地址或真实密码写入代码。

## 启动基础设施

复制环境变量示例：

```powershell
Copy-Item deploy\.env.example deploy\.env
```

启动 MySQL、Redis、MinIO：

```powershell
docker compose --env-file deploy\.env -f deploy\docker-compose.yml up -d
```

默认服务端口：

- MySQL: `3306`
- Redis: `6379`
- MinIO API: `9000`
- MinIO Console: `9001`

## 启动后端

可通过 `backend/.env` 为后端提供本机数据库连接配置。该文件已被 Git 忽略，不要提交真实密码：

```powershell
Copy-Item backend\.env.example backend\.env
```

在 MySQL Workbench 使用本机管理员账号连接后，执行下面的 SQL 创建应用数据库账号。请将密码替换为你自己生成的强密码，然后把相同的密码填入 `backend/.env` 的 `LUE_DB_PASSWORD`：

```sql
CREATE DATABASE IF NOT EXISTS lue_erp CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'lue_erp_app'@'localhost' IDENTIFIED BY '123456';
ALTER USER 'lue_erp_app'@'localhost' IDENTIFIED BY '123456';
GRANT ALL PRIVILEGES ON lue_erp.* TO 'lue_erp_app'@'localhost';
```

如果 `backend/.env` 中的数据库已存在且包含业务数据，不要删除或重建数据库。以上步骤只创建/授权应用账号。Flyway 默认关闭；只在已核实的隔离环境中显式设置 `LUE_FLYWAY_ENABLED=true` 验证迁移。生产迁移须另行执行经批准的操作流程。

```powershell
cd backend
mvn spring-boot:run
```

健康检查：

```text
GET http://localhost:8080/api/health
```

## 启动前端

```powershell
cd frontend
npm install
npm run dev
```

默认访问：

```text
http://localhost:5173
```

### Windows 一键启动

完成首次环境配置（JDK、Maven、Node.js/npm、本机 MySQL、`backend/.env` 和 `frontend/node_modules`）后，可双击项目根目录的 `一键启动ERP.bat`。脚本会检查 MySQL 与必要依赖，后台启动尚未运行的前后端，等待健康检查通过后打开浏览器；运行日志保存在 `logs/`。关闭系统时双击 `一键停止ERP.bat`。脚本不会修改数据库或覆盖本地环境配置；如果端口已被其他程序占用，会提示处理。

Windows 本地运行及暂停步骤见[程序运行命令操作文档](docs/程序运行命令操作文档.md)和[程序暂停操作文档](docs/程序暂停操作文档.md)。

## 登录与初始化账户

隔离环境显式启用 Flyway 并迁移后，会创建用户、角色、权限关联和操作日志表，并初始化 `ADMIN`、`OPERATOR`、`VIEWER` 角色及菜单权限。默认管理员账户为 `admin` / `ChangeMe123!`。首次部署前请通过环境变量设置 `LUE_ADMIN_USERNAME`、`LUE_ADMIN_PASSWORD` 和 `LUE_AUTH_SECRET`；管理员只在账户尚不存在时创建，修改环境变量不会重置已存在账户的密码。

在 PowerShell 启动前可配置：

```powershell
$env:LUE_ADMIN_USERNAME = "admin"
$env:LUE_ADMIN_PASSWORD = "请替换为强密码"
$env:LUE_AUTH_SECRET = "请替换为至少32字符的随机密钥"
```

启动登录验证流程：在 `deploy\.env` 中配置 MySQL 连接参数并启动数据库，然后分别运行后端和前端。浏览器打开 `http://localhost:5173`，未登录会进入登录页；使用管理员账户登录后可看到工作台、用户管理、角色与权限、操作日志菜单。运营和只读角色仅可见工作台。登录后端接口为 `POST /api/auth/login`，当前用户为 `GET /api/auth/me`，退出为 `POST /api/auth/logout`（前端清除本地令牌）。

## 主数据

Flyway 迁移会创建 `shops`、`skus`、`suppliers`、`package_materials` 和编码序列表 `master_code_sequences`。页面位于店铺管理、SKU 中心、供应商档案和包材档案菜单。列表支持搜索、状态/包材分类筛选、分页、新增、编辑、停用和软删除。供应商编码默认 `SUP + YYYYMMDD + 6 位每日序号`，包材编码默认 `MAT + YYYYMMDD + 6 位每日序号`；新增时自动填入，用户可修改。自定义编码限 2–64 位大写字母、数字、短横线或下划线，必须唯一。SKU 默认产品包装及发货打包材料方案以 JSON 预留；包材方案引用只允许使用分类匹配且启用的包材。主数据接口统一位于 `/api/master/{shops|skus|suppliers|materials}`，需要对应菜单权限，写操作另需 `master.data.manage` 按钮权限。

## 店铺 SKU 映射

阶段 3 已提供 `/mappings` 页面和 `/api/shop-sku-mappings` 接口，可按店铺、平台 SKU、内部 SKU 筛选并分页，支持手工新增、编辑和停用。每条映射必须关联启用的店铺及内部 SKU，订单模式可选 `JIT`（默认）或 `WAREHOUSE`。数据库以生成列 `active_platform_sku` 将停用/软删除记录置为 `NULL`，并对 `(shop_id, active_platform_sku)` 建唯一索引：同一店铺同一平台 SKU 同时只能有一条有效映射，不同店铺可以使用相同平台 SKU；停用后可重新绑定。创建、编辑、停用均写入操作日志。

## Excel 导入中心

阶段 4 已提供「导入中心」，当前支持 SKU 与店铺 SKU 映射两种 `.xlsx` 模板。导入流程为上传、解析和校验、预览原始行及错误、确认后事务写入。单文件上限 20,000 行；错误批次不允许提交。SKU 和映射按业务键检测重复，勾选强制重导入后可覆盖已有记录。成功内容默认按导入类型和文件 SHA-256 幂等拦截。原文件默认保存到 `backend/data/imports/`（可通过 `LUE_IMPORT_STORAGE_DIR` 配置），数据库保留批次、原始 JSON 行、标准化值和逐行校验错误；失败明细可下载 CSV。批次表预留 `data_collect_task_id`，采集任务中心完成后再建立关联。

模板字段说明见 `data-templates/README.md`，模板通过 `/api/import-batches/templates/{sku|shop_sku_mapping}` 下载。

## 后续阶段未实现

- 采购业务、库存台账及包材库存流水。
- 手机端仓库操作。
- 爬虫、RPA、速卖通官方 API。
