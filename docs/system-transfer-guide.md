# LUE ERP 双 Windows 电脑迁移与同步指南

本文记录如何在两台 Windows 电脑之间迁移、启动和持续同步 LUE ERP，包括以图搜图服务的构建、模型准备和网络异常处理。项目远程仓库为 <https://github.com/SoiuE/LUE-ERP>。

## 1. 同步内容与本机内容

Git 仓库保存源代码、项目文档、模板和已提交的测试导入文件。超过 GitHub 普通文件大小限制的安装包、Python wheel 或其他离线文件必须通过 Git LFS 保存，或者使用受控的离线介质单独传输。克隆前先确认仓库是否使用了 Git LFS；如果使用，新电脑必须安装 Git LFS 才能取得实际文件。

以图搜图镜像当前会从 `image-search/wheelhouse/` 读取本地 CPU 版 PyTorch wheel。该文件约 175 MiB，不能作为普通 Git 文件推送到 GitHub。若决定通过仓库传输，应在旧电脑上执行下面的命令并提交生成的 `.gitattributes`；若不通过 Git LFS，则必须把整个 `image-search/wheelhouse/` 目录用移动硬盘等受控方式复制到新电脑的相同路径。

```powershell
git lfs install
git lfs track "image-search/wheelhouse/*.whl"
git add .gitattributes image-search/wheelhouse/*.whl
git commit -m "Add offline PyTorch wheel for image search"
git push
```

提交后用 `git lfs ls-files` 确认 wheel 已被 LFS 跟踪。不要直接对 100 MiB 以上的 wheel 执行普通 `git add` 后推送。

本机 `.env`、构建目录、依赖目录、日志以及安装说明中包含的账号信息不进入仓库。另一台电脑需要从 `.env.example` 复制自己的配置文件并自行填写。

需要区分测试导入文件和数据库：仓库里的 Excel 测试文件会同步，但每台电脑 Docker 中的 MySQL、Redis 和对象存储数据各自独立。Excel 文件对应的导入批次、数据库业务记录、用户密码和图片文件等不会因为 Git 同步而迁移。如需复制数据库，请使用 MySQL Workbench 导出/导入数据库，或采用经确认的数据库备份流程；不要把含真实信息的数据库备份提交到 GitHub。

## 2. 首台电脑：首次上传

本项目已经完成首次初始化和上传。首次提交为 `94e611a`，主分支为 `main`，远程名为 `origin`。以后无需再次 `git init`。

如果在一台新的项目副本中首次配置仓库，可参考以下命令。仅对尚未初始化 Git 的项目使用 `git init`：

```powershell
cd "D:\LUE ERP"
git init -b main
git lfs install
git lfs track "app/*.exe"
git add .gitattributes .gitignore
git add .
git status
git commit -m "Initial project snapshot"
git remote add origin https://github.com/SoiuE/LUE-ERP.git
git push -u origin main
```

如果 `origin` 已存在，先查看地址：

```powershell
git remote -v
```

必要时更新地址，而不是重复添加：

```powershell
git remote set-url origin https://github.com/SoiuE/LUE-ERP.git
```

提交前确认 `.env`、密码、个人账号说明和本机业务数据没有进入暂存区：

```powershell
git status --short
git check-ignore backend\.env deploy\.env
```

本项目的测试导入文件已按用户确认上传。以后如需提交新的测试文件，提交前仍要核对内容，确认不含真实客户、凭证或其他不应共享的信息。

注意：`.gitignore` 忽略整个 `backend/data/` 运行数据目录。若以后明确需要把新的测试导入文件放入仓库，使用 `git add -f` 指定文件，例如：

```powershell
git add -f backend/data/imports/2026-10-15/example.xlsx
```

只添加确认过的测试文件，不要用 `git add -f backend/data/` 整体加入运行目录。

## 3. 第二台电脑：克隆并启动

### 3.1 安装开发环境

准备以下工具：

- Git for Windows 和 Git LFS
- JDK 17 或更高版本
- Maven 3.9 或更高版本
- Node.js 20 或更高版本，以及 npm 10 或更高版本
- Docker Desktop（需要在本机运行 MySQL、Redis、对象存储时）

如果安装程序或以图搜图离线 wheel 已通过 Git LFS 提交，新电脑还要安装 Git LFS。也可以提前从官方来源安装 Docker Desktop、Git、JDK、Maven 和 Node.js，只通过 Git 获取项目代码。

### 3.2 克隆项目

打开 PowerShell：

```powershell
cd D:\
git lfs install
git clone https://github.com/SoiuE/LUE-ERP.git
cd D:\LUE-ERP
git lfs pull
```

