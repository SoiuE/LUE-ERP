<template>
  <div>
    <div class="mobile-page-title"><h1>商品库存作业</h1><p>出库、盘点和 SKU 条码查询</p></div>
    <van-tabs v-model:active="mode" @change="reset"><van-tab title="商品出库" name="outbound"/><van-tab title="库存盘点" name="count"/></van-tabs>
    <MobileBarcodeSearch v-model="q" @search="search"/>
    <van-empty v-if="!results.length&&!selected" description="扫码或搜索内部 SKU、条码、商品名称"/>
    <van-cell-group v-if="results.length&&!selected" inset class="mobile-section">
      <van-cell v-for="sku in results" :key="sku.id" is-link :title="sku.internalSku" :label="`${sku.productName} · 条码 ${sku.barcode||'无'}`" :value="`库存 ${sku.availableQty}`" @click="select(sku)"/>
    </van-cell-group>
    <div v-if="selected" class="mobile-result-card">
      <div class="mobile-sku-title">{{ selected.internalSku }} · {{ selected.productName }}</div><div class="mobile-sku-meta">条码：{{ selected.barcode||'—' }}　可用库存：{{ selected.availableQty }}</div>
      <van-button size="small" plain type="primary" @click="selected=undefined">重新选择</van-button>
    </div>
    <van-cell-group v-if="selected" inset>
      <template v-if="mode==='outbound'">
        <van-cell title="出库类型"><template #value><select v-model="outboundType" class="native-select"><option value="JIT">JIT 出库（扣商品及默认包材）</option><option value="STOCKING">备货出库</option><option value="MANUAL">手工出库</option></select></template></van-cell>
        <van-field v-model.number="quantity" type="number" label="出库数量" input-align="right" placeholder="输入数量"/>
        <van-field v-model="reason" label="备注" placeholder="选填"/>
        <van-notice-bar v-if="outboundType==='JIT'" wrapable :scrollable="false" text="JIT 出库会扣减本地商品库存，并根据 SKU 默认方案扣减产品包装和发货打包材料。仓发订单请勿在本地执行出库。"/>
      </template>
      <template v-else><van-field v-model.number="quantity" type="number" label="实盘数量" input-align="right" placeholder="填写盘点后的实际数量"/><van-field v-model="reason" type="textarea" rows="2" label="盘点原因" placeholder="必填，例如月度盘点、库位复核"/></template>
    </van-cell-group>
    <div v-if="selected" class="photo-box"><div class="photo-title">现场照片（选填）</div><van-uploader v-model="photos" accept="image/*" capture="environment" :max-count="3" :after-read="uploadPhoto" :before-delete="removePhoto"/></div>
    <div v-if="selected" class="mobile-form-actions"><van-button type="primary" block :loading="saving" @click="submit">{{ mode==='count'?'提交盘点':'提交出库' }}</van-button></div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { api } from '../../api'
import MobileBarcodeSearch from './MobileBarcodeSearch.vue'
import { onPhotoError, uploadWarehousePhoto } from './mobileHelpers'
const route=useRoute(),mode=ref<'outbound'|'count'>('outbound'),outboundType=ref('JIT'),q=ref(''),results=ref<any[]>([]),selected=ref<any>(),quantity=ref<number>(),reason=ref(''),saving=ref(false),photos=ref<any[]>([]),attachmentIds=ref<number[]>([])
async function search(value=q.value){q.value=value;selected.value=undefined;try{results.value=(await api<any>(`/api/mobile/skus?q=${encodeURIComponent(value)}`)).items}catch(e){showToast(e instanceof Error?e.message:'SKU 查询失败')}}
function select(sku:any){selected.value=sku;quantity.value=mode.value==='count'?Number(sku.availableQty):undefined;results.value=[]}
function reset(name:string){mode.value=name as any;selected.value=undefined;quantity.value=undefined;reason.value='';if(mode.value==='count'&&route.query.mode==='count')mode.value='count'}
async function uploadPhoto(file:any){try{const data=await uploadWarehousePhoto(file);attachmentIds.value.push(data.id);file.attachmentId=data.id;file.status='done'}catch(e){file.status='failed';onPhotoError(e)}}
function removePhoto(file:any){if(file.attachmentId)attachmentIds.value=attachmentIds.value.filter(id=>id!==file.attachmentId);return true}
async function submit(){if(!selected.value||quantity.value===undefined||Number.isNaN(Number(quantity.value))){showToast('请选择 SKU 并填写数量');return}if(mode.value==='outbound'&&Number(quantity.value)<=0){showToast('出库数量必须大于 0');return}if(mode.value==='count'&&!reason.value.trim()){showToast('盘点必须填写原因');return}
  try{await showConfirmDialog({title:mode.value==='count'?'确认盘点':'确认商品出库',message:`${selected.value.internalSku}，数量 ${quantity.value}${mode.value==='count'?'（实盘数）':''}。提交后将生成库存流水。`})}catch{return}
  saving.value=true;try{const body={operation:mode.value==='count'?'COUNT':'OUTBOUND',skuId:selected.value.id,quantity:quantity.value,reason:reason.value,outboundType:outboundType.value,attachmentIds:attachmentIds.value};const result=await api<any>('/api/mobile/inventory/operations',{method:'POST',body:JSON.stringify(body)});showToast(result.warning||result.packageWarnings?.join('；')||'操作成功，库存流水已生成');selected.value=undefined;quantity.value=undefined;reason.value='';photos.value=[];attachmentIds.value=[];await search(q.value)}catch(e){showToast(e instanceof Error?e.message:'库存操作失败')}finally{saving.value=false}}
watch(()=>route.query.mode,value=>{if(value==='count')mode.value='count'})
onMounted(()=>{if(route.query.mode==='count')mode.value='count';if(route.query.q){q.value=String(route.query.q);search(q.value)}})
</script>

<style scoped>
.native-select{border:0;background:#fff;color:#323b4b;max-width:220px;font-size:13px;text-align:right}.photo-box{background:#fff;border-radius:10px;padding:14px;margin-top:13px}.photo-title{font-size:14px;font-weight:600;margin-bottom:10px}
</style>
