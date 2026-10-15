# LUE ERP 双 Windows 电脑迁移与同步指南

本文记录如何在两台 Windows 电脑之间迁移、启动和持续同步 LUE ERP。项目远程仓库为 <https://github.com/SoiuE/LUE-ERP>。

## 1. 同步内容与本机内容

Git 仓库保存源代码、项目文档、模板和已提交的测试导入文件。三个安装包通过 Git LFS 保存：Docker Desktop、VS Code 和肥猫云安装包。克隆前要安装 Git LFS，才能下载这些大文件。

本机 `.env`、构建目录、依赖目录、日志以及安装说明中包含的账号信息不进入仓库。另一台电脑需要从 `.env.example` 复制自己的配置文件并自行填写。

需要区分测试导入文件和数据库：仓库里的 Excel 测试文件会同步，但每台电脑 Docker 中的 MySQL、Redis 和 MinIO 数据各自独立。Excel 文件对应的导入批次、数据库业务记录、用户密码等不会因为 Git 同步而迁移。如需复制数据库，请使用 MySQL Workbench 导出/导入数据库，或采用经确认的数据库备份流程；不要把含真实信息的数据库备份提交到 GitHub。

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
- Docker Desktop（需要在本机运行 MySQL、Redis、MinIO 时）

项目内的 `app` 目录也包含 Docker Desktop、VS Code 和肥猫云安装程序。这些安装包较大，已由 Git LFS 跟踪。

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
Get-ChildItem app\*.exe | Select-Object Name,Length
```

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
