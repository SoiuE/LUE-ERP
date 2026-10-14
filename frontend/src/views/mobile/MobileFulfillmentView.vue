<template>
  <div>
    <div class="mobile-page-title"><h1>发货拣货</h1><p>JIT 客户订单与仓发入平台仓备货单分开处理</p></div>
    <van-tabs v-model:active="type" @change="loadDocs"><van-tab title="JIT 客户订单" name="JIT_ORDER"/><van-tab title="仓发入平台仓" name="PLATFORM_WAREHOUSE_STOCKING"/></van-tabs>
    <template v-if="!document">
      <van-search v-model="query" placeholder="搜索平台单号、SKU、预约号" @search="loadDocs"/>
      <van-pull-refresh v-model="refreshing" @refresh="loadDocs">
        <van-cell-group v-if="docs.length" inset class="mobile-section">
          <van-cell v-for="d in docs" :key="d.id" is-link :title="d.sourceDocumentId" :label="`${d.groupBatchId||'无组包批次'} · 预约 ${d.appointmentNo||'—'} · ${d.pickupStart||'时间未提供'}`" :value="statusName(d.localStatus)" @click="openDoc(d.id)"/>
        </van-cell-group>
        <van-empty v-else description="没有待处理发货单"/>
      </van-pull-refresh>
    </template>
    <template v-else>
      <van-cell-group inset class="mobile-section">
        <van-cell title="来源平台单号" :value="document.sourceDocumentId"/><van-cell title="本地单号" :value="document.documentNo"/><van-cell title="发货仓" :value="document.warehouseName"/><van-cell title="预约号" :value="document.appointmentNo||'—'"/><van-cell title="预约取件" :value="document.pickupStart||'—'"/><van-cell v-if="document.targetPlatformWarehouse" title="目标平台仓" :value="document.targetPlatformWarehouse"/>
      </van-cell-group>
      <van-cell-group v-if="document.packages?.length" inset title="组包 / 包裹信息" class="mobile-section"><van-cell v-for="p in document.packages" :key="p.id" :title="p.packageNo||p.labelNo||'平台包裹'" :label="`组包 ${p.groupBatchId||'—'} · 标签 ${p.labelNo||p.labelFilename||'—'}`" :value="p.appointmentNo||'—'"/></van-cell-group>
      <van-cell-group inset title="订单明细" class="mobile-section"><van-cell v-for="i in document.items" :key="i.id" :title="`${i.internalSku||'未匹配'} · ${i.productName||i.platformSku}`" :label="`平台 SKU ${i.platformSku} · 已发 ${i.shippedQty} / ${i.orderedQty}`" :value="`剩余 ${Number(i.orderedQty)-Number(i.shippedQty)}`"/></van-cell-group>
      <div class="mobile-form-actions"><van-button type="primary" block :disabled="!canCreateDispatch" @click="createDispatch">新建本地拣货批次</van-button><van-button plain block @click="document=undefined;dispatch=undefined">返回发货单列表</van-button></div>
    </template>
    <van-popup v-model:show="dispatchOpen" position="bottom" round closeable :style="{height:'88%'}" @closed="stopScanner">
      <div class="dispatch-popup">
        <h2>{{ dispatch?.dispatchNo||'拣货批次' }}</h2><p>扫描 SKU 条码并输入本次实拣数量</p>
        <MobileBarcodeSearch v-model="barcode" @search="scanPick"/>
        <van-field v-model.number="pickQty" type="number" label="拣货数量" input-align="right"/>
        <van-cell-group inset title="本批次明细">
          <div v-for="line in dispatch?.items||[]" :key="line.itemId" class="mobile-qty-row"><div><strong>{{ line.internalSku||line.platformSku }}</strong><small>{{ line.productName||line.platformSku }} · {{ line.pickedQty }} / {{ line.plannedQty }}</small></div><van-button size="small" plain type="warning" @click="shortage(line)">缺货</van-button></div>
        </van-cell-group>
        <van-field v-model="handoffReference" label="交接凭证/运单号" placeholder="实际交接后填写"/>
        <div class="mobile-form-actions"><van-button type="primary" block :loading="working" @click="handoff">确认拣货完成并交接</van-button></div>
      </div>
    </van-popup>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { showConfirmDialog, showToast } from 'vant'