检查安装包是否已下载为实际文件，而不是几行文本的 LFS 指针：

```powershell
git lfs ls-files
Get-ChildItem image-search\wheelhouse\*.whl | Select-Object Name,Length
```

当前 Dockerfile 需要 `image-search/wheelhouse/torch-2.5.1+cpu-cp311-cp311-linux_x86_64.whl`。如果该文件不存在、文件大小异常，或内容仍是 LFS 指针，图片搜索镜像将无法构建。

### 3.3 创建本机环境配置

```powershell
Copy-Item deploy\.env.example deploy\.env
Copy-Item backend\.env.example backend\.env
```

两个 `.env` 文件只保存在当前电脑，不要提交。打开 `deploy\.env` 和 `backend\.env`，根据项目实际运行方式填写相同的数据库名、用户名和密码。

使用本项目 Docker Compose 默认配置时，`deploy\.env.example` 中的数据库用户是 `lue_erp`，密码是 `lue_erp_password`；而 `backend\.env.example` 使用 `lue_erp_app` 和 `123456`，两者默认并不匹配。可选择：

1. 将 `backend\.env` 的 `LUE_DB_USERNAME` 和 `LUE_DB_PASSWORD` 改成 `deploy\.env` 中的 `MYSQL_USER` 和 `MYSQL_PASSWORD`；或
2. 按仓库根目录 `README.md` 的说明，在 MySQL 中创建应用账号，并把该账号密码写入 `backend\.env`。

不要删除已有数据库卷来解决账号或密码问题；那样可能丢失本机数据。

### 3.4 启动基础设施、后端和前端

先启动 Docker Desktop，再从项目根目录启动基础设施：

```powershell
docker compose --env-file deploy\.env -f deploy\docker-compose.yml up -d
```

在第一个 PowerShell 窗口启动后端：

```powershell
cd D:\LUE-ERP\backend
mvn spring-boot:run
```

在第二个 PowerShell 窗口安装并启动前端：

```powershell
cd D:\LUE-ERP\frontend
npm ci
npm run dev
```

浏览器打开 <http://localhost:5173>。后端健康检查地址为 <http://localhost:8080/api/health>。首次启动后端时，Flyway 会执行数据库迁移。

### 3.5 部署以图搜图服务

以图搜图是 Compose 中名为 `image-search` 的可选服务，默认 profile 不会自动启动。它使用 CPU 版 OpenCLIP `ViT-B-32` 模型、SQLite 向量索引和三个 Docker 卷：

- `image_search_models`：模型权重缓存；
- `image_search_data`：向量索引；
- `image_search_backups`：在线索引备份。

建议为新电脑预留至少 2 个 CPU 核、4 GiB 可用内存以及数 GiB 磁盘空间。当前镜像面向 Linux `x86_64` 容器；Docker Desktop 应使用 Linux containers。

先在 `deploy\.env` 中增加以下配置。内部令牌应使用足够长的随机值，不要沿用示例值：

```properties
LUE_IMAGE_SEARCH_INTERNAL_TOKEN=请替换为随机长令牌
LUE_IMAGE_SEARCH_MODEL=ViT-B-32
LUE_IMAGE_SEARCH_PRETRAINED=laion2b_s34b_b79k
```

在 `backend\.env` 中使用完全相同的令牌，并让宿主机运行的 Spring 后端访问本机映射端口：

```properties
LUE_IMAGE_SEARCH_URL=http://127.0.0.1:8090
LUE_IMAGE_SEARCH_INTERNAL_TOKEN=请替换为与deploy环境相同的随机长令牌
LUE_IMAGE_INDEX_POLL_DELAY_MS=3000
LUE_IMAGE_SEARCH_MINIMUM_SCORE=0.20
LUE_IMAGE_SEARCH_INCLUDE_PACKAGING=false
```

从项目根目录构建并启动。显式指定项目名 `lue-erp`，可以让后续镜像和数据卷名称保持固定：

```powershell
docker compose -p lue-erp --env-file deploy\.env -f deploy\docker-compose.yml --profile image-search build image-search
docker compose -p lue-erp --env-file deploy\.env -f deploy\docker-compose.yml --profile image-search up -d mysql redis minio minio-init image-search
```

服务第一次启动时还会下载约 350 MiB 的 OpenCLIP 模型权重，下载完成后保存在 `lue-erp_image_search_models` 卷中。镜像显示为已启动不代表模型已经可用；用下面的命令检查日志和健康状态：

```powershell
docker compose -p lue-erp --env-file deploy\.env -f deploy\docker-compose.yml logs --tail 100 image-search
Invoke-RestMethod http://127.0.0.1:8090/health
```

