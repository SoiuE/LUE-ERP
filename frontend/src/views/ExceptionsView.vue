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
        <el-table-column prop="exceptionType" label="异常类型" width="150" />
        <el-table-column prop="shopName" label="店铺" />
        <el-table-column prop="platformSku" label="店铺 SKU" />
        <el-table-column prop="title" label="异常说明" />
        <el-table-column prop="createdAt" label="创建时间" />
        <el-table-column label="操作" width="250"><template #default="{ row }">
          <el-button v-if="row.status === 'OPEN' && ['SKU_UNMATCHED','FULFILLMENT_SKU_UNMATCHED'].includes(row.exceptionType)" type="primary" link @click="open(row)">补充映射</el-button>
          <el-tag v-else-if="row.status === 'OPEN'" type="warning">待处理</el-tag>
          <el-tag v-else type="success">已重算</el-tag>
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
        <el-form-item label="默认订单模式"><el-select v-model="defaultOrderMode"><el-option label="JIT" value="JIT" /><el-option label="WAREHOUSE" value="WAREHOUSE" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存映射并重算</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'

type ExceptionItem = { id: number; exceptionNo: string; shopName: string; platformSku: string; title: string; status: string; createdAt: string; documentNo?:string; sourceDocumentId?:string; exceptionType:string }
type Sku = { id: number; internalSku: string; productName: string }
const items = ref<ExceptionItem[]>([])
const skus = ref<Sku[]>([])
const status = ref('OPEN')
const loading = ref(false)
const visible = ref(false)
const saving = ref(false)
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
