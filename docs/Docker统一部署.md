# Docker 统一部署与迁移

## 当前本机环境

`一键启动ERP.bat` 使用宿主机的 Java/Maven 和 Node/npm 启动后端与前端。当前机器上 `MySQL80` 与 `Redis` Windows 服务正在运行；此会话无法访问 Docker 引擎，因此不能确认容器数量。项目已有后端、前端 Dockerfile，以及基础设施和应用 Compose 文件。

## 统一启动

以下命令均在仓库根目录执行。首次没有 `deploy/.env` 时复制模板并修改所有 `CHANGE_ME` 项；已有该文件时不要覆盖：

```powershell
Copy-Item deploy\.env.full.example deploy\.env
```

本机数据库只有测试数据，使用独立项目名 `lue-erp-local` 创建全新的数据卷，不导入旧 MySQL 数据。新配置不发布 MySQL 与 Redis 宿主机端口，因此不会与当前 Windows 服务争用端口。设置 `LUE_FLYWAY_ENABLED=true`，让首次启动在空库建表。`deploy/.env` 只保存在本机，不要提交。

```powershell
docker compose -p lue-erp-local --env-file deploy\.env -f deploy\docker-compose.prod.yml -f deploy\docker-compose.app.yml config --quiet
docker compose -p lue-erp-local --env-file deploy\.env -f deploy\docker-compose.prod.yml -f deploy\docker-compose.app.yml up -d --build
docker compose -p lue-erp-local --env-file deploy\.env -f deploy\docker-compose.prod.yml -f deploy\docker-compose.app.yml ps
```

浏览器访问 `http://127.0.0.1:8081`。后端和前端都在容器内；MySQL 与 Redis 仅在 Compose 内网访问，RustFS 只绑定宿主机回环地址。后端业务文件保存在 `lue-erp-local_backend_data` 卷。首次空库建表完成后可将 Flyway 设回 `false`。

现有 `backend/data` 文件不会自动进入新卷；如果其中有需保留的非测试附件，应单独复制。切换前停止宿主机前后端进程，避免双实例写入。旧的 Windows MySQL、Redis 服务和旧 Docker 卷不会因上述命令而删除；确认新系统正常后再决定是否停用。

## 阿里云服务器

截图所示服务器是 Alibaba Cloud Linux 4、2 vCPU、4 GiB 内存、50 GiB 系统盘。建议在 Windows 的 Docker Desktop 上构建 Linux AMD64 应用镜像，服务器只拉取 MySQL、Redis 和 RustFS 基础镜像。先在服务器执行 `uname -m`，输出 `x86_64` 时按以下步骤操作。构建时使用当前源码，镜像包不要提交到 Git。

Windows PowerShell：

```powershell
$env:DOCKER_DEFAULT_PLATFORM = 'linux/amd64'
docker compose -p lue-erp-local --env-file deploy\.env -f deploy\docker-compose.prod.yml -f deploy\docker-compose.app.yml build backend frontend
docker save -o lue-erp-images.tar lue-erp-backend:local lue-erp-frontend:local
scp lue-erp-images.tar <SSH用户>@<服务器地址>:/opt/lue-erp/
```

将 `deploy/docker-compose.prod.yml`、`deploy/docker-compose.app.yml` 和服务器专用 `deploy/.env` 放在服务器 `/opt/lue-erp/deploy/`。服务器的密码和密钥独立生成，不要直接复制本机测试环境凭据。由于 Compose 的 `build.context` 使用相对路径，还需上传 `backend/` 和 `frontend/` 源码目录，或用同一仓库检出目标提交；`--no-build` 确保服务器不进行构建。首次部署在空库中设置 `LUE_FLYWAY_ENABLED=true`。

服务器 Shell：

```bash
cd /opt/lue-erp
docker load -i lue-erp-images.tar
docker compose -p lue-erp --env-file deploy/.env -f deploy/docker-compose.prod.yml -f deploy/docker-compose.app.yml config --quiet
docker compose -p lue-erp --env-file deploy/.env -f deploy/docker-compose.prod.yml -f deploy/docker-compose.app.yml up -d --no-build
docker compose -p lue-erp --env-file deploy/.env -f deploy/docker-compose.prod.yml -f deploy/docker-compose.app.yml ps
```

默认 Web 只监听 `127.0.0.1:8081`，由服务器 Nginx 或宝塔反向代理到该端口，再在 Nginx 配置域名、HTTPS 与访问控制。若直接开放容器 Web 端口，设置 `LUE_WEB_BIND=0.0.0.0` 与合适的 `LUE_WEB_PORT`，并配置安全组和防火墙。不要对公网开放 3306、6379、9000、9001。

当前测试数据无需迁移，因此阿里云也可从空库启动，首次启动设 `LUE_FLYWAY_ENABLED=true`。如果之后本地已产生正式业务数据，再迁往服务器时必须备份并恢复 MySQL、RustFS 和后端 `data`，并核对恢复结果。50 GiB 系统盘需持续监控 Docker 镜像、数据卷和日志占用；备份应保存到服务器外部。

旧的 [阿里云轻量服务器部署方案](阿里云轻量服务器部署方案.md)使用宿主机 Java 和 Nginx，可作为旧部署路径参考；全容器部署以本文件的组合 Compose 命令为准。

图片搜索服务在开发 Compose 中属于可选 `image-search` profile，尚未纳入这套生产组合；在 4 GiB 服务器上需要单独评估模型内存和索引迁移。当前统一部署覆盖 ERP 主应用及其三个基础服务。
