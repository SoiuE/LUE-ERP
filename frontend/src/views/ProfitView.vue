<template>
  <div class="page-stack profit-page">
    <div class="page-heading"><div><div class="eyebrow">PROFIT ANALYTICS</div><h1>SKU 利润报表</h1><p>按日预计算落表，支持 SKU、店铺、订单模式和月份维度查看。</p></div></div>

    <el-card shadow="never" class="profit-controls">
      <div class="filter-row">
        <el-select v-model="dimension" style="width:160px" @change="load"><el-option label="SKU 日利润" value="daily"/><el-option label="SKU 月利润" value="monthly"/><el-option label="店铺利润" value="shop"/><el-option label="订单模式利润" value="mode"/></el-select>
        <el-date-picker v-model="dates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期"/>
        <el-select v-model="shopId" clearable filterable placeholder="全部店铺" style="width:180px"><el-option v-for="shop in shops" :key="shop.id" :label="shop.shopName" :value="String(shop.id)"/></el-select>
        <el-select v-model="skuId" clearable filterable placeholder="全部 SKU" style="width:200px"><el-option v-for="sku in skus" :key="sku.id" :label="`${sku.internalSku} · ${sku.productName}`" :value="String(sku.id)"/></el-select>
        <el-select v-model="orderMode" clearable placeholder="全部模式" style="width:150px"><el-option label="JIT" value="JIT"/><el-option label="仓发 WAREHOUSE" value="WAREHOUSE"/></el-select>
        <el-input v-model="q" clearable placeholder="SKU 或商品名称" style="width:190px" @keyup.enter="load"/>
        <el-button type="primary" @click="load">查询</el-button>
      </div>
      <div class="action-row">
        <el-button v-if="canRecalculate" :icon="Refresh" :disabled="!dates?.[0]||!dates?.[1]" @click="recalculate">手动重算日期范围</el-button>
        <el-button :icon="Download" @click="exportReport">导出 Excel</el-button>
        <el-checkbox v-if="canImport" v-model="forceReimport">强制重导入同一文件</el-checkbox>
        <el-upload v-if="canImport" :show-file-list="false" accept=".xlsx" :before-upload="uploadSettlement"><el-button>导入结算 Excel</el-button></el-upload>
        <el-upload v-if="canImport" :show-file-list="false" accept=".xlsx" :before-upload="uploadOtherCosts"><el-button>导入其他费用 Excel</el-button></el-upload>
        <el-button v-if="canImport" text @click="downloadTemplate('settlement')">结算模板</el-button>
        <el-button v-if="canImport" text @click="downloadTemplate('other_cost')">其他费用模板</el-button>
      </div>
      <el-alert type="info" :closable="false" show-icon title="采购成本按 SKU 当前采购价 × 销量；包材成本只计有库存流水关联的实际 JIT 消耗；结算费用优先采用结算导入。" />
    </el-card>

    <el-card shadow="never">
      <el-table :data="items" v-loading="loading" stripe>
        <el-table-column v-if="dimension==='daily'" prop="reportDate" label="日期" width="120"/>
        <el-table-column v-if="dimension==='monthly'" prop="reportMonth" label="月份" width="120"/>
        <el-table-column v-if="['daily','monthly','shop'].includes(dimension)" prop="shopName" label="店铺" min-width="150"/>
        <el-table-column v-if="['daily','monthly'].includes(dimension)" prop="internalSku" label="内部 SKU" min-width="150"/>
        <el-table-column v-if="['daily','monthly'].includes(dimension)" prop="productName" label="商品名称" min-width="180" show-overflow-tooltip/>
        <el-table-column v-if="['daily','monthly','mode'].includes(dimension)" prop="orderMode" label="订单模式" width="140"><template #default="{row}">{{ row.orderMode==='WAREHOUSE'?'仓发 WAREHOUSE':row.orderMode }}</template></el-table-column>
        <el-table-column prop="salesQty" label="销量" min-width="105"/>
        <el-table-column prop="salesRevenue" label="销售收入（元）" min-width="140"/>
        <el-table-column prop="platformFee" label="平台费用（元）" min-width="140"/>
        <el-table-column prop="purchaseCost" label="采购成本（元）" min-width="140"/>
        <el-table-column prop="packageCost" label="包材成本（元）" min-width="140"/>
        <el-table-column prop="refundLoss" label="退款售后损失（元）" min-width="165"/>
        <el-table-column prop="otherCost" label="其他分摊费用（元）" min-width="165"/>
        <el-table-column prop="grossProfit" label="SKU 毛利（元）" min-width="140"/>
        <el-table-column prop="grossMargin" label="SKU 毛利率" min-width="120"><template #default="{row}">{{ row.grossMargin==null?'—':`${(Number(row.grossMargin)*100).toFixed(2)}%` }}</template></el-table-column>
      </el-table>
      <div class="pagination-wrap"><el-pagination v-model:current-page="page" v-model:page-size="pageSize" :total="total" :page-sizes="[20,50,100]" layout="total, sizes, prev, pager, next" @change="load"/></div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadRawFile } from 'element-plus'
