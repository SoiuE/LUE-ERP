<template>
  <div class="page-stack profit-page">
    <div class="page-heading"><div><div class="eyebrow">PROFIT ANALYTICS</div><h1>SKU 利润报表</h1><p>按日预计算落表，支持 SKU、店铺、订单模式和月份维度查看。</p></div></div>

    <el-card shadow="never" class="profit-controls">
      <div class="filter-row">
        <el-select v-model="dimension" style="width:160px" @change="load"><el-option label="SKU 日利润" value="daily"/><el-option label="SKU 月利润" value="monthly"/><el-option label="店铺利润" value="shop"/><el-option label="订单模式利润" value="mode"/></el-select>
        <el-date-picker v-model="dates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期"/>
        <el-select v-model="shopId" clearable filterable placeholder="全部店铺" style="width:180px"><el-option v-for="shop in shops" :key="shop.id" :label="shop.shopName" :value="String(shop.id)"/></el-select>
        <el-select v-model="skuId" clearable filterable placeholder="全部 SKU" style="width:200px"><el-option v-for="sku in skus" :key="sku.id" :label="`${sku.internalSku} · ${sku.productName}`" :value="String(sku.id)"/></el-select>
        <el-select v-model="orderMode" clearable placeholder="全部模式" style="width:180px"><el-option label="JIT（即时履约）" value="JIT"/><el-option label="仓发（WAREHOUSE）" value="WAREHOUSE"/></el-select>
        <el-input v-model="q" clearable placeholder="SKU 或商品名称" style="width:190px" @keyup.enter="load"/>
        <el-button type="primary" @click="load">查询</el-button>
      </div>
      <div class="action-row">
        <el-button v-if="canRecalculate" :icon="Refresh" :disabled="!dates?.[0]||!dates?.[1]" @click="recalculate">手动重算日期范围</el-button>
        <el-button :icon="Download" @click="exportReport">导出 Excel</el-button>
        <el-checkbox v-if="canImport" v-model="forceReimport">其他费用强制重导入</el-checkbox>
        <el-upload v-if="canImport" :show-file-list="false" accept=".xlsx" :before-upload="uploadSettlement"><el-button>导入结算 Excel</el-button></el-upload>
        <el-upload v-if="canImport" :show-file-list="false" accept=".xlsx" :before-upload="uploadOtherCosts"><el-button>导入其他费用 Excel</el-button></el-upload>
        <el-button v-if="canImport" text @click="downloadTemplate('settlement')">结算模板</el-button>
        <el-button v-if="canImport" text @click="downloadTemplate('other_cost')">其他费用模板</el-button>
      </div>
      <el-alert type="info" :closable="false" show-icon title="利润按订单日归属；有履约快照时使用实发货品与包材成本。结算日仅用于现金流查询，结算金额不会新增销售收入。" />
    </el-card>

    <el-card shadow="never">
      <el-table :data="items" v-loading="loading" stripe>
        <el-table-column v-if="dimension==='daily'" prop="reportDate" label="日期" width="120"/>
        <el-table-column v-if="dimension==='monthly'" prop="reportMonth" label="月份" width="120"/>
        <el-table-column v-if="['daily','monthly','shop'].includes(dimension)" prop="shopName" label="店铺" min-width="150"/>
        <el-table-column v-if="['daily','monthly'].includes(dimension)" prop="internalSku" label="内部 SKU" min-width="150"/>
        <el-table-column v-if="['daily','monthly'].includes(dimension)" prop="productName" label="商品名称" min-width="180" show-overflow-tooltip/>
        <el-table-column v-if="['daily','monthly','mode'].includes(dimension)" prop="orderMode" label="订单模式" width="180"><template #default="{row}">{{ orderModeName(row.orderMode) }}</template></el-table-column>
        <el-table-column prop="salesQty" label="销量" min-width="105"/>
        <el-table-column prop="salesRevenue" label="销售收入（元）" min-width="140"/>
        <el-table-column prop="platformFee" label="平台费用（元）" min-width="140"/>
        <el-table-column prop="purchaseCost" label="采购成本（元）" min-width="140"/>
        <el-table-column prop="packageCost" label="包材成本（元）" min-width="140"/>
        <el-table-column prop="refundLoss" label="退款售后损失（元）" min-width="165"/>
        <el-table-column prop="otherCost" label="其他分摊费用（元）" min-width="165"/>
        <el-table-column prop="grossProfit" label="SKU 毛利（元）" min-width="140"/>
        <el-table-column prop="grossMargin" label="SKU 毛利率" min-width="120"><template #default="{row}">{{ row.grossMargin==null?'—':`${(Number(row.grossMargin)*100).toFixed(2)}%` }}</template></el-table-column>
        <el-table-column label="成本口径" min-width="180"><template #default="{row}">实际 {{ row.actualCostOrders }} / 混合 {{ row.mixedCostOrders }} / 旧口径 {{ row.legacyCostOrders }}<small v-if="Number(row.costUnmatchedOrders)" class="cost-warning">待匹配 {{ row.costUnmatchedOrders }}</small></template></el-table-column>
        <el-table-column label="结算进度" min-width="160"><template #default="{row}">暂估 {{ row.estimatedOrders }} / 部分 {{ row.partialSettlementOrders }} / 已结 {{ row.settledOrders }}</template></el-table-column>
        <el-table-column prop="settlementDifference" label="结算利润差额" width="140"/>
        <el-table-column label="追溯" width="90"><template #default="{row}"><el-button link type="primary" @click="openProfitOrders(row)">订单</el-button></template></el-table-column>
      </el-table>
      <div class="pagination-wrap"><el-pagination v-model:current-page="page" v-model:page-size="pageSize" :total="total" :page-sizes="[20,50,100]" layout="total, sizes, prev, pager, next" @change="load"/></div>
    </el-card>

    <el-card shadow="never"><template #header><div class="section-heading"><div><h2>结算明细</h2><p>按平台结算日查询；待匹配与金额类型不明的明细保留异常</p></div><el-button @click="loadSettlements">刷新</el-button></div></template>
      <div class="filter-row"><el-date-picker v-model="settlementDates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="结算开始日" end-placeholder="结算结束日"/><el-select v-model="settlementStatus" clearable placeholder="全部匹配状态" style="width:180px"><el-option label="待匹配" value="PENDING"/><el-option label="待映射" value="UNMAPPED"/><el-option label="已匹配" value="MATCHED"/><el-option label="人工匹配" value="MANUAL"/></el-select><el-button @click="loadSettlements">按结算日查询</el-button></div>
      <el-table :data="settlementLines" stripe><el-table-column prop="settlementDate" label="结算日" width="120"/><el-table-column prop="batchNo" label="导入批次" width="155"/><el-table-column prop="platformBillNo" label="平台账单号" min-width="140"/><el-table-column prop="platformLineNo" label="明细号" min-width="130"/><el-table-column prop="platformOrderNo" label="平台订单号" min-width="150"/><el-table-column prop="platformSku" label="平台 SKU" min-width="130"/><el-table-column prop="platformFee" label="费用" width="95"/><el-table-column prop="cashAmount" label="现金金额" width="110"/><el-table-column label="匹配" min-width="180"><template #default="{row}">{{ row.matchStatus }}<small v-if="row.matchReason" class="cost-warning">{{ row.matchReason }}</small></template></el-table-column><el-table-column label="操作" width="130"><template #default="{row}"><el-button v-if="canImport&&row.matchStatus==='UNMAPPED'&&row.adjustmentAmount!=null&&row.platformBillNo&&row.platformLineNo" link type="primary" @click="mapAdjustment(row)">映射</el-button><el-button v-if="canImport&&['PENDING','UNMAPPED'].includes(row.matchStatus)" link type="primary" @click="repairSettlement(row)">匹配</el-button><el-button v-else-if="row.orderId" link @click="openTrace(row.orderId)">订单</el-button></template></el-table-column></el-table>
      <div class="pagination-wrap"><el-pagination v-model:current-page="settlementPage" :page-size="50" :total="settlementTotal" layout="total,prev,pager,next" @change="loadSettlements"/></div>
    </el-card>
    <el-dialog v-model="ordersVisible" title="订单日利润明细" width="min(900px,96vw)"><el-table :data="profitOrders"><el-table-column prop="orderDate" label="订单日"/><el-table-column prop="platformOrderNo" label="平台订单号"/><el-table-column prop="platformLineNo" label="明细号"/><el-table-column prop="platformSku" label="平台 SKU"/><el-table-column prop="costBasis" label="成本口径"/><el-table-column prop="settlementStatus" label="结算状态"/><el-table-column prop="purchaseCost" label="货品成本"/><el-table-column prop="packageCost" label="包材成本"/><el-table-column label="追溯"><template #default="{row}"><el-button link type="primary" @click="openTrace(row.orderId)">查看</el-button></template></el-table-column></el-table><el-pagination v-model:current-page="ordersPage" :page-size="100" :total="ordersTotal" layout="total,prev,pager,next" @change="loadProfitOrders"/></el-dialog>
    <el-dialog v-model="traceVisible" title="订单成本与结算追溯" width="min(1000px,96vw)"><template v-if="trace"><p>订单 {{ trace.order.platformOrderNo }} · {{ trace.order.costBasis }} · {{ trace.order.settlementStatus }}</p><h3>履约与组成版本</h3><el-table :data="trace.fulfillments" size="small"><el-table-column prop="dispatchNo" label="批次"/><el-table-column prop="compositionVersionId" label="组成版本"/><el-table-column prop="shippedQty" label="实发"/><el-table-column prop="shippedAt" label="发货时间"/></el-table><h3>货品成本流水</h3><el-table :data="trace.goodsOutbounds" size="small"><el-table-column prop="stockDocumentId" label="出库单"/><el-table-column prop="goodsCode" label="货品"/><el-table-column label="具体位置" min-width="300"><template #default="{row}"><LocationDisplay :location="row"/></template></el-table-column><el-table-column prop="quantity" label="数量"/><el-table-column prop="unitCostSnapshot" label="单位成本"/><el-table-column prop="costAmountSnapshot" label="金额"/></el-table><h3>采购成本来源（移动平均成本层）</h3><el-table :data="trace.purchaseCostSources" size="small"><el-table-column prop="receiptNo" label="收货单"/><el-table-column prop="goodsId" label="货品 ID"/><el-table-column prop="actualUnitPrice" label="实价"/><el-table-column prop="receiptQty" label="数量"/></el-table><h3>包材实耗</h3><el-table :data="trace.packageFlows" size="small"><el-table-column prop="materialCode" label="包材"/><el-table-column label="具体位置" min-width="300"><template #default="{row}"><LocationDisplay :location="row"/></template></el-table-column><el-table-column prop="quantity" label="实耗"/><el-table-column prop="costAmountSnapshot" label="成本快照"/></el-table><h3>结算明细</h3><el-table :data="trace.settlements" size="small"><el-table-column prop="platformBillNo" label="账单"/><el-table-column prop="settlementDate" label="结算日"/><el-table-column prop="platformFee" label="费用"/><el-table-column prop="cashAmount" label="现金"/><el-table-column prop="adjustmentType" label="调整类型"/><el-table-column prop="adjustmentAmount" label="调整金额"/></el-table></template></el-dialog>
  </div>
