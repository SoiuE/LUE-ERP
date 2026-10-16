<template>
  <div>
    <div class="mobile-page-title"><h1>发货拣货</h1><p>JIT 客户订单与仓发入平台仓备货单分开处理</p></div>
    <van-tabs v-model:active="type" @change="loadDocs"><van-tab title="JIT 客户订单" name="JIT_ORDER"/><van-tab title="仓发入平台仓" name="PLATFORM_WAREHOUSE_STOCKING"/></van-tabs>
    <template v-if="!document">
      <van-search v-model="query" placeholder="搜索平台单号、SKU、预约号" @search="loadDocs"/>
      <van-pull-refresh v-model="refreshing" @refresh="loadDocs">
        <van-cell-group v-if="docs.length" inset class="mobile-section">
          <van-cell v-for="d in docs" :key="d.id" is-link :title="d.sourceDocumentId" :label="`${d.groupBatchId||'无组包批次'} · 预约 ${d.appointmentNo||'—'} · ${pickupTime(d.pickupStart)}`" :value="statusName(d.localStatus)" @click="openDoc(d.id)"/>
        </van-cell-group>
        <van-empty v-else description="没有待处理发货单"/>
      </van-pull-refresh>
    </template>
    <template v-else>
      <van-cell-group inset class="mobile-section">
        <van-cell title="来源平台单号" :value="document.sourceDocumentId"/><van-cell title="本地单号" :value="document.documentNo"/><van-cell title="发货仓" :value="document.warehouseName"/><van-cell title="预约号" :value="document.appointmentNo||'—'"/><van-cell title="预约取件" :value="pickupTime(document.pickupStart)"/><van-cell v-if="document.targetPlatformWarehouse" title="目标平台仓" :value="document.targetPlatformWarehouse"/>
      </van-cell-group>
      <van-cell-group v-if="document.packages?.length" inset title="组包 / 包裹信息" class="mobile-section"><van-cell v-for="p in document.packages" :key="p.id" :title="p.packageNo||p.labelNo||'平台包裹'" :label="`组包 ${p.groupBatchId||'—'} · 标签 ${p.labelNo||p.labelFilename||'—'}`" :value="p.appointmentNo||'—'"/></van-cell-group>
      <van-cell-group inset title="订单明细" class="mobile-section"><van-cell v-for="i in document.items" :key="i.id" :title="`${i.internalSku||'未匹配'} · ${i.productName||i.platformSku}`" :label="`平台 SKU ${i.platformSku} · 已发 ${i.shippedQty} / ${i.orderedQty}`" :value="`剩余 ${Number(i.orderedQty)-Number(i.shippedQty)}`"/></van-cell-group>
      <van-cell-group inset title="本地发货批次" class="mobile-section"><van-cell v-for="batch in document.dispatches||[]" :key="batch.id" :title="batch.dispatchNo" :value="batch.status" :is-link="batch.status==='PICKING'||batch.status==='PICKED'" @click="(batch.status==='PICKING'||batch.status==='PICKED')&&openDispatch(batch.id)"/></van-cell-group>
      <div class="mobile-form-actions"><van-button type="primary" block :disabled="!canCreateDispatch" @click="createDispatch">新建本地拣货批次</van-button><van-button plain block @click="document=undefined;dispatch=undefined">返回发货单列表</van-button></div>
    </template>
    <MobileLocationPicker v-model:show="locationPickerOpen" :model-value="locationPickerValue" :options="locationPickerOptions" title="选择作业库位" @update:model-value="locationChosen"/>
    <van-popup v-model:show="dispatchOpen" position="bottom" round closeable :style="{height:'88%'}" @closed="stopScanner">
      <div class="dispatch-popup">
        <h2>{{ dispatch?.dispatchNo||'拣货批次' }}</h2><p>按货品任务和来源库位拣货；扫码校验，也可手工搜索选择</p>
        <van-cell title="货品任务" :value="selectedTask?.goodsCode||'请选择'" is-link @click="selectTask"/>
        <van-cell title="来源库位" is-link @click="selectBin"><template #value><LocationDisplay :location="selectedBin"/></template></van-cell>
        <MobileBarcodeSearch v-model="barcode" @search="scanPick"/>
        <van-field v-model="locationScan" label="库位扫码" placeholder="可扫描校验或手工选择库位"/>
        <van-field v-model.number="pickQty" type="number" label="拣货数量" input-align="right"/>
        <van-button size="small" type="primary" :disabled="!selectedTask||!selectedBin" @click="scanPick(barcode)">记拣货</van-button>
        <van-cell-group inset title="货品需求与库位"><van-cell v-for="task in dispatch?.goodsTasks||[]" :key="task.taskId" :title="`${task.goodsCode} · ${task.goodsName}`" :label="`计划 ${task.plannedQty} / 已拣 ${task.pickedQty} / 实发 ${task.shippedQty} · 缺货 ${task.shortageQty}; ${task.locations?.map((b:any)=>`${locationLabel(b)} ${b.availableQty}`).join('、')||'无可拣位'}`" @click="taskId=task.taskId;locationId=task.locations?.[0]?.locationId"/></van-cell-group>
        <van-cell-group inset title="本批次明细">
          <div v-for="line in dispatch?.items||[]" :key="line.itemId" class="mobile-qty-row"><div><strong>{{ line.internalSku||line.platformSku }}</strong><small>{{ line.productName||line.platformSku }} · 已拣 {{ line.pickedQty }} / 计划 {{ line.plannedQty }}</small><van-field v-model.number="shippedQuantities[line.itemId]" type="number" label="本次实发"/></div><van-button size="small" plain type="warning" @click="shortage(line)">缺货</van-button></div>
        </van-cell-group>
        <van-field v-model="handoffReference" label="交接凭证/运单号" placeholder="实际交接后填写"/>
        <van-cell-group inset title="包材实耗"><div v-for="usage in packageUsages" :key="`${usage.dispatchItemId}-${usage.materialId}`"><van-cell :title="`${usage.materialCode} · 建议 ${usage.suggestedQty}`" :value="locationLabel(usage.locations?.find((b:any)=>b.locationId===usage.locationId))" is-link @click="selectPackageBin(usage)"/><van-field v-model.number="usage.quantity" type="number" label="实际数量"/><van-field v-model="usage.reason" label="调整原因" placeholder="与建议不同必填"/></div></van-cell-group>
        <div class="mobile-form-actions"><van-button type="primary" block :loading="working" @click="handoff">确认拣货完成并交接</van-button></div>
      </div>
    </van-popup>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { showConfirmDialog, showToast } from 'vant'
