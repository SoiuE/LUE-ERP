<template>
  <div class="page-stack import-center">
    <div class="page-heading"><div><div class="eyebrow">DATA MANAGEMENT</div><h1>Excel 导入中心</h1><p>上传文件后先查看原始数据和校验结果，确认无误再导入正式数据。</p></div></div>

    <el-card shadow="never" class="import-upload-card">
      <template #header><div class="section-heading"><div><h2>新建导入</h2><p>仅支持 .xlsx，单文件最多 20,000 行</p></div><el-tag type="info" effect="plain">原文件及原始行会保留</el-tag></div></template>
      <el-form label-position="top" class="import-form">
        <el-form-item label="导入类型"><el-select v-model="importType" class="import-type-select"><el-option label="SKU" value="sku"/><el-option label="店铺 SKU 映射" value="shop_sku_mapping"/></el-select></el-form-item>
        <el-form-item label="Excel 文件"><el-upload :key="uploadKey" drag :auto-upload="false" :limit="1" accept=".xlsx" :on-change="fileChanged" :on-remove="fileRemoved"><el-icon class="el-icon--upload"><UploadFilled /></el-icon><div class="el-upload__text">拖放文件到此处，或 <em>点击选择</em></div><template #tip><div class="el-upload__tip">文件需使用系统模板首行字段名；不接受 .xls 文件。</div></template></el-upload></el-form-item>
        <el-form-item><el-checkbox v-model="forceReimport">强制重导入：匹配已有 SKU 或映射时覆盖字段</el-checkbox></el-form-item>
        <div class="import-actions"><el-button :icon="Download" @click="downloadTemplate">下载当前模板</el-button><el-button type="primary" :icon="Upload" :loading="uploading" :disabled="!selectedFile" @click="uploadFile">上传并预览</el-button></div>
      </el-form>
      <el-alert class="import-rules" type="info" :closable="false" show-icon :title="importRuleText"/>
    </el-card>

    <el-card shadow="never">
      <template #header><div class="section-heading"><div><h2>导入批次</h2><p>批次号格式：IM + 日期 + 8 位序号</p></div><el-button :icon="Refresh" @click="loadBatches">刷新</el-button></div></template>
      <el-table :data="batches" v-loading="loadingBatches" stripe @row-click="selectBatch" class="clickable-table">
        <el-table-column prop="batchNo" label="批次号" min-width="190"/>
        <el-table-column label="类型" width="150"><template #default="{row}">{{ typeLabel(row.importType) }}</template></el-table-column>
        <el-table-column prop="originalFilename" label="文件名" min-width="200" show-overflow-tooltip/>
        <el-table-column label="状态" width="125"><template #default="{row}"><el-tag :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="行数" width="200"><template #default="{row}">共 {{ row.totalRows }} · 有效 {{ row.validRows }} · 错误 {{ row.errorRows }}</template></el-table-column>
        <el-table-column prop="createdAt" label="创建时间" min-width="180"><template #default="{row}">{{ formatChinaDateTime(row.createdAt) }}</template></el-table-column>
        <el-table-column label="操作" width="240" fixed="right"><template #default="{row}"><el-button link type="primary" @click.stop="selectBatch(row)">预览</el-button><el-button link @click.stop="downloadOriginal(row)">原文件</el-button><el-button v-if="row.errorRows || row.errorMessage" link type="danger" @click.stop="downloadFailures(row)">失败明细</el-button></template></el-table-column>
      </el-table>
      <div class="pagination-wrap"><el-pagination v-model:current-page="batchPage" v-model:page-size="batchPageSize" :total="batchTotal" :page-sizes="[10,20,50]" layout="total, sizes, prev, pager, next" @change="loadBatches"/></div>
    </el-card>

    <el-card v-if="activeBatch" shadow="never" class="import-preview-card">
      <template #header><div class="section-heading"><div><h2>批次预览：{{ activeBatch.batchNo }}</h2><p>{{ activeBatch.originalFilename }} · {{ typeLabel(activeBatch.importType) }} · 共 {{ activeBatch.totalRows }} 行</p></div><div class="preview-actions"><el-button v-if="activeBatch.errorRows || activeBatch.errorMessage" type="danger" plain :icon="Download" @click="downloadFailures(activeBatch)">下载失败明细</el-button><el-button v-if="activeBatch.status==='PENDING'" type="primary" :loading="committing" @click="commitBatch">确认导入 {{ activeBatch.validRows }} 行</el-button><el-tag v-else :type="statusType(activeBatch.status)">{{ statusLabel(activeBatch.status) }}</el-tag></div></div></template>
      <el-alert v-if="activeBatch.errorRows" type="warning" :closable="false" show-icon :title="`${activeBatch.errorRows} 行存在校验错误；本批次不会写入正式数据。下载失败明细后修正文件并重新上传。`"/>
      <el-alert v-if="activeBatch.errorMessage" class="batch-error-alert" type="error" :closable="false" show-icon :title="activeBatch.errorMessage"/>
      <el-table :data="previewRows" v-loading="loadingPreview" stripe max-height="520" class="preview-table">
        <el-table-column prop="rowNumber" label="Excel 行" width="90" fixed/>
        <el-table-column prop="rowStatus" label="校验" width="105"><template #default="{row}"><el-tag :type="row.rowStatus==='ERROR'?'danger':row.rowStatus==='IMPORTED'?'success':'success'">{{ row.rowStatus==='ERROR'?'失败':row.rowStatus==='IMPORTED'?'已导入':'通过' }}</el-tag></template></el-table-column>
        <el-table-column v-for="column in previewColumns" :key="column" :prop="`raw.${column}`" :label="previewLabel(column)" min-width="150" show-overflow-tooltip>
          <template #default="{row}">{{ previewValue(column,row.raw?.[column]) }}</template>
        </el-table-column>
        <el-table-column label="失败原因" min-width="240" fixed="right"><template #default="{row}">{{ (row.errors || []).join('；') || '—' }}</template></el-table-column>
      </el-table>
      <div class="pagination-wrap"><el-pagination v-model:current-page="previewPage" v-model:page-size="previewPageSize" :total="Number(activeBatch.previewTotal || activeBatch.totalRows || 0)" :page-sizes="[20,50,100]" layout="total, sizes, prev, pager, next" @change="loadPreview"/></div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, Refresh, Upload, UploadFilled } from '@element-plus/icons-vue'
