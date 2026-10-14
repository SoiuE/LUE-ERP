<template>
  <div>
    <div class="mobile-page-title"><h1>采购作业</h1><p>确认到货、分批入库和现场照片留痕</p></div>
    <van-tabs v-model:active="stage" sticky @change="load"><van-tab title="待确认" name="confirm"/><van-tab title="待入库" name="receipt"/></van-tabs>
    <MobileBarcodeSearch v-model="q" :placeholder="stage==='receipt'?'扫描/输入快递单号、采购单号':'搜索采购单、订单编号、供应商、SKU'" @search="load"/>
    <van-pull-refresh v-model="refreshing" @refresh="load">
      <van-empty v-if="!loading&&!orders.length" description="暂无待处理采购单"/>
      <van-card v-for="order in orders" :key="order.id" class="mobile-card" :title="order.poNo" :desc="`${order.supplierName} · ${order.itemCount} 个 SKU`">
        <template #tags><van-tag v-if="order.trackingNo" plain type="primary">快递 {{ order.trackingNo }}</van-tag><van-tag v-if="order.orderNo" plain type="success" style="margin-left:5px">订单 {{ order.orderNo }}</van-tag><van-tag v-if="order.isAbnormal" type="danger" style="margin-left:5px">异常</van-tag></template>
        <template #footer><van-button size="small" type="primary" @click="open(order)">{{ stage==='confirm'?'确认到货':'办理入库' }}</van-button></template>
      </van-card>
      <div v-if="loading" class="mobile-empty-space"><van-loading vertical>读取采购单</van-loading></div>
    </van-pull-refresh>

    <van-popup v-model:show="detailOpen" position="bottom" round closeable :style="{height:'88%'}" @closed="clearPhoto">
      <div class="purchase-sheet" v-if="detail">
        <h2>{{ stage==='confirm'?'确认采购到货':'采购分批入库' }}</h2><p class="sheet-subtitle">{{ detail.poNo }} · {{ detail.supplierName }}</p>
        <van-cell-group inset>
          <van-cell title="快递单号" :value="detail.trackingNo||'未填写'"/><van-cell title="订单编号" :value="detail.orderNo||'未填写'"/>
        </van-cell-group>
        <van-notice-bar v-if="detail.isAbnormal" wrapable :scrollable="false" left-icon="warning-o" :text="`采购异常：${detail.abnormalReason||'请查看采购单'}`"/>
        <van-field v-if="detail.isAbnormal" v-model="abnormalHandlingNote" label="异常处理结果" type="textarea" rows="2" placeholder="处理异常时必填"/>
        <van-button v-if="detail.isAbnormal" block plain type="warning" class="abnormal-action" :loading="saving" @click="resolveAbnormal">提交异常处理结果</van-button>
        <template v-else><van-field v-model="abnormalReason" label="到货异常（选填）" type="textarea" rows="2" placeholder="填写后可将采购单标记为异常"/><van-button v-if="abnormalReason.trim()" block plain type="danger" class="abnormal-action" :loading="saving" @click="markAbnormal">标记采购异常</van-button></template>
        <div v-for="line in detail.items" :key="line.id" class="mobile-result-card">
          <div class="mobile-sku-title">{{ line.internalSku }} · {{ line.productName }}</div>
          <div class="mobile-sku-meta">采购 {{ line.orderedQty }} · 已确认 {{ line.confirmedQty }} · 已入库 {{ line.receivedQty }}</div>
          <div class="qty-entry"><span>{{ stage==='confirm'?'本次确认数量':'本次入库数量' }}</span><van-stepper v-if="stage==='confirm'" v-model="line.mobileQty" :min="line.receivedQty||0" :decimal-length="4" :step="1" input-width="78px"/><van-stepper v-else v-model="line.mobileQty" :min="0" :decimal-length="4" :step="1" input-width="78px"/></div>
          <van-field v-if="Number(line.mobileQty)>Number(line.orderedQty)" v-model="line.overReceiptReason" label="超收原因" placeholder="必填"/>
        </div>
        <div class="photo-box"><div class="photo-title">现场照片（选填）</div><van-uploader v-model="photos" accept="image/*" capture="environment" :max-count="3" :after-read="uploadPhoto" :before-delete="removePhoto"/><p>照片会作为该次采购操作附件保存。</p></div>
        <div class="purchase-actions"><van-button block type="primary" :loading="saving" @click="submit">{{ stage==='confirm'?'确认到货':'提交入库' }}</van-button></div>
      </div>
    </van-popup>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { api, getToken } from '../../api'