健康接口返回 `ready: true` 后，再启动或重启后端。随后在系统的以图搜图管理页面检查索引状态；迁移了 MySQL 和 MinIO、但没有迁移向量索引时，可在确认图片均可读取后执行全量重建。模型名称或预训练权重发生变化时也必须全量重建，不能继续使用旧向量。

以图搜图端口仅绑定 `127.0.0.1:8090`，无需向局域网客户端开放。员工浏览器只访问前端和后端，后端负责鉴权并调用图片搜索服务。

## 4. 两台电脑日常开发与同步

### 开始工作前

先进入项目目录，检查是否有未提交改动，再拉取远程更新：

```powershell
cd "D:\LUE ERP"
git status
git pull --rebase
git lfs pull
```

另一台电脑克隆目录可能是 `D:\LUE-ERP`，请按该电脑的实际路径调整。

### 完成工作后

```powershell
git status
git add .
git status
git commit -m "描述本次改动"
git push
```

看到 `git push` 成功结束后，再在另一台电脑执行 `git pull --rebase`。尽量不要同时修改同一文件。不要用 U 盘覆盖整个项目目录；使用 Git 拉取和推送可以保留双方的提交历史。

### 两台电脑都有未提交改动时

优先在当前电脑提交自己的改动，然后拉取远程更新并处理冲突。若暂时不适合提交，可先暂存工作区：

```powershell
git stash push -u -m "临时保存本机改动"
git pull --rebase
git stash pop
```

如果出现冲突，打开冲突文件，保留需要的内容并删除 `<<<<<<<`、`=======`、`>>>>>>>` 标记，然后：

```powershell
git add <已解决的文件路径>
git rebase --continue
```

如果没有正在进行的 rebase，直接提交解决后的改动即可。不要在不了解影响时使用 `git reset --hard`，它会丢弃未提交改动。

## 5. 常见问题

### `Failed to connect to github.com port 443`

这是网络连接问题，发生在 GitHub 登录之前。确认浏览器能打开 GitHub，检查当前网络、防火墙以及 VPN/代理是否已连接。若使用本地 HTTP 代理，在代理软件里查看实际的 HTTP 或混合端口，不要猜端口号。

例如实际代理地址是 `127.0.0.1`、端口是 `7892` 时：

```powershell
git config --global http.proxy http://127.0.0.1:7892
curl.exe -I -x http://127.0.0.1:7892 https://github.com
git push
```

`Test-NetConnection github.com -Port 443` 测的是直连，不会使用 Git 的代理配置；所以在代理模式下它仍显示失败，并不能单独说明 Git 代理失败。可先检查本地代理端口：

```powershell
Test-NetConnection 127.0.0.1 -Port 7892
```

将 `7892` 替换为代理软件显示的实际端口。若不再使用代理，移除 Git 配置：

```powershell
git config --global --unset http.proxy
```

### GitHub 提示文件大于 100 MB 或 LFS 相关错误

不要把 `.exe` 直接当普通 Git 文件提交。本项目通过 `.gitattributes` 把 `app/*.exe` 交给 Git LFS 管理。每台电脑先安装并初始化 LFS：

```powershell
git lfs install
git lfs pull
```

如果在没有 Git LFS 的电脑上克隆，安装包可能只显示为很小的指针文件。安装 LFS 后运行 `git lfs pull` 获取实际文件。

### 后端提示数据库连接失败或 Access denied

确认 Docker Desktop 已运行，容器状态正常，并核对 `backend\.env` 中的数据库 URL、用户名和密码。数据库账号密码必须与 Compose 创建的账号一致，或按根目录 README 创建应用账号。查看容器状态：

```powershell
docker compose --env-file deploy\.env -f deploy\docker-compose.yml ps
```

改动 `deploy\.env` 中的 MySQL 初始化变量不会自动更改已经初始化的数据卷里的账号密码。若是已有数据库，先确认数据和账号状态，不要删除卷。

### 前端提示找不到 npm 包或依赖不一致

在 `frontend` 目录运行 `npm ci`。此命令根据已提交的 `package-lock.json` 安装依赖；不要把 `node_modules` 复制或提交到 Git。

### 以图搜图镜像因网络卡顿构建失败

先用详细构建日志确认失败发生在哪一步：

```powershell
docker compose -p lue-erp --env-file deploy\.env -f deploy\docker-compose.yml --profile image-search build --progress=plain image-search
```

常见情况及处理方式如下：

