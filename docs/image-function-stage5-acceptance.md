# 图片功能阶段 5 验收记录

日期：2026-10-17

## 范围与结论

本阶段实现内部销售 SKU 和仓库货品的真实图片特征检索。浏览器只访问 ERP 后端；后端鉴权后调用独立 FastAPI 服务，并聚合对象资料与可用库存。搜索和“确认候选”均不写入店铺映射、组成清单、库存、单据或履约记录。

采用 OpenCLIP `ViT-B-32 / laion2b_s34b_b79k`、Python 3.11 FastAPI、SQLite 持久化向量和 NumPy 精确余弦检索。Qdrant、Milvus 与当前方案的比较、容量假设、模型下载、部署、重建和备份方式见 [图片搜索服务说明](../image-search/README.md)。

## 已交付

- 新增“以图搜商品”权限、菜单、路由和响应式页面，支持文件选择及手机拍摄入口。
- 前后端双重校验 JPEG/PNG/GIF、10 MiB、文件签名、解码和像素上限；查询图不进入图库。
- Spring 后端持有内部令牌并调用搜索服务；前端代码中没有搜索服务或向量索引地址。
- 返回搜索服务前 100 张图片命中后，后端按阈值过滤、按 SKU/货品合并多图并取最高分，最终严格限制 Top 10。
- 结果展示匹配度、对象类型、编码、名称、规格、标准图和库存信息，可跳转 SKU 详情或定位仓库货品。
- SKU 库存文字由当前生效组成中的货品可拣库存汇总展示；仓库货品展示可拣库存合计，没有创建新的 SKU 库存语义。
- 默认索引主图和商品实物图；包装图由配置开关控制；现场仓库照片不进入索引。
- 图片上传、归档、关联/用途变更、解除关联和停用会触发幂等索引 upsert/delete。
- 新增 `PROCESSING` 状态、尝试次数和最近尝试时间；提供失败重试、积压计数、全量重建和一致性备份。
- 搜索服务 503/超时返回清楚的 `IMAGE_SEARCH_UNAVAILABLE`，页面保留直接查询主数据的手工 SKU/货品搜索。
- 确认候选只更新浏览器页面中的选中状态，不调用写 API。

## 数据请求变化

| 调用 | 用途 | 写业务数据 |
|---|---|---|
| `POST /api/image-search/search` | 鉴权、查询图校验、内部相似检索、对象与库存聚合 | 否 |
| `GET /api/image-search/manual?q=` | 服务不可用时查询 SKU/货品主数据 | 否 |
| `GET /api/image-search/status` | 查看队列、失败与服务健康 | 否 |
| `POST /api/image-search/index/{id}/retry` | 失败图片重新排队 | 仅索引状态 |
| `POST /api/image-search/index/rebuild` | 清空搜索索引并重新排队合格图片 | 仅索引状态 |
| `POST /api/image-search/index/backup` | 生成 SQLite 在线备份 | 否 |
| `/internal/*` | Spring 后端以内部令牌调用 FastAPI | 仅向量索引 |

## 验证结果

| 命令 | 真实结果 |
|---|---|
| `cd frontend; npm run build` | 通过。`vue-tsc -b` 与 Vite 生产构建完成；仅有现有大 chunk 提示。 |
| `cd backend; mvn -q test` | 通过，退出码 0；14 个测试套件共 42 项，22 项执行通过、20 项需外部验收数据库而按既有条件跳过，失败 0、错误 0。包含新增低相似度过滤、重复对象合并、空结果和 Top 10 上限测试。 |
| `cd image-search; python -m compileall -q app tests` | 通过，Python 源码和测试文件语法检查成功。 |
| `$env:LUE_IMAGE_SEARCH_INTERNAL_TOKEN='verification-only-token'; docker compose -f deploy/docker-compose.yml config --quiet` | 通过，包含必填内部令牌检查的最终 Compose 配置有效。 |
| `cd backend; mvn -q -DskipTests package` | 通过，退出码 0，后端部署包生成成功。 |
| `docker compose -f deploy/docker-compose.yml build image-search` | 未完成。Docker 可用，但访问 Docker Hub 获取 `python:3.11-slim` 匿名令牌时网络连接超时，因此没有执行镜像内依赖安装、pytest 或真实模型下载。 |
| `git diff --check` | 通过；只有 Git 的 LF/CRLF 工作区提示，无空白错误。 |

Python 测试已编写：使用假的颜色特征验证内部令牌、索引、相似查询和重置；使用 NumPy 精确索引验证空索引、Top 限制、删除、重置和在线备份。由于本机只有 Python 3.14 且没有 FastAPI/NumPy，Docker 又无法拉取基础镜像，本次没有把这些 pytest 记录为已运行。

## 验收边界

- 离线/不可用：后端连接和读取均有时限，返回 503；手工搜索独立于图片服务，采购、收货、库存和履约接口未接入该服务。
- 空索引：搜索返回空集合及索引数量 0，页面提示管理员重建。
- 低相似度：低于配置阈值的命中被后端过滤，页面给出换图或手工搜索提示。
- 重复对象：同一 SKU/货品的多张命中只保留最高相似度图片。
- Top 10：搜索服务单次最多返回 200，Java 后端聚合后再次限制 10 个对象。
- 索引可追踪：状态、错误、版本、请求/完成/最近尝试时间与尝试次数均保存在 MySQL。
- 业务隔离：搜索服务无法直接访问 MySQL；搜索结果确认无后端写入；索引任务只读取图片并更新索引状态。

## 尚需部署环境验证

在允许访问 Docker Hub 和模型仓库的部署环境执行：

1. 构建 `image-search` 镜像并运行 `pytest`。
2. 首次启动下载 OpenCLIP 权重，确认 `/health` 返回 `ready=true`、维度 512。
3. 对两张已知相似商品图执行端到端查询，确认候选、详情跳转和库存聚合。
4. 停止图片服务验证 503 与手工回退，再恢复服务验证失败图片重试。
5. 执行全量重建和备份，核对积压归零、模型版本一致及备份文件可恢复。
