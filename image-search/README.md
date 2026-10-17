# LUE ERP 图片相似检索服务

该服务只负责图片向量生成、索引和相似度计算。浏览器不调用本服务；Spring 后端完成用户鉴权、查询图校验、内部令牌认证、对象资料与库存聚合。

## 选型

- 模型：OpenCLIP `ViT-B-32`，权重 `laion2b_s34b_b79k`，CPU 推理，输出 512 维归一化向量。
- 服务：Python 3.11、FastAPI、单 Uvicorn worker。
- 索引：SQLite 持久化原始 `float32` 向量，NumPy 内存矩阵执行精确余弦相似度搜索。

当前精确索引每张图的裸向量约 2 KiB；50,000 张图约 100 MiB，另有 SQLite 与进程开销。它适合当前单机、小中型图库，且不用增加生产数据库或向量库。目录达到数十万图片、查询延迟无法接受或需要多副本写入时，将相同内部 API 的存储实现迁移到 Qdrant。

Qdrant 有成熟的持久化、过滤和快照能力，但需要新增一个受保护的服务、卷、认证、监控和备份流程。Milvus Standalone 资源与运维更重；Milvus Lite 适合本地嵌入，单目录并发和服务隔离能力不适合作为本项目独立服务的收益点。因此本阶段采用 SQLite + NumPy。

参考资料：

- OpenCLIP 模型与预训练权重：<https://github.com/mlfoundations/open_clip>
- Qdrant 快照：<https://qdrant.tech/documentation/operations/snapshots/>
- Milvus Lite 限制：<https://github.com/milvus-io/milvus-lite/blob/main/README.md>

## 资源和部署

- 首次启动需要访问模型仓库并下载约 350 MiB 权重；CPU 版 PyTorch 镜像和依赖还需要数 GiB 磁盘空间。
- 建议至少 2 个 CPU 核、4 GiB 可用内存；并发量增大后再根据实测调整。
- `/models` 保存模型缓存，`/data` 保存 SQLite 索引，`/backups` 保存在线备份。
- 必须设置随机且足够长的 `LUE_IMAGE_SEARCH_INTERNAL_TOKEN`，并让 Spring 后端使用相同值。
- 当前 Compose 把端口仅绑定到 `127.0.0.1:8090`，用于宿主机运行的 Spring 后端。后端容器化后应去掉宿主端口，在 Compose 私有网络中使用 `http://image-search:8090`。
- 无外网部署应在发布环境预下载并随受控模型缓存卷分发权重；模型及权重名称保持不变，避免索引版本漂移。

```powershell
$env:LUE_IMAGE_SEARCH_INTERNAL_TOKEN = '<随机内部令牌>'
docker compose -f deploy/docker-compose.yml build image-search
docker compose -f deploy/docker-compose.yml up -d image-search
```

构建测试目标并运行不下载模型权重的单元/API 测试：

```powershell
docker build --target test -t lue-image-search-test image-search
docker run --rm lue-image-search-test
```

## 索引生命周期

Spring 后端是队列状态的事实来源。活动图片在上传、归档、关联、用途调整、解除关联或停用后进入 `PENDING`；后台每批最多处理 10 张。服务端按图片资产 ID 幂等 upsert 或 delete，重复提交不会产生副本。处理中断超过 5 分钟会重新排队。

默认只索引 `MAIN_IMAGE` 和 `PRODUCT_PHOTO`。设置 `LUE_IMAGE_SEARCH_INCLUDE_PACKAGING=true` 后也索引 `PACKAGING`。`WAREHOUSE_PHOTO` 等现场附件始终不进入默认索引。

管理页面可以查看 `NOT_INDEXED / PENDING / PROCESSING / INDEXED / FAILED` 数量、失败原因和尝试次数，并执行单张重试、全量重建和在线备份。全量重建先清空搜索索引，再从 MySQL 中符合条件的活动图片重新排队；MinIO 图片和 MySQL 元数据才是可重建的事实来源。

## 备份和恢复

- 将 MySQL 和 MinIO 纳入现有业务备份；这是完整恢复的必要部分。
- 在线备份通过管理页面调用 SQLite backup API，在 `/backups` 生成一致性文件，再由宿主备份系统复制 `image_search_backups` 卷。
- 恢复到相同模型版本时可停服务后替换 `/data/image-index.sqlite`；更稳妥的灾难恢复方式是启动空索引并执行全量重建。
- 更改模型或权重后必须执行全量重建，不能复用旧向量。

## 查询图处理

Spring 与 FastAPI 都校验 MIME、文件签名、可解码性、10 MiB 大小和 2,000 万像素上限。查询图只在请求内存和框架受管的上传流中使用，请求结束后释放，不写入商品图库、附件或索引。
