# LUE ERP 的 Docker 组成与全栈部署

## 当前项目有哪些容器

| 服务 | 当前文件 | 作用 | 数据位置 |
| --- | --- | --- | --- |
| MySQL 8.4 | `deploy/docker-compose.yml`、`deploy/docker-compose.prod.yml` | 业务数据库 | Docker `mysql_data` 卷 |
| Redis 7.4 | 同上 | 缓存等运行状态 | Docker `redis_data` 卷 |
| RustFS（服务名 `minio`） | 同上 | S3 兼容图片存储 | Docker `minio_data` 卷 |
| `minio-init` | 同上 | 首次创建图片 bucket，完成后退出 | 无 |
| 图片搜索 Python 服务 | `image-search/Dockerfile`，开发 Compose 的 `image-search` profile | 图片向量检索 | 独立卷；4 GiB 服务器暂不启用 |
| Java 后端 | `backend/Dockerfile`、`deploy/docker-compose.app.yml` | Spring Boot API | `backend_data` 卷中的上传原文件和仓库照片 |
| Vue 前端 | `frontend/Dockerfile`、`deploy/docker-compose.app.yml` | 静态页面与 `/api/` 代理 | 无持久数据 |

Docker 镜像是程序和运行环境；Docker 卷是数据库、图片和文件。`docker save` 只转移镜像，**不会转移卷中的业务数据**。你现在不迁移测试数据，可以在服务器创建全新卷。

## 推荐结构

浏览器 → 宝塔 Nginx（80/443、SSL）→ `127.0.0.1:8081`（前端容器 Nginx）→ `backend:8080` → MySQL / Redis / RustFS。两个 Nginx 用途不同：宝塔负责公网入口与证书，容器内 Nginx 提供前端文件及 `/api` 转发。只需在宝塔建立**一个反向代理站点**，目标 `http://127.0.0.1:8081`。不要同时启用旧方案的宝塔静态 HTML 站点与 systemd Java 服务，否则会出现重复程序或路由混乱。

生产 Compose 用 `-f deploy/docker-compose.prod.yml -f deploy/docker-compose.app.yml` 合并。数据库、Redis、对象存储、前端入口都只映射服务器本机；后端没有主机端口。宝塔之外仅开放 22、80、443。

## 本机构建镜像，再发到 4 GiB 服务器

在 Windows 的 PowerShell 中，先把要上线的代码推送 GitHub，并从目标提交克隆；也可在当前工作区确认 `git status` 和目标提交后直接构建。Docker Desktop 必须运行 Linux 容器，服务器与本机目标架构都使用 `linux/amd64`。

在仓库根目录建立 `deploy/.env`（已被 Git 忽略），可从 `deploy/.env.example` 复制。除原有数据库和对象存储变量外，加入：

```dotenv
LUE_AUTH_SECRET=<至少32位随机字符串>
LUE_ADMIN_USERNAME=admin
LUE_ADMIN_PASSWORD=<管理员强密码>
LUE_FLYWAY_ENABLED=true
```

第一次部署的是空库，所以 Flyway 设为 `true`。保管 `.env`，不要放进镜像、GitHub 或镜像归档文件。然后构建并打包**程序镜像**：

```powershell
docker compose -p lue-erp --env-file deploy/.env -f deploy/docker-compose.prod.yml -f deploy/docker-compose.app.yml build backend frontend
docker save -o lue-erp-app-images.tar lue-erp-backend:local lue-erp-frontend:local
```

`lue-erp-app-images.tar` 可能很大，传输和服务器磁盘空间需预留余量。也可以将镜像推送到你自己的阿里云容器镜像服务，再由服务器拉取；不要把含凭证的 `.env` 推送仓库或注册表。

## 服务器导入和启动

先在服务器装好 Docker Engine 和 Compose v2，宝塔 Nginx 保持运行；完整系统安装步骤见 [阿里云部署方案](阿里云轻量服务器部署方案.md)。将两个 Compose 文件与本机镜像包上传：

```powershell
ssh root@<服务器公网IP> "mkdir -p /opt/lue-erp/deploy"
scp deploy/docker-compose.prod.yml deploy/docker-compose.app.yml root@<服务器公网IP>:/opt/lue-erp/deploy/
scp lue-erp-app-images.tar root@<服务器公网IP>:/opt/lue-erp/
```

服务器上创建 `/opt/lue-erp/deploy/.env`，填入和本机构建时同名的变量；数据库/对象存储密码可在服务器独立生成，应用镜像不含这些密码。`LUE_AUTH_SECRET` 必须至少 32 位。启动：

```bash
cd /opt/lue-erp
sudo chmod 600 deploy/.env
sudo docker load -i lue-erp-app-images.tar
sudo docker compose -p lue-erp --env-file deploy/.env -f deploy/docker-compose.prod.yml -f deploy/docker-compose.app.yml config --quiet
sudo docker compose -p lue-erp --env-file deploy/.env -f deploy/docker-compose.prod.yml -f deploy/docker-compose.app.yml up -d --no-build
sudo docker compose -p lue-erp --env-file deploy/.env -f deploy/docker-compose.prod.yml -f deploy/docker-compose.app.yml ps
sudo docker compose -p lue-erp --env-file deploy/.env -f deploy/docker-compose.prod.yml -f deploy/docker-compose.app.yml logs --tail=100 backend
curl -fsS http://127.0.0.1:8081/api/health
```

第一次启动会运行 Flyway 建表并创建管理员。确认登录成功后，把服务器 `.env` 中 `LUE_FLYWAY_ENABLED=false`，再 `sudo docker compose ... up -d --no-build backend` 重建后端容器。将来版本带数据库迁移时，先备份，再为那次升级开启 Flyway。`docker compose` 的 `-p lue-erp` 要一直相同，以便复用原有数据卷。

在宝塔「网站」建立反向代理到 `http://127.0.0.1:8081`，保持请求路径不变；域名和 SSL 在宝塔配置。先从浏览器打开站点，再检查登录、图片上传和 Excel 导入。

## 日常更新与备份

代码更新：本机构建新镜像 → `docker save` → 上传 → `docker load` → `docker compose ... up -d --no-build backend frontend`。使用 `:local` 标签时新镜像会替换旧镜像；更新前记录 Git 提交号并保留上一个镜像归档。若有数据库迁移，先备份数据库并审查迁移 SQL。

备份范围：`mysql_data`、`minio_data`、`backend_data`、服务器的 `deploy/.env`，以及目标 Git 提交号。不要执行 `docker compose down -v`，它会删除数据卷。服务器只有 50 GiB，镜像归档导入成功后可删除上传的 `.tar`；长期备份应放到服务器以外。