import { api } from '../../api'
import { formatChinaDateTime } from '../../utils/chinaTime'
import MobileBarcodeSearch from './MobileBarcodeSearch.vue'
import LocationDisplay from '../../components/LocationDisplay.vue'
import MobileLocationPicker from '../../components/MobileLocationPicker.vue'
import { locationLabel } from '../../utils/location'
const type=ref('JIT_ORDER'),query=ref(''),docs=ref<any[]>([]),refreshing=ref(false),document=ref<any>(),dispatch=ref<any>(),dispatchOpen=ref(false),barcode=ref(''),locationScan=ref(''),taskId=ref<number>(),locationId=ref<number>(),pickQty=ref<number>(1),handoffReference=ref(''),packageUsages=ref<any[]>([]),shippedQuantities=ref<Record<number,number>>({}),working=ref(false)
const selectedTask=computed(()=>dispatch.value?.goodsTasks?.find((x:any)=>x.taskId===taskId.value))
const selectedBin=computed(()=>selectedTask.value?.locations?.find((x:any)=>x.locationId===locationId.value))
const canCreateDispatch=computed(()=>document.value?.items?.some((i:any)=>i.skuId&&Number(i.orderedQty)>Number(i.shippedQty)))
function statusName(v:string){return ({SKU_UNMATCHED:'SKU 未匹配',READY_TO_PICK:'待拣货',PICKING:'拣货中',SHORTAGE:'缺货待采购',PARTIALLY_SHIPPED:'部分已发货',SHIPPED:'已发货',NEEDS_REVIEW:'待人工核对',CANCELLED:'已取消'} as Record<string,string>)[v]||v}
function pickupTime(value?:string){return value?formatChinaDateTime(value,true):'时间未提供'}
async function loadDocs(){refreshing.value=true;try{const p=new URLSearchParams({page:'1',pageSize:'100',type:type.value});if(query.value.trim())p.set('q',query.value.trim());const r=await api<any>(`/api/fulfillment/documents?${p}`);docs.value=r.items.filter((d:any)=>!['SHIPPED','CANCELLED'].includes(d.localStatus))}catch(e){showToast(e instanceof Error?e.message:'发货单读取失败')}finally{refreshing.value=false}}
async function openDoc(id:number){try{document.value=await api<any>(`/api/fulfillment/documents/${id}`)}catch(e){showToast(e instanceof Error?e.message:'履约单详情读取失败')}}
async function createDispatch(){if(!document.value)return;const items=document.value.items.filter((i:any)=>i.skuId&&Number(i.orderedQty)>Number(i.shippedQty)).map((i:any)=>({itemId:i.id,quantity:Number(i.orderedQty)-Number(i.shippedQty)}));if(!items.length)return;try{await showConfirmDialog({title:'新建拣货批次',message:`发货仓：${document.value.warehouseName}。该订单各批次使用同一仓库。`});const r=await api<any>(`/api/fulfillment/documents/${document.value.id}/dispatches`,{method:'POST',body:JSON.stringify({idempotencyKey:crypto.randomUUID(),items})});await openDispatch(r.id)}catch(e){if(e!=='cancel')showToast(e instanceof Error?e.message:'创建拣货任务失败')}}
async function openDispatch(id:number){dispatch.value=await api<any>(`/api/fulfillment/dispatches/${id}`);shippedQuantities.value={};for(const line of dispatch.value.items||[])shippedQuantities.value[line.itemId]=Number(line.pickedQty);const first=dispatch.value.goodsTasks?.[0];taskId.value=first?.taskId;locationId.value=first?.locations?.[0]?.locationId;const suggestions=await api<{items:any[]}>(`/api/fulfillment/dispatches/${id}/package-suggestions`);packageUsages.value=suggestions.items.map(x=>({...x,quantity:Number(x.suggestedQty),reason:''}));dispatchOpen.value=true;handoffReference.value=''}
async function selectTask(){const choices=(dispatch.value?.goodsTasks||[]).map((x:any)=>`${x.taskId}: ${x.goodsCode} ${x.goodsName}`).join('\n');const value=prompt(`输入货品任务 ID：\n${choices}`,String(taskId.value||''));if(value){const task=dispatch.value.goodsTasks.find((x:any)=>x.taskId===Number(value));if(task){taskId.value=task.taskId;locationId.value=task.locations?.[0]?.locationId}else showToast('货品任务无效')}}
const locationPickerOpen=ref(false),locationPickerValue=ref<number>(),locationPickerOptions=ref<any[]>([]),pickerUsage=ref<any>()
function selectBin(){pickerUsage.value=undefined;locationPickerOptions.value=selectedTask.value?.locations||[];locationPickerValue.value=locationId.value;locationPickerOpen.value=true}
function selectPackageBin(usage:any){pickerUsage.value=usage;locationPickerOptions.value=usage.locations||[];locationPickerValue.value=usage.locationId;locationPickerOpen.value=true}
function locationChosen(value:number){if(pickerUsage.value)pickerUsage.value.locationId=value;else locationId.value=value}
async function scanPick(value:string){barcode.value=value;if(!dispatch.value||!taskId.value||!locationId.value)return showToast('请选择货品任务和来源库位');working.value=true;try{const r=await api<any>(`/api/fulfillment/dispatches/${dispatch.value.id}/pick`,{method:'POST',body:JSON.stringify({taskId:taskId.value,locationId:locationId.value,barcode:value.trim()||undefined,locationScan:locationScan.value.trim()||undefined,quantity:pickQty.value})});showToast(r.shortageWarning?`已记拣，库位可用 ${r.availableQty}，交接时记录缺货异常`:`已拣 ${r.pickedQty} / ${r.plannedQty}`);barcode.value='';locationScan.value='';dispatch.value=await api<any>(`/api/fulfillment/dispatches/${dispatch.value.id}`)}catch(e){showToast(e instanceof Error?e.message:'货品拣货失败')}finally{working.value=false}}
async function shortage(line:any){if(!dispatch.value)return;try{const reason=prompt('请填写缺货原因');if(!reason?.trim())return;await api(`/api/fulfillment/dispatches/${dispatch.value.id}/shortages`,{method:'POST',body:JSON.stringify({itemId:line.itemId,reason})});showToast('缺货已登记，可由管理端关联采购单');dispatch.value=await api<any>(`/api/fulfillment/dispatches/${dispatch.value.id}`);if(document.value)await openDoc(document.value.id)}catch(e){showToast(e instanceof Error?e.message:'缺货登记失败')}}
async function handoff(){if(!dispatch.value?.items?.some((i:any)=>Number(i.pickedQty)>0))return showToast('请先拣货');try{await showConfirmDialog({title:'确认交接发货',message:'确认后将按货品来源库位和包材实耗生成流水。'});working.value=true;const shipped=Object.fromEntries(Object.entries(shippedQuantities.value).map(([id,qty])=>[`shippedQty_${id}`,qty]));const r=await api<any>(`/api/fulfillment/dispatches/${dispatch.value.id}/handoff`,{method:'POST',body:JSON.stringify({handoffReference:handoffReference.value,...shipped,packageUsages:packageUsages.value.map(x=>({dispatchItemId:x.dispatchItemId,materialId:x.materialId,locationId:x.locationId,quantity:x.quantity,reason:x.reason}))})});showToast(`已交接 ${r.shippedQty} 件${r.warnings?.length?'，有库存/包材预警':''}`);dispatchOpen.value=false;if(document.value)await openDoc(document.value.id);await loadDocs()}catch(e){if(e!=='cancel')showToast(e instanceof Error?e.message:'交接失败')}finally{working.value=false}}
function stopScanner(){barcode.value=''}
onMounted(loadDocs)
</script>

<style scoped>
.dispatch-popup{height:100%;overflow:auto;padding:20px 14px 32px}.dispatch-popup h2{font-size:19px;margin:0 0 5px}.dispatch-popup>p{font-size:13px;color:#8991a0;margin:0 0 12px}
</style>