import { Download, Refresh } from '@element-plus/icons-vue'
import { api, getToken, type AuthUser } from '../api'

const props=defineProps<{user?:AuthUser}>()
const canImport=computed(()=>props.user?.permissions.includes('button.profits.import')===true)
const canRecalculate=computed(()=>props.user?.permissions.includes('button.profits.recalculate')===true)
const dimension=ref('daily'),dates=ref<string[]|null>([]),shopId=ref(''),skuId=ref(''),orderMode=ref(''),q=ref(''),forceReimport=ref(false)
const shops=ref<Array<{id:number;shopName:string}>>([]),skus=ref<Array<{id:number;internalSku:string;productName:string}>>([])
const items=ref<Record<string,any>[]>([]),page=ref(1),pageSize=ref(20),total=ref(0),loading=ref(false)
function params(){const p=new URLSearchParams({dimension:dimension.value,page:String(page.value),pageSize:String(pageSize.value)});const [from,to]=dates.value||[];if(from)p.set('dateFrom',from);if(to)p.set('dateTo',to);if(shopId.value)p.set('shopId',shopId.value);if(skuId.value)p.set('skuId',skuId.value);if(orderMode.value)p.set('orderMode',orderMode.value);if(q.value)p.set('q',q.value);return p}
async function load(){loading.value=true;try{const data=await api<{items:Record<string,any>[];total:number}>(`/api/profits/report?${params()}`);items.value=data.items;total.value=data.total}catch(e){ElMessage.error(e instanceof Error?e.message:'利润报表读取失败')}finally{loading.value=false}}
async function loadOptions(){try{const data=await api<{shops:typeof shops.value;skus:typeof skus.value}>('/api/profits/options');shops.value=data.shops;skus.value=data.skus}catch(e){ElMessage.error(e instanceof Error?e.message:'筛选项读取失败')}}
async function recalculate(){const [from,to]=dates.value||[];if(!from||!to)return;try{await ElMessageBox.confirm(`将重新计算 ${from} 至 ${to} 的所有 SKU、店铺和订单模式利润，并覆盖该日期范围现有预计算结果。`, '确认手动重算', {type:'warning',confirmButtonText:'确认重算'});const result=await api<{rows:number}>('/api/profits/recalculate',{method:'POST',body:JSON.stringify({dateFrom:from,dateTo:to})});ElMessage.success(`重算完成，共写入 ${result.rows} 个利润维度`);await load()}catch(e){if(e!=='cancel'&&e!=='close')ElMessage.error(e instanceof Error?e.message:'利润重算失败')}}
async function upload(file:File,type:'settlement'|'other_cost'){if(!file.name.toLowerCase().endsWith('.xlsx')){ElMessage.error('仅支持 .xlsx 文件');return false}const form=new FormData();form.append('file',file);form.append('forceReimport',String(forceReimport.value));try{const data=await api<{batchNo:string;rows:number;recalculatedRows:number}>(`/api/profits/imports/${type}`,{method:'POST',body:form});ElMessage.success(`导入成功：${data.batchNo}，${data.rows} 行；已自动重算 ${data.recalculatedRows} 个利润维度`);await load()}catch(e){ElMessage.error(e instanceof Error?e.message:'利润数据导入失败')}return false}
function uploadSettlement(file:UploadRawFile){return upload(file,'settlement')}
function uploadOtherCosts(file:UploadRawFile){return upload(file,'other_cost')}
async function downloadTemplate(type:'settlement'|'other_cost'){await download(`/api/profits/templates/${type}`,`${type}_profit_import_template.xlsx`)}
async function exportReport(){await download(`/api/profits/export?${params()}`,`SKU利润报表_${dimension.value}.xlsx`)}
async function download(url:string,filename:string){try{const headers:Record<string,string>={};const token=getToken();if(token)headers.Authorization=`Bearer ${token}`;const response=await fetch(url,{headers});if(!response.ok){const error=await response.json().catch(()=>null);throw new Error(error?.message||`下载失败（HTTP ${response.status}）`)}const blob=await response.blob();const a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download=filename;a.click();URL.revokeObjectURL(a.href)}catch(e){ElMessage.error(e instanceof Error?e.message:'文件下载失败')}}
onMounted(async()=>{await loadOptions();await load()})
</script>

<style scoped>
.profit-controls{margin-bottom:16px}.filter-row,.action-row{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-bottom:14px}.action-row{margin-top:4px}.action-row .el-upload{display:flex}.profit-page :deep(.el-alert){margin-top:10px}
</style>