1. 如果失败在拉取 `python:3.11-slim`，问题发生在 Docker Hub。先在 Docker Desktop 的代理设置中填写实际可用的 HTTP/HTTPS 代理，重启 Docker Desktop，再单独执行 `docker pull python:3.11-slim`。公司网络使用镜像加速器时，应在 Docker Desktop 的 Docker Engine 配置中填写由网络管理员提供的可信 registry mirror。
2. 如果失败在安装 `torchvision` 或 `requirements.txt`，问题发生在 pip 下载。当前 Dockerfile 已将超时提高到 300 秒并重试 10 次，同时从 `image-search/wheelhouse/` 安装最大的 PyTorch wheel。先确认本地 wheel 完整，再重新构建；Docker 会复用成功的缓存层。PowerShell 中配置的 `HTTP_PROXY` 不一定自动传入 Docker 构建环境，优先配置 Docker Desktop 自身的代理。
3. 如果日志出现证书校验失败，不要使用关闭 TLS 校验的方式绕过。应把公司代理的根证书正确配置到 Docker Desktop/容器信任链，或改用不拦截 TLS 的网络。
4. 如果镜像已构建成功，但 `/health` 长时间返回 503，查看 `docker compose ... logs image-search`。这通常是首次启动下载模型权重失败，与镜像构建失败是两件事。恢复网络后重启 `image-search`，它会继续使用模型缓存卷；模型下载完成前不要执行全量索引。

网络长期不稳定时，建议在网络正常的旧电脑上构建镜像并下载模型，然后离线搬运镜像和模型缓存。以下命令使用固定项目名，避免两台电脑的镜像和卷名称不同。

旧电脑执行：

```powershell
docker compose -p lue-erp --env-file deploy\.env -f deploy\docker-compose.yml --profile image-search build image-search
docker compose -p lue-erp --env-file deploy\.env -f deploy\docker-compose.yml --profile image-search up -d image-search
Invoke-RestMethod http://127.0.0.1:8090/health
docker pull python:3.11-slim
docker save -o lue-erp-image-search.tar lue-erp-image-search:latest python:3.11-slim
docker run --rm -v lue-erp_image_search_models:/source -v "${PWD}:/transfer" python:3.11-slim sh -c "tar -C /source -czf /transfer/lue-erp-image-search-models.tar.gz ."
```

把 `lue-erp-image-search.tar` 和 `lue-erp-image-search-models.tar.gz` 复制到新电脑的项目根目录。新电脑执行：

```powershell
docker load -i .\lue-erp-image-search.tar
docker volume create lue-erp_image_search_models
docker run --rm -v lue-erp_image_search_models:/target -v "${PWD}:/transfer" python:3.11-slim sh -c "tar -C /target -xzf /transfer/lue-erp-image-search-models.tar.gz"
docker compose -p lue-erp --env-file deploy\.env -f deploy\docker-compose.yml --profile image-search up -d --no-build image-search
Invoke-RestMethod http://127.0.0.1:8090/health
```

镜像归档只包含程序和 Python 依赖，不包含模型卷、向量索引、MySQL 或 MinIO 数据，所以模型缓存必须单独传输。生产业务恢复仍应以 MySQL 和 MinIO 备份为基础；向量索引可以迁移，也可以在新电脑上重新生成。

### Git 提示 `nothing to commit` 或 `Everything up-to-date`

这通常表示没有新改动需要提交，或本地分支已经与远程同步。检查：

```powershell
git status
git log -1 --oneline
git branch -vv
```

### `.env` 没有出现在另一台电脑

这是预期行为。`.env` 含本机连接信息且被 `.gitignore` 排除。使用 `.env.example` 复制生成，然后在每台电脑分别填写本机配置。

## 6. 核对清单

- `git status` 显示工作区干净，或只包含计划提交的改动。
- 两台电脑的开发依赖版本满足根目录 README 的要求。
- 克隆前已运行 `git lfs install`，安装包已通过 `git lfs pull` 获取。
- 两台电脑分别创建了自己的 `deploy\.env` 和 `backend\.env`。
- `backend\.env` 的数据库连接配置和本机 Docker MySQL 账号一致。
- 访问前端 `http://localhost:5173`，后端健康检查 `http://localhost:8080/api/health`。
- 已理解 Git 同步代码和文件，不会自动同步 Docker 数据库内容。
- 已确认 `image-search/wheelhouse/` 中的 PyTorch wheel 存在且完整，或已通过受控介质复制。
- `deploy\.env` 与 `backend\.env` 使用相同的图片搜索内部令牌。
- `image-search` 健康接口返回 `ready: true`，模型卷、索引卷和备份卷已纳入迁移或重建计划。
