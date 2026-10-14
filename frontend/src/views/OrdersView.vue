<template>
  <div class="page-stack">
    <div class="page-heading"><div><div class="eyebrow">SALES DATA</div><h1>订单数据</h1><p>订单导入、模式筛选和 SKU 归集情况</p></div></div>
    <el-card shadow="never">
      <div class="toolbar">
        <el-input v-model="shopId" placeholder="店铺 ID" style="max-width: 120px" />
        <el-input v-model="q" placeholder="平台 SKU / 内部 SKU / 平台订单号" clearable style="max-width: 320px" />
        <el-select v-model="mode" clearable placeholder="全部模式" style="width: 160px">
          <el-option label="JIT" value="JIT" /><el-option label="仓发 WAREHOUSE" value="WAREHOUSE" />
        </el-select>
        <el-date-picker v-model="dates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" />
        <el-button type="primary" @click="load">查询</el-button>
        <el-button @click="downloadTemplate">下载导入模板</el-button>
        <el-upload :show-file-list="false" accept=".xlsx" :before-upload="upload"><el-button>导入订单 Excel</el-button></el-upload>
      </div>
      <el-table :data="items" v-loading="loading" stripe>
        <el-table-column prop="orderDate" label="日期" width="120" />
        <el-table-column prop="shopName" label="店铺" />
        <el-table-column prop="platformOrderNo" label="平台订单号" />
        <el-table-column prop="platformSku" label="平台 SKU" />
        <el-table-column prop="internalSku" label="内部 SKU"><template #default="{ row }">{{ row.internalSku || '未匹配' }}</template></el-table-column>
        <el-table-column prop="orderMode" label="模式" width="140" />
        <el-table-column prop="quantity" label="销量" />
        <el-table-column prop="salesAmount" label="销售收入（元）" />
        <el-table-column prop="refundAmount" label="退款（元）" />
        <el-table-column prop="platformFee" label="平台费用（元）" />
      </el-table>
      <el-pagination v-model:current-page="page" v-model:page-size="pageSize" :total="total" layout="total, prev, pager, next" @current-change="load" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, getToken } from '../api'

type Item = { id: number; shopName: string; platformOrderNo: string; platformSku: string; internalSku: string; orderMode: string; orderDate: string; quantity: number; salesAmount: number; refundAmount: number; platformFee: number }
const items = ref<Item[]>([])
const q = ref('')
const shopId = ref('')
const mode = ref('')
const dates = ref<string[]>()
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const params = new URLSearchParams({ page: String(page.value), pageSize: String(pageSize.value) })
    if (q.value) params.set('q', q.value)
    if (shopId.value) params.set('shopId', shopId.value)
    if (mode.value) params.set('orderMode', mode.value)
    if (dates.value?.[0]) params.set('dateFrom', dates.value[0])
    if (dates.value?.[1]) params.set('dateTo', dates.value[1])
    const result = await api<{ items: Item[]; total: number }>(`/api/orders?${params}`)
    items.value = result.items
    total.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '读取失败')
  } finally {
    loading.value = false
  }
}

async function downloadTemplate() {
  try {
    const headers: Record<string, string> = {}
    const token = getToken()
    if (token) headers.Authorization = `Bearer ${token}`
    const response = await fetch('/api/orders/template', { headers })
    if (!response.ok) throw new Error(`模板下载失败（HTTP ${response.status}）`)
    const link = document.createElement('a')
    link.href = URL.createObjectURL(await response.blob())
    link.download = 'order_summary_import_template.xlsx'
    link.click()
    URL.revokeObjectURL(link.href)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '模板下载失败')
  }
}

async function upload(file: File) {
  const form = new FormData()
  form.append('file', file)
  try {
    const result = await api<{ importedRows: number; unmatchedRows: number }>('/api/orders/import', { method: 'POST', body: form })
    ElMessage.success(`导入 ${result.importedRows} 行，未匹配 ${result.unmatchedRows} 行已进入异常中心`)
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '导入失败')
  }
  return false
}

onMounted(load)
</script>