import { api } from '../../api'
import MobileBarcodeSearch from './MobileBarcodeSearch.vue'
const type=ref('JIT_ORDER'),query=ref(''),docs=ref<any[]>([]),refreshing=ref(false),document=ref<any>(),dispatch=ref<any>(),dispatchOpen=ref(false),barcode=ref(''),pickQty=ref<number>(1),handoffReference=ref(''),working=ref(false)
const canCreateDispatch=computed(()=>document.value?.items?.some((i:any)=>i.skuId&&Number(i.orderedQty)>Number(i.shippedQty)))
function statusName(v:string){return ({SKU_UNMATCHED:'SKU 未匹配',READY_TO_PICK:'待拣货',PICKING:'拣货中',SHORTAGE:'缺货待采购',PARTIALLY_SHIPPED:'部分已发货',SHIPPED:'已发货',NEEDS_REVIEW:'需核对'} as Record<string,string>)[v]||v}
async function loadDocs(){refreshing.value=true;try{const p=new URLSearchParams({page:'1',pageSize:'100',type:type.value});if(query.value.trim())p.set('q',query.value.trim());const r=await api<any>(`/api/fulfillment/documents?${p}`);docs.value=r.items.filter((d:any)=>!['SHIPPED','CANCELLED'].includes(d.localStatus))}catch(e){showToast(e instanceof Error?e.message:'发货单读取失败')}finally{refreshing.value=false}}
async function openDoc(id:number){try{document.value=await api<any>(`/api/fulfillment/documents/${id}`)}catch(e){showToast(e instanceof Error?e.message:'履约单详情读取失败')}}
async function createDispatch(){if(!document.value)return;const items=document.value.items.filter((i:any)=>i.skuId&&Number(i.orderedQty)>Number(i.shippedQty)).map((i:any)=>({itemId:i.id,quantity:Number(i.orderedQty)-Number(i.shippedQty)}));if(!items.length)return;try{await showConfirmDialog({title:'新建拣货批次',message:`发货仓：${document.value.warehouseName}。该订单各批次使用同一仓库。`});const r=await api<any>(`/api/fulfillment/documents/${document.value.id}/dispatches`,{method:'POST',body:JSON.stringify({idempotencyKey:crypto.randomUUID(),items})});dispatch.value=await api<any>(`/api/fulfillment/dispatches/${r.id}`);dispatchOpen.value=true;handoffReference.value=''}catch(e){if(e!=='cancel')showToast(e instanceof Error?e.message:'创建拣货任务失败')}}
async function scanPick(value:string){barcode.value=value;if(!dispatch.value||!value.trim())return;working.value=true;try{const r=await api<any>(`/api/fulfillment/dispatches/${dispatch.value.id}/pick`,{method:'POST',body:JSON.stringify({barcode:value.trim(),quantity:pickQty.value})});showToast(r.shortageWarning?`已记拣，库存 ${r.availableQty}，请核实缺货`:`已拣 ${r.pickedQty} / ${r.plannedQty}`);barcode.value='';dispatch.value=await api<any>(`/api/fulfillment/dispatches/${dispatch.value.id}`)}catch(e){showToast(e instanceof Error?e.message:'扫码拣货失败')}finally{working.value=false}}
async function shortage(line:any){if(!dispatch.value)return;try{const reason=prompt('请填写缺货原因');if(!reason?.trim())return;await api(`/api/fulfillment/dispatches/${dispatch.value.id}/shortages`,{method:'POST',body:JSON.stringify({itemId:line.itemId,reason})});showToast('缺货已登记，可由管理端关联采购单');dispatch.value=await api<any>(`/api/fulfillment/dispatches/${dispatch.value.id}`);if(document.value)await openDoc(document.value.id)}catch(e){showToast(e instanceof Error?e.message:'缺货登记失败')}}
async function handoff(){if(!dispatch.value?.items?.some((i:any)=>Number(i.pickedQty)>0))return showToast('请先扫描拣货');try{await showConfirmDialog({title:'确认交接发货',message:'确认后将扣减本地库存并生成可追溯库存流水和包材流水。'});working.value=true;const r=await api<any>(`/api/fulfillment/dispatches/${dispatch.value.id}/handoff`,{method:'POST',body:JSON.stringify({handoffReference:handoffReference.value})});showToast(`已交接 ${r.shippedQty} 件${r.warnings?.length?'，有库存/包材预警':''}`);dispatchOpen.value=false;if(document.value)await openDoc(document.value.id);await loadDocs()}catch(e){if(e!=='cancel')showToast(e instanceof Error?e.message:'交接失败')}finally{working.value=false}}
function stopScanner(){barcode.value=''}
onMounted(loadDocs)
</script>

<style scoped>
.dispatch-popup{height:100%;overflow:auto;padding:20px 14px 32px}.dispatch-popup h2{font-size:19px;margin:0 0 5px}.dispatch-popup>p{font-size:13px;color:#8991a0;margin:0 0 12px}
</style>