</template>

<script setup lang="ts">
import LocationDisplay from '../components/LocationDisplay.vue'
import { locationLabel } from '../utils/location'
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadRawFile } from 'element-plus'
import { Download, Refresh } from '@element-plus/icons-vue'
import { api, getToken, type AuthUser } from '../api'

const props=defineProps<{user?:AuthUser}>()
const canImport=computed(()=>props.user?.permissions.includes('button.profits.import')===true)
const canRecalculate=computed(()=>props.user?.permissions.includes('button.profits.recalculate')===true)
const dimension=ref('daily'),dates=ref<string[]|null>([]),shopId=ref(''),skuId=ref(''),orderMode=ref(''),q=ref(''),forceReimport=ref(false)
function orderModeName(value:string){return ({JIT:'JIT（即时履约）',WAREHOUSE:'仓发（WAREHOUSE）'} as Record<string,string>)[value]||'其他模式'}
const shops=ref<Array<{id:number;shopName:string}>>([]),skus=ref<Array<{id:number;internalSku:string;productName:string}>>([])
const items=ref<Record<string,any>[]>([]),page=ref(1),pageSize=ref(20),total=ref(0),loading=ref(false)
const settlementDates=ref<string[]|null>([]),settlementStatus=ref(''),settlementPage=ref(1),settlementTotal=ref(0),settlementLines=ref<any[]>([]),ordersVisible=ref(false),ordersPage=ref(1),ordersTotal=ref(0),ordersQuery=ref(''),profitOrders=ref<any[]>([]),traceVisible=ref(false),trace=ref<any>()
function params(){const p=new URLSearchParams({dimension:dimension.value,page:String(page.value),pageSize:String(pageSize.value)});const [from,to]=dates.value||[];if(from)p.set('dateFrom',from);if(to)p.set('dateTo',to);if(shopId.value)p.set('shopId',shopId.value);if(skuId.value)p.set('skuId',skuId.value);if(orderMode.value)p.set('orderMode',orderMode.value);if(q.value)p.set('q',q.value);return p}
async function load(){loading.value=true;try{const data=await api<{items:Record<string,any>[];total:number}>(`/api/profits/report?${params()}`);items.value=data.items;total.value=data.total}catch(e){ElMessage.error(e instanceof Error?e.message:'利润报表读取失败')}finally{loading.value=false}}
async function loadOptions(){try{const data=await api<{shops:typeof shops.value;skus:typeof skus.value}>('/api/profits/options');shops.value=data.shops;skus.value=data.skus}catch(e){ElMessage.error(e instanceof Error?e.message:'筛选项读取失败')}}
async function recalculate(){const [from,to]=dates.value||[];if(!from||!to)return;try{await ElMessageBox.confirm(`将重新计算 ${from} 至 ${to}${shopId.value?'、当前店铺':''}${skuId.value?'、当前 SKU':''} 的利润。`, '确认手动重算', {type:'warning',confirmButtonText:'确认重算'});const result=await api<{rows:number}>('/api/profits/recalculate',{method:'POST',body:JSON.stringify({dateFrom:from,dateTo:to,shopId:shopId.value||null,skuId:skuId.value||null})});ElMessage.success(`重算完成，共写入 ${result.rows} 个利润维度`);await load()}catch(e){if(e!=='cancel'&&e!=='close')ElMessage.error(e instanceof Error?e.message:'利润重算失败')}}
async function upload(file:File,type:'settlement'|'other_cost'){if(!file.name.toLowerCase().endsWith('.xlsx')){ElMessage.error('仅支持 .xlsx 文件');return false}const form=new FormData();form.append('file',file);form.append('forceReimport',String(type==='other_cost'&&forceReimport.value));try{if(type==='settlement'){const preview=await api<{rows:number;issues:number;sample:any[]}>('/api/profits/settlements/preview',{method:'POST',body:form});await ElMessageBox.confirm(`预览 ${preview.rows} 行，字段待核对 ${preview.issues} 行。原始明细会保存；无法唯一匹配的结算会进入异常。`,'确认导入结算',{type:'info'})}const data=await api<{batchNo:string;rows:number;recalculatedRows?:number;matched?:number;pending?:number;duplicate?:boolean}>(`/api/profits/imports/${type}`,{method:'POST',body:form});ElMessage.success(data.duplicate?`文件已导入：${data.batchNo}`:`导入 ${data.batchNo}，共 ${data.rows} 行${type==='settlement'?`，匹配 ${data.matched||0}，待处理 ${data.pending||0}`:`，重算 ${data.recalculatedRows||0} 个维度`}`);await load();await loadSettlements()}catch(e){if(e!=='cancel'&&e!=='close')ElMessage.error(e instanceof Error?e.message:'利润数据导入失败')}return false}
function uploadSettlement(file:UploadRawFile){return upload(file,'settlement')}
function uploadOtherCosts(file:UploadRawFile){return upload(file,'other_cost')}
async function downloadTemplate(type:'settlement'|'other_cost'){await download(`/api/profits/templates/${type}`,`${type}_profit_import_template.xlsx`)}
async function exportReport(){await download(`/api/profits/export?${params()}`,`SKU利润报表_${dimension.value}.xlsx`)}
async function download(url:string,filename:string){try{const headers:Record<string,string>={};const token=getToken();if(token)headers.Authorization=`Bearer ${token}`;const response=await fetch(url,{headers});if(!response.ok){const error=await response.json().catch(()=>null);throw new Error(error?.message||`下载失败（HTTP ${response.status}）`)}const blob=await response.blob();const a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download=filename;a.click();URL.revokeObjectURL(a.href)}catch(e){ElMessage.error(e instanceof Error?e.message:'文件下载失败')}}
async function loadSettlements(){try{const p=new URLSearchParams({page:String(settlementPage.value),pageSize:'50'});if(settlementDates.value?.[0])p.set('settlementFrom',settlementDates.value[0]);if(settlementDates.value?.[1])p.set('settlementTo',settlementDates.value[1]);if(settlementStatus.value)p.set('status',settlementStatus.value);const data=await api<{items:any[];total:number}>(`/api/profits/settlements?${p}`);settlementLines.value=data.items;settlementTotal.value=data.total}catch(e){ElMessage.error(e instanceof Error?e.message:'结算明细读取失败')}}
async function repairSettlement(line:any){try{const data=await api<{items:any[]}>(`/api/profits/settlements/${line.id}/candidates`);const label=data.items.map(x=>`${x.orderId}: ${x.platformOrderNo} / ${x.platformLineNo||'无明细号'} / ${x.orderDate}`).join('\n');const {value}=await ElMessageBox.prompt(`请输入确认的订单 ID：\n${label||'没有安全候选，请先核对订单来源'}`,'人工关联结算',{inputPattern:/^[1-9]\d*$/,inputErrorMessage:'请输入订单 ID'});await api(`/api/profits/settlements/${line.id}/match`,{method:'POST',body:JSON.stringify({orderId:Number(value)})});ElMessage.success('已匹配并重算原订单日期');await loadSettlements();await load()}catch(e){if(e!=='cancel'&&e!=='close')ElMessage.error(e instanceof Error?e.message:'结算匹配失败')}}
async function mapAdjustment(line:any){try{const {value}=await ElMessageBox.prompt('确认金额性质：FEE_DELTA（费用增量）、REFUND_DELTA（退款增量）或 OTHER_COST_DELTA（其他成本增量）。正负号按原明细金额。','结算调整类型映射',{inputPattern:/^(FEE_DELTA|REFUND_DELTA|OTHER_COST_DELTA)$/,inputErrorMessage:'请输入三个明确类型之一'});await api(`/api/profits/settlements/${line.id}/adjustment-type`,{method:'POST',body:JSON.stringify({adjustmentType:value})});ElMessage.success('调整类型已保存；可匹配时已重算订单日利润');await loadSettlements();await load()}catch(e){if(e!=='cancel'&&e!=='close')ElMessage.error(e instanceof Error?e.message:'调整类型映射失败')}}
async function loadProfitOrders(){try{const data=await api<{items:any[];total:number}>(`/api/profits/order-facts?${ordersQuery.value}&page=${ordersPage.value}`);profitOrders.value=data.items;ordersTotal.value=data.total;ordersVisible.value=true}catch(e){ElMessage.error(e instanceof Error?e.message:'订单利润明细读取失败')}}
async function openProfitOrders(row:any){const p=new URLSearchParams();let from='',to='';if(row.reportDate){from=to=String(row.reportDate).slice(0,10)}else if(row.reportMonth){from=`${row.reportMonth}-01`;const end=new Date(`${from}T00:00:00`);end.setMonth(end.getMonth()+1);end.setDate(0);to=`${row.reportMonth}-${String(end.getDate()).padStart(2,'0')}`}else{[from,to]=dates.value||[];if(!from||!to){ElMessage.warning('请先选择订单日期范围再下钻');return}}p.set('dateFrom',from);p.set('dateTo',to);if(row.shopId)p.set('shopId',String(row.shopId));else if(shopId.value)p.set('shopId',shopId.value);if(row.skuId)p.set('skuId',String(row.skuId));else if(skuId.value)p.set('skuId',skuId.value);if(row.orderMode)p.set('orderMode',row.orderMode);else if(orderMode.value)p.set('orderMode',orderMode.value);ordersQuery.value=p.toString();ordersPage.value=1;await loadProfitOrders()}
async function openTrace(orderId:number){try{trace.value=await api<any>(`/api/profits/orders/${orderId}/trace`);traceVisible.value=true}catch(e){ElMessage.error(e instanceof Error?e.message:'成本追溯读取失败')}}
onMounted(async()=>{await loadOptions();await load();await loadSettlements()})
</script>

<style scoped>
.profit-controls{margin-bottom:16px}.filter-row,.action-row{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-bottom:14px}.action-row{margin-top:4px}.action-row .el-upload{display:flex}.profit-page :deep(.el-alert){margin-top:10px}
.cost-warning{display:block;color:#b45309}
</style>
