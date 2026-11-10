# LUE AliExpress 全托管 ERP

面向速卖通全托管多店铺运营的 ERP 系统。前端使用 Vue 3 和 TypeScript，后端使用 Spring Boot；业务数据存于 MySQL，Redis 提供运行支持，RustFS 提供 S3 兼容图片存储。图片相似搜索是独立的可选服务。

## 已实现的主要功能

- 用户登录、角色权限与操作日志。
- 店铺、SKU、供应商、包材、仓库货品及店铺 SKU 映射管理。
- Excel 导入、订单与异常处理、采购收货、仓储库位及库存操作。
- 本地履约、利润与结算、移动端仓库操作。
- 图片资产管理；图片相似搜索需另行启动其服务。

功能细节、阶段需求与验收记录见 [文档导航](docs/README.md)。历史验收文档记录当时版本，实际能力以当前代码和部署配置为准。

## 目录

| 路径 | 用途 |
| --- | --- |
| `backend/` | Spring Boot API、Flyway 数据库迁移、后端 Dockerfile |
| `frontend/` | Vue 3 页面、前端 Dockerfile |
| `deploy/` | Docker Compose 与环境变量模板 |
| `image-search/` | 可选的 Python 图片搜索服务 |
| `data-templates/` | Excel 导入模板 |
| `scripts/` | Windows 本地启动与停止脚本 |
| `tools/` | 导入转换等辅助工具 |
| `docs/` | 部署、需求、开发过程及验收文档 |

## 运行方式

| 场景 | 入口 | 访问地址 |
| --- | --- | --- |
| Windows 本地开发 | [一键启动与启动前准备](docs/部署/本地电脑/一键启动与启动前准备.md) | `http://localhost:5173` |
| Windows 本地全容器 | [Docker 本地部署](docs/部署/本地电脑/Docker本地部署.md) | `http://127.0.0.1:8081` |
| 阿里云轻量服务器 | [Docker 服务器部署](docs/部署/服务器/Docker服务器部署.md)、[系统和宝塔配置](docs/部署/服务器/阿里云轻量服务器部署方案.md) | 经宝塔 Nginx 对外提供 |

本地脚本方式需要 JDK 17+、Maven 3.9+、Node.js 20+、npm 10+，以及本机 MySQL、Redis 和对象存储。全容器方式需要 Docker Engine 或 Docker Desktop，以及 Compose v2；后端、前端和依赖服务由 Compose 启动。服务器上的宝塔 Nginx 只负责网站入口与 HTTPS，反向代理到本机 `127.0.0.1:8081`。

全容器首次启动：复制 `deploy/.env.full.example` 为 `deploy/.env`，替换所有 `CHANGE_ME`，然后在仓库根目录运行：

```powershell
docker compose -p lue-erp-local --env-file deploy/.env -f deploy/docker-compose.prod.yml -f deploy/docker-compose.app.yml up -d --build
```

首次空库建表需要 `LUE_FLYWAY_ENABLED=true`；已有业务数据时，先备份并审查迁移。完整的本地运行、服务器镜像传输、升级及备份步骤见上表文档。项目包含 `/api/health` 健康接口。

## 配置与数据

`deploy/.env` 和 `backend/.env` 是本地配置，不应提交。生产环境必须设置独立的数据库密码、对象存储密码、`LUE_AUTH_SECRET` 和管理员密码。管理员只在账户不存在时创建，修改环境变量不会重置已有密码。

GitHub 同步源代码和模板；MySQL 数据卷、RustFS 图片、后端上传文件及本机 `backend/data/` 不会随 Git 同步。生产备份范围与恢复要求见 [Docker 统一部署](docs/部署/服务器/Docker服务器部署.md)。