import { api, getToken } from '../api'
import { formatChinaDateTime } from '../utils/chinaTime'

type Batch={id:number;batchNo:string;importType:'sku'|'shop_sku_mapping'|'settlement'|'other_cost';originalFilename:string;status:string;forceReimport:boolean;totalRows:number;validRows:number;errorRows:number;importedRows:number;errorMessage?:string;createdAt:string;rows?:unknown[];previewTotal:number}
type PreviewRow={id:number;rowNumber:number;rawData:unknown;validationErrors:unknown;rowStatus:string;raw:Record<string,unknown>;errors:string[]}
const importType=ref<'sku'|'shop_sku_mapping'>('sku');const forceReimport=ref(false);const selectedFile=ref<File>();const uploadKey=ref(0);const uploading=ref(false);const committing=ref(false);const loadingBatches=ref(false);const loadingPreview=ref(false)
const batches=ref<Batch[]>([]);const batchPage=ref(1);const batchPageSize=ref(20);const batchTotal=ref(0);const activeBatch=ref<Batch>();const previewRows=ref<PreviewRow[]>([]);const previewPage=ref(1);const previewPageSize=ref(50)
const previewColumns=computed(()=>{const type=activeBatch.value?.importType;if(type==='sku')return ['internal_sku','product_name','spu','name_zh','name_en','specification','color','size','barcode','category','supplier_code','purchase_price','supply_price','weight','length','width','height','default_product_packaging_plan','default_shipping_material_plan','image_url','status','note'];if(type==='settlement')return ['shop_identifier','platform_order_no','platform_sku','order_mode','settlement_date','platform_fee','settlement_amount'];if(type==='other_cost')return ['shop_identifier','internal_sku','cost_date','amount','note'];return ['shop_identifier','platform_sku','platform_product_id','platform_product_name','internal_sku','default_order_mode','status']})
const previewLabels:Record<string,string>={internal_sku:'内部 SKU',product_name:'商品名称',spu:'SPU',name_zh:'中文名称',name_en:'英文名称',specification:'规格',color:'颜色',size:'尺码',barcode:'条码',category:'品类',supplier_code:'供应商编码',purchase_price:'采购价（元）',supply_price:'供货价（元）',weight:'重量',length:'长度',width:'宽度',height:'高度',default_product_packaging_plan:'默认产品包装方案（JSON）',default_shipping_material_plan:'默认发货打包材料方案（JSON）',image_url:'商品图片链接',status:'状态',note:'备注',shop_identifier:'店铺标识',platform_sku:'平台 SKU',platform_order_no:'平台订单号',order_mode:'订单模式',settlement_date:'结算日期',platform_fee:'平台费用（元）',settlement_amount:'结算金额（元）',cost_date:'费用日期',amount:'费用金额（元）',platform_product_id:'平台商品 ID',platform_product_name:'平台商品名称',default_order_mode:'默认订单模式'}
const importRuleText=computed(()=>importType.value==='sku'?'必填：内部 SKU、商品名称。内部 SKU 全局唯一；金额和尺寸为非负 decimal(18,4)，最多 4 位小数；状态填写 ACTIVE 或 INACTIVE。完整字段规则见下载模板的“填写说明”工作表。':'必填：店铺标识、平台 SKU、内部 SKU，且店铺和 SKU 须已启用。订单模式填写 JIT 或 WAREHOUSE（留空默认为 JIT）；状态填写 ACTIVE 或 INACTIVE。完整字段规则见下载模板的“填写说明”工作表。')
function parseJson(value:unknown):unknown {if(typeof value!=='string')return value;try{return JSON.parse(value)}catch{return value}}
function fileChanged(file:{raw?:File}){selectedFile.value=file.raw}
function fileRemoved(){selectedFile.value=undefined}
function typeLabel(type:string){return ({sku:'SKU',shop_sku_mapping:'店铺 SKU 映射',settlement:'结算数据',other_cost:'其他分摊费用'} as Record<string,string>)[type]||type}
function previewLabel(field:string){return previewLabels[field]||field}
function previewValue(field:string,value:unknown){if(value==null||value==='')return'—';const text=String(value);if(['order_mode','default_order_mode'].includes(field))return ({JIT:'JIT（即时履约）',WAREHOUSE:'仓发（WAREHOUSE）'} as Record<string,string>)[text]||text;if(field==='status')return ({ACTIVE:'启用',INACTIVE:'停用'} as Record<string,string>)[text]||text;if(field==='category')return ({PRODUCT_PACKAGING:'产品包装',SHIPPING_MATERIAL:'发货打包材料'} as Record<string,string>)[text]||text;return value}
function statusLabel(status:string){return ({PENDING:'待确认',VALIDATING:'校验中',FAILED:'失败',SUCCESS:'成功',PARTIAL_SUCCESS:'部分成功'} as Record<string,string>)[status]||status}
function statusType(status:string){return status==='SUCCESS'?'success':status==='FAILED'?'danger':status==='PENDING'?'warning':'info'}
async function loadBatches(){loadingBatches.value=true;try{const data=await api<{items:Batch[];total:number}>(`/api/import-batches?page=${batchPage.value}&pageSize=${batchPageSize.value}`);batches.value=data.items;batchTotal.value=data.total}catch(e){ElMessage.error(e instanceof Error?e.message:'批次列表读取失败')}finally{loadingBatches.value=false}}
async function loadPreview(){if(!activeBatch.value)return;loadingPreview.value=true;try{const data=await api<Batch>(`/api/import-batches/${activeBatch.value.id}?page=${previewPage.value}&pageSize=${previewPageSize.value}`);activeBatch.value=data;previewRows.value=(data.rows as Array<Record<string,unknown>>).map(row=>({id:Number(row.id),rowNumber:Number(row.rowNumber),raw:parseJson(row.rawData) as Record<string,unknown>,errors:(parseJson(row.validationErrors) as string[]|null)||[],rowStatus:String(row.rowStatus),rawData:row.rawData,validationErrors:row.validationErrors}))}catch(e){ElMessage.error(e instanceof Error?e.message:'预览读取失败')}finally{loadingPreview.value=false}}
async function selectBatch(row:Batch){activeBatch.value=row;previewPage.value=1;await loadPreview()}
async function uploadFile(){if(!selectedFile.value)return;if(!selectedFile.value.name.toLowerCase().endsWith('.xlsx')){ElMessage.error('仅支持 .xlsx 文件');return}const form=new FormData();form.append('file',selectedFile.value);form.append('importType',importType.value);form.append('forceReimport',String(forceReimport.value));uploading.value=true;try{const data=await api<Batch>('/api/import-batches',{method:'POST',body:form});activeBatch.value=data;previewPage.value=1;await loadPreview();selectedFile.value=undefined;uploadKey.value++;ElMessage.success(data.errorRows?'文件已上传，发现校验错误':'文件已校验通过，可预览后确认导入');await loadBatches()}catch(e){ElMessage.error(e instanceof Error?e.message:'上传失败')}finally{uploading.value=false}}
async function commitBatch(){if(!activeBatch.value)return;committing.value=true;try{await api(`/api/import-batches/${activeBatch.value.id}/commit`,{method:'POST'});ElMessage.success('导入完成');await Promise.all([loadBatches(),loadPreview()])}catch(e){ElMessage.error(e instanceof Error?e.message:'导入失败');await Promise.all([loadBatches(),loadPreview()])}finally{committing.value=false}}
async function downloadTemplate(){await download(`/api/import-batches/templates/${importType.value}`,importType.value==='sku'?'sku_import_template.xlsx':'shop_sku_mapping_template.xlsx')}
async function downloadOriginal(row:Batch){await download(`/api/import-batches/${row.id}/original`,row.originalFilename)}
async function downloadFailures(row:Batch){await download(`/api/import-batches/${row.id}/failures`,`import-failures-${row.batchNo}.csv`)}
async function download(url:string,filename:string){try{const headers:Record<string,string>={};const token=getToken();if(token)headers.Authorization=`Bearer ${token}`;const response=await fetch(url,{headers});if(!response.ok){const body=await response.json().catch(()=>null);throw new Error(body?.message||'文件下载失败')}const blob=await response.blob();const a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download=filename;a.click();URL.revokeObjectURL(a.href)}catch(e){ElMessage.error(e instanceof Error?e.message:'文件下载失败')}}
onMounted(loadBatches)
</script>
