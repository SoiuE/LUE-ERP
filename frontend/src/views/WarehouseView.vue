<template>
  <div class="page-stack">
    <div class="page-heading"><div><div class="eyebrow">PACKAGING STOCK</div><h1>包材库存</h1><p>查看包材余额和每一次库存变化的业务流水</p></div><el-button type="primary" @click="operationVisible=true">库存业务操作</el-button></div>
    <el-card shadow="never">
      <div class="toolbar"><el-input v-model="q" clearable placeholder="包材编码 / 名称" style="max-width:380px" @keyup.enter="load"/><el-button type="primary" @click="load">查询</el-button><el-radio-group v-model="tab" @change="load"><el-radio-button label="stock">库存</el-radio-button><el-radio-button label="flow">库存流水</el-radio-button></el-radio-group></div>
      <el-table :data="items" v-loading="loading" stripe>
        <template v-if="tab==='stock'"><el-table-column prop="code" label="包材编码" min-width="160"/><el-table-column prop="name" label="包材名称" min-width="180"/><el-table-column prop="category" label="包材分类"><template #default="{row}">{{ categoryText(row.category) }}</template></el-table-column><el-table-column prop="unit" label="单位" width="90"/><el-table-column prop="availableQty" label="可用库存" width="125"/><el-table-column prop="safetyStock" label="安全库存" width="125"/></template>
        <template v-else><el-table-column prop="createdAt" label="发生时间" min-width="180"><template #default="{row}">{{ formatChinaDateTime(row.createdAt) }}</template></el-table-column><el-table-column prop="code" label="包材编码"/><el-table-column prop="name" label="名称" min-width="150"/><el-table-column prop="category" label="包材分类"><template #default="{row}">{{ categoryText(row.category) }}</template></el-table-column><el-table-column prop="flowType" label="流水类型"><template #default="{row}">{{ flowText(row.flowType) }}</template></el-table-column><el-table-column prop="quantityDelta" label="数量变化"/><el-table-column prop="availableBefore" label="变更前"/><el-table-column prop="availableAfter" label="变更后"/><el-table-column prop="referenceType" label="来源"/><el-table-column prop="reason" label="原因" min-width="160"/></template>
      </el-table>
      <el-pagination v-model:current-page="page" v-model:page-size="pageSize" :total="total" layout="total, prev, pager, next" @current-change="load"/>
    </el-card>
    <el-dialog v-model="operationVisible" title="包材库存操作" width="560px"><el-form label-position="top"><el-form-item label="操作类型"><el-select v-model="form.operation"><el-option label="入库" value="INBOUND"/><el-option label="出库" value="OUTBOUND"/><el-option label="盘点（填写实盘数）" value="COUNT"/><el-option label="调整（正数增加，负数减少）" value="ADJUST"/></el-select></el-form-item><el-form-item label="包材"><el-select v-model="form.materialId" filterable><el-option v-for="x in materials" :key="x.id" :label="`${x.materialCode} · ${x.materialName}`" :value="x.id"/></el-select></el-form-item><el-form-item :label="form.operation==='COUNT'?'实盘数量':form.operation==='ADJUST'?'调整数量（可正可负）':'数量'"><el-input-number v-model="form.quantity" :precision="4" :step="1"/></el-form-item><el-form-item label="原因" :required="['COUNT','ADJUST'].includes(form.operation)"><el-input v-model="form.reason" type="textarea" placeholder="盘点、调整必须填写原因；包材出库不足时会自动生成异常"/></el-form-item></el-form><template #footer><el-button @click="operationVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="submit">提交</el-button></template></el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { formatChinaDateTime } from '../utils/chinaTime'

const q=ref(''),tab=ref('stock'),items=ref<any[]>([]),materials=ref<any[]>([]),page=ref(1),pageSize=ref(50),total=ref(0),loading=ref(false),saving=ref(false),operationVisible=ref(false)
const form=reactive<any>({operation:'INBOUND',materialId:null,quantity:1,reason:''})
const categoryText=(v:string)=>({PRODUCT_PACKAGING:'产品包装',SHIPPING_MATERIAL:'发货打包材料'} as Record<string,string>)[v]||v||'—'
const flowText=(v:string)=>({MANUAL_INBOUND:'手工入库',MANUAL_OUTBOUND:'手工出库',STOCK_COUNT:'库存盘点',ADJUSTMENT:'库存调整',JIT_CONSUMPTION:'JIT 包材消耗',STOCKING_CONSUMPTION:'仓发产品包装消耗'} as Record<string,string>)[v]||v||'—'
async function load(){loading.value=true;try{const params=new URLSearchParams({page:String(page.value),pageSize:String(pageSize.value)});if(q.value)params.set('q',q.value);const result=await api<any>(`/api/warehouse/packages${tab.value==='flow'?'/flows':''}?${params}`);items.value=result.items;total.value=result.total}catch(e){ElMessage.error(e instanceof Error?e.message:'加载失败')}finally{loading.value=false}}
async function submit(){saving.value=true;try{const result=await api<any>('/api/warehouse/packages/operations',{method:'POST',body:JSON.stringify(form)});if(result.warning)ElMessage.warning(result.warning);else ElMessage.success('库存流水已生成');operationVisible.value=false;await load()}catch(e){ElMessage.error(e instanceof Error?e.message:'库存操作失败')}finally{saving.value=false}}
onMounted(async()=>{try{materials.value=(await api<any>('/api/master/materials')).items||[]}catch(e){ElMessage.error(e instanceof Error?e.message:'包材档案读取失败')}await load()})
</script>
