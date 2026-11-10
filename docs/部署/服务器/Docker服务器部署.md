# 阿里云服务器 Docker 部署与迁移

服务器通过 Docker Compose 运行前端、后端、MySQL、Redis 和 RustFS；宝塔 Nginx 提供公网入口。本地电脑全容器启动见 [Windows 本地全容器部署](../本地电脑/Docker本地部署.md)。

## 服务器部署

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

阿里云系统安装、宝塔 Nginx 和网络配置参考 [阿里云轻量服务器部署方案](阿里云轻量服务器部署方案.md)；全容器部署以本文件的组合 Compose 命令为准。

图片搜索服务已作为可选 `image-search` profile 写入应用 Compose；在 4 GiB 服务器上启用前，需要单独评估模型内存、磁盘容量和索引备份。默认启动仅覆盖 ERP 主应用及其三个基础服务。
