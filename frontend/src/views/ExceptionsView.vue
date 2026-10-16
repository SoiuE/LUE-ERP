<template>
  <div class="page-stack">
    <div class="page-heading"><div><div class="eyebrow">EXCEPTION CENTER</div><h1>异常中心</h1><p>补充店铺 SKU 映射后，系统自动重算关联订单</p></div></div>
    <el-card shadow="never">
      <div class="toolbar">
        <el-select v-model="status" style="width: 160px" @change="load">
          <el-option label="待处理" value="OPEN" /><el-option label="已解决" value="RESOLVED" />
        </el-select>
        <el-button @click="load">刷新</el-button>
      </div>
      <el-table :data="items" v-loading="loading">
        <el-table-column prop="exceptionNo" label="异常单号" width="180" />
        <el-table-column prop="exceptionType" label="异常类型" width="190"><template #default="{row}">{{ exceptionTypeText(row.exceptionType) }}</template></el-table-column>
        <el-table-column prop="shopName" label="店铺" />
        <el-table-column prop="platformSku" label="店铺 SKU" />
        <el-table-column prop="title" label="异常说明" />
        <el-table-column prop="createdAt" label="创建时间" min-width="180"><template #default="{row}">{{ formatChinaDateTime(row.createdAt) }}</template></el-table-column>
        <el-table-column label="操作" width="250"><template #default="{ row }">
          <el-button v-if="row.status === 'OPEN' && ['SKU_UNMATCHED','FULFILLMENT_SKU_UNMATCHED'].includes(row.exceptionType)" type="primary" link @click="open(row)">补充映射</el-button>
          <el-button v-else-if="row.status === 'OPEN'" type="primary" link @click="openResolve(row)">处理异常</el-button>
          <el-tooltip v-else-if="row.resolutionNote" :content="row.resolutionNote" placement="top"><el-tag type="success">已处理</el-tag></el-tooltip>
          <el-tag v-else type="success">已解决</el-tag>
        </template></el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="visible" title="补充店铺 SKU 映射" width="460px">
      <el-form label-position="top">
        <el-form-item label="店铺 SKU"><el-input :model-value="active?.platformSku" disabled /></el-form-item>
        <el-form-item label="内部 SKU">
          <el-select v-model="skuId" filterable placeholder="搜索内部 SKU" style="width: 100%">
            <el-option v-for="sku in skus" :key="sku.id" :label="`${sku.internalSku} · ${sku.productName}`" :value="sku.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="默认订单模式"><el-select v-model="defaultOrderMode"><el-option label="JIT（即时履约）" value="JIT" /><el-option label="仓发（WAREHOUSE）" value="WAREHOUSE" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存映射并重算</el-button></template>
    </el-dialog>

    <el-dialog v-model="resolveVisible" title="处理异常" width="460px">
      <p class="resolve-summary">{{ active?.title || exceptionTypeText(active?.exceptionType || '') }}</p>
      <p class="resolve-hint">{{ resolveHint(active?.exceptionType || '') }}</p>
      <el-form label-position="top">
        <el-form-item label="处理说明" required>
          <el-input v-model="resolutionNote" type="textarea" :rows="4" maxlength="1000" show-word-limit placeholder="填写处理方式或处理结果" />
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="resolveVisible = false">取消</el-button><el-button type="primary" :loading="resolving" @click="resolve">确认处理</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { formatChinaDateTime } from '../utils/chinaTime'

type ExceptionItem = { id: number; exceptionNo: string; shopName: string; platformSku: string; title: string; status: string; createdAt: string; documentNo?:string; sourceDocumentId?:string; exceptionType:string; resolutionNote?:string }
type Sku = { id: number; internalSku: string; productName: string }
const items = ref<ExceptionItem[]>([])
const skus = ref<Sku[]>([])
const status = ref('OPEN')
const exceptionTypes:Record<string,string>={SKU_UNMATCHED:'订单 SKU 未匹配',FULFILLMENT_SKU_UNMATCHED:'履约 SKU 未匹配',PACKAGE_SHORTAGE:'包材库存不足',FULFILLMENT_PACKAGE_SHORTAGE:'履约包材库存不足',FULFILLMENT_STOCK_SHORTAGE:'履约商品缺货',FULFILLMENT_PLATFORM_CHANGED:'平台数据变更待核对'}
function exceptionTypeText(value:string){return exceptionTypes[value]||'其他异常'}
function resolveHint(value:string){
  if(['PACKAGE_SHORTAGE','FULFILLMENT_PACKAGE_SHORTAGE'].includes(value))return '请先在包材库存中完成补货或库存处理，再记录结果并结案；此操作不会直接修改库存。'
  if(value==='FULFILLMENT_STOCK_SHORTAGE')return '请先在本地发货履约中处理缺货（必要时关联采购并入库），再记录结果并结案。'
  if(value==='FULFILLMENT_PLATFORM_CHANGED')return '请先核对平台数据与本地履约进度；结案不会覆盖已完成的本地操作。'
  return '请先在对应业务模块完成实际处理，再填写处理结果。'
}
const loading = ref(false)
const visible = ref(false)
const saving = ref(false)
const resolveVisible = ref(false)
const resolving = ref(false)
const resolutionNote = ref('')
const active = ref<ExceptionItem>()
const skuId = ref<number>()
const defaultOrderMode = ref('JIT')

async function load() {
  loading.value = true
  try {
    items.value = (await api<{ items: ExceptionItem[] }>(`/api/exceptions?status=${status.value}`)).items
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '读取失败')
  } finally {
    loading.value = false
  }
}

function open(row: ExceptionItem) {
  active.value = row
  skuId.value = undefined
  visible.value = true
}

function openResolve(row: ExceptionItem) {
  active.value = row
  resolutionNote.value = ''
  resolveVisible.value = true
}

async function resolve() {
  if (!active.value) return
  const note = resolutionNote.value.trim()
  if (!note) {
    ElMessage.warning('请填写处理说明')
    return
  }
  resolving.value = true
  try {
    await api(`/api/exceptions/${active.value.id}/resolve`, {
      method: 'POST',
      body: JSON.stringify({ resolutionNote: note }),
    })
    ElMessage.success('异常已处理')
    resolveVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '处理失败')
  } finally {
    resolving.value = false
  }
}

async function save() {
  if (!active.value || !skuId.value) return
  saving.value = true
  try {
    const result = await api<{ recalculatedRows: number }>(`/api/exceptions/${active.value.id}/map`, {
      method: 'POST',
      body: JSON.stringify({ skuId: skuId.value, defaultOrderMode: defaultOrderMode.value }),
    })
    ElMessage.success(`映射已保存，重新匹配 ${result.recalculatedRows} 条记录`)
    visible.value = false
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  await load()
  try {
    skus.value = (await api<{ items: Sku[] }>('/api/master/skus?page=1&pageSize=100')).items
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'SKU 列表读取失败')
  }
})
</script>

<style scoped>
.resolve-summary { margin: 0 0 8px; color: var(--el-text-color-secondary); }
.resolve-hint { margin: 0 0 16px; color: var(--el-color-warning-dark-2); font-size: 13px; line-height: 1.6; }
</style>
