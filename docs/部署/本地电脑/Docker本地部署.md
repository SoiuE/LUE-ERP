# Windows 本地全容器部署

项目另有 [宿主机一键启动方式](一键启动与启动前准备.md)。本页只说明前端、后端、MySQL、Redis、RustFS 都由 Docker Compose 启动的方式。

## 准备

安装 Docker Desktop（Linux 容器模式、WSL 2 后端）和 Compose v2。在仓库根目录复制配置模板：

```powershell
Copy-Item deploy/.env.full.example deploy/.env
```

编辑 `deploy/.env`，替换所有 `CHANGE_ME`。已有该文件时不要覆盖。首次使用全新空库时设置 `LUE_FLYWAY_ENABLED=true`。此文件包含凭证，已被 Git 忽略。

## 启动

本地使用独立项目名 `lue-erp-local`，这样会创建独立数据卷。生产 Compose 不发布 MySQL 和 Redis 到 Windows 宿主机，避免与现有 Windows 服务争用端口。

```powershell
docker compose -p lue-erp-local --env-file deploy/.env -f deploy/docker-compose.prod.yml -f deploy/docker-compose.app.yml config --quiet
docker compose -p lue-erp-local --env-file deploy/.env -f deploy/docker-compose.prod.yml -f deploy/docker-compose.app.yml up -d --build
docker compose -p lue-erp-local --env-file deploy/.env -f deploy/docker-compose.prod.yml -f deploy/docker-compose.app.yml ps
```

浏览器访问 `http://127.0.0.1:8081`。后端业务文件位于 `lue-erp-local_backend_data` 卷；本机 `backend/data/` 不会自动导入。登录和功能确认后，可将 Flyway 设置回 `false`。停止服务使用相同参数执行 `docker compose ... down`，不要加 `-v`，否则会删除数据卷。

图片相似搜索是可选的 `image-search` profile。使用前在 `deploy/.env` 设置随机的 `LUE_IMAGE_SEARCH_INTERNAL_TOKEN`。构建优先使用 `image-search/wheelhouse/` 中完整的 PyTorch wheel；若只有 Git LFS 指针，则从 PyTorch 官方 CPU 软件源下载。网络不稳定时先执行 `git lfs pull -I image-search/wheelhouse/*.whl`，然后运行：

```powershell
docker compose -p lue-erp-local --env-file deploy/.env -f deploy/docker-compose.prod.yml -f deploy/docker-compose.app.yml --profile image-search up -d --build image-search backend
```

搜索模型首次启动还需下载权重，资源及模型准备参见 [图片搜索说明](../../../image-search/README.md)。