import MobileBarcodeSearch from './MobileBarcodeSearch.vue'
const route=useRoute(),stage=ref<'confirm'|'receipt'>('confirm'),q=ref(''),orders=ref<any[]>([]),detail=ref<any>(),detailOpen=ref(false),loading=ref(false),refreshing=ref(false),saving=ref(false)
const photos=ref<any[]>([]),attachmentIds=ref<number[]>([]),abnormalReason=ref(''),abnormalHandlingNote=ref('')
async function load(){loading.value=true;refreshing.value=true;try{const params=new URLSearchParams({stage:stage.value,page:'1',pageSize:'50'});if(q.value)params.set('q',q.value);orders.value=(await api<any>(`/api/mobile/purchases?${params}`)).items}catch(e){showToast(e instanceof Error?e.message:'采购单加载失败')}finally{loading.value=false;refreshing.value=false}}
async function open(order:any){try{detail.value=await api<any>(`/api/mobile/purchases/${order.id}`);detail.value.items.forEach((x:any)=>{x.mobileQty=stage.value==='confirm'?(Number(x.confirmedQty)||Number(x.orderedQty)):0;x.overReceiptReason=''});photos.value=[];attachmentIds.value=[];abnormalReason.value='';abnormalHandlingNote.value='';detailOpen.value=true}catch(e){showToast(e instanceof Error?e.message:'采购明细加载失败')}}
async function uploadPhoto(file:any){try{const form=new FormData();form.append('file',file.file);const headers:Record<string,string>={};const token=getToken();if(token)headers.Authorization=`Bearer ${token}`;const response=await fetch('/api/mobile/attachments',{method:'POST',headers,body:form});const data=await response.json();if(!response.ok||!data.success)throw new Error(data.message||'照片上传失败');file.attachmentId=data.data.id;attachmentIds.value.push(data.data.id);file.status='done';showToast('照片已上传')}catch(e){file.status='failed';showToast(e instanceof Error?e.message:'照片上传失败')}}
function removePhoto(file:any){if(file.attachmentId)attachmentIds.value=attachmentIds.value.filter(id=>id!==file.attachmentId);return true}
async function markAbnormal(){if(!detail.value||!abnormalReason.value.trim())return;try{await showConfirmDialog({title:'标记采购异常',message:`${detail.value.poNo}：${abnormalReason.value}`})}catch{return}saving.value=true;try{await api(`/api/mobile/purchases/${detail.value.id}/abnormal`,{method:'POST',body:JSON.stringify({reason:abnormalReason.value})});showToast('已标记采购异常');detail.value.isAbnormal=true;detail.value.abnormalReason=abnormalReason.value;abnormalReason.value='';await load()}catch(e){showToast(e instanceof Error?e.message:'异常标记失败')}finally{saving.value=false}}
async function resolveAbnormal(){if(!detail.value||!abnormalHandlingNote.value.trim()){showToast('请填写异常处理结果');return}try{await showConfirmDialog({title:'处理采购异常',message:'确认提交异常处理结果？'})}catch{return}saving.value=true;try{await api(`/api/mobile/purchases/${detail.value.id}/abnormal/resolve`,{method:'POST',body:JSON.stringify({handlingNote:abnormalHandlingNote.value})});showToast('异常处理结果已记录');detail.value.isAbnormal=false;detail.value.abnormalHandlingNote=abnormalHandlingNote.value;abnormalHandlingNote.value='';await load()}catch(e){showToast(e instanceof Error?e.message:'异常处理失败')}finally{saving.value=false}}
async function submit(){if(!detail.value)return;const lines=detail.value.items.filter((x:any)=>stage.value==='confirm'||Number(x.mobileQty)>0);if(!lines.length){showToast('请录入本次入库数量');return}if(lines.some((x:any)=>Number(x.mobileQty)>Number(x.orderedQty)&&!String(x.overReceiptReason||'').trim())){showToast('超出采购数量时请填写原因');return}
  try{await showConfirmDialog({title:stage.value==='confirm'?'确认到货':'确认采购入库',message:`${detail.value.poNo}，${stage.value==='confirm'?'确认到货数量':'本次入库数量'}将提交并生成操作记录。`})}catch{return}
  saving.value=true;try{const payload={items:lines.map((x:any)=>stage.value==='confirm'?{itemId:x.id,confirmedQty:x.mobileQty,overReceiptReason:x.overReceiptReason}:{itemId:x.id,quantity:x.mobileQty,overReceiptReason:x.overReceiptReason}),attachmentIds:attachmentIds.value};const url=stage.value==='confirm'?`/api/mobile/purchases/${detail.value.id}/confirm`:`/api/mobile/purchases/${detail.value.id}/receipts`;const result=await api<any>(url,{method:'POST',body:JSON.stringify(payload)});showToast(stage.value==='confirm'?'采购到货已确认':`入库完成 ${result.receiptNo||''}`);detailOpen.value=false;await load()}catch(e){showToast(e instanceof Error?e.message:'采购操作失败')}finally{saving.value=false}}
function clearPhoto(){photos.value=[];attachmentIds.value=[]}
watch(()=>route.query.stage,value=>{if(value==='receipt'||value==='confirm'){stage.value=value;load()}})
onMounted(()=>{if(route.query.stage==='receipt')stage.value='receipt';load()})
</script>

<style scoped>
.purchase-sheet{height:100%;overflow:auto;padding:20px 15px 28px}.purchase-sheet h2{font-size:19px;margin:3px 0 5px}.sheet-subtitle{font-size:13px;color:#8c93a1;margin:0 0 15px}.qty-entry{display:flex;align-items:center;justify-content:space-between;gap:9px;margin-top:14px;font-size:13px}.photo-box{margin:16px 4px;padding:14px;background:#fff;border-radius:10px}.photo-title{font-size:14px;font-weight:600;margin-bottom:9px}.photo-box p{font-size:12px;color:#949aa6}.purchase-actions{position:sticky;bottom:0;padding:12px 3px max(10px,env(safe-area-inset-bottom));background:#f5f7fa}.purchase-actions .van-button{height:46px}.abnormal-action{margin:8px 0}
</style>
