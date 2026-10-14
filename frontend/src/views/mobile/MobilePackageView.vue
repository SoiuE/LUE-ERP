<template>
  <div>
    <div class="mobile-page-title"><h1>包材出入库</h1><p>选择包材并记录现场库存变化</p></div>
    <van-tabs v-model:active="operation" @change="selected=undefined"><van-tab title="包材入库" name="INBOUND"/><van-tab title="包材出库" name="OUTBOUND"/><van-tab title="包材盘点" name="COUNT"/></van-tabs>
    <van-search v-model="q" placeholder="搜索包材编码或名称" @search="load"/>
    <van-empty v-if="!selected&&!materials.length" description="暂无包材或没有匹配结果"/>
    <van-cell-group v-if="!selected&&materials.length" inset>
      <van-cell v-for="material in materials" :key="material.id" is-link :title="material.code" :label="`${material.name} · ${category(material.category)} · ${material.unit}`" :value="`库存 ${material.availableQty}`" @click="select(material)"/>
    </van-cell-group>
    <div v-if="selected" class="mobile-result-card"><div class="mobile-sku-title">{{ selected.code }} · {{ selected.name }}</div><div class="mobile-sku-meta">{{ category(selected.category) }} · {{ selected.unit }} · 当前库存 {{ selected.availableQty }}</div><van-button size="small" plain type="primary" @click="selected=undefined">重新选择</van-button></div>
    <van-cell-group v-if="selected" inset>
      <van-field v-model.number="quantity" type="number" :label="operation==='INBOUND'?'入库数量':operation==='COUNT'?'实盘数量':'出库数量'" input-align="right" placeholder="输入数量"/>
      <van-field v-model="reason" :label="operation==='COUNT'?'盘点原因':'备注'" type="textarea" rows="2" :placeholder="operation==='COUNT'?'盘点必须填写原因':'选填；库存不足时会自动创建异常'"/>
    </van-cell-group>
    <div v-if="selected" class="photo-box"><div class="photo-title">现场照片（选填）</div><van-uploader v-model="photos" accept="image/*" capture="environment" :max-count="3" :after-read="uploadPhoto" :before-delete="removePhoto"/></div>
    <div v-if="selected" class="mobile-form-actions"><van-button block type="primary" :loading="saving" @click="submit">确认{{ operation==='INBOUND'?'入库':operation==='COUNT'?'盘点':'出库' }}</van-button></div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { showConfirmDialog, showToast } from 'vant'
import { api } from '../../api'
import { onPhotoError, uploadWarehousePhoto } from './mobileHelpers'
const operation=ref<'INBOUND'|'OUTBOUND'|'COUNT'>('INBOUND'),q=ref(''),materials=ref<any[]>([]),selected=ref<any>(),quantity=ref<number>(),reason=ref(''),saving=ref(false),photos=ref<any[]>([]),attachmentIds=ref<number[]>([])
const categories:Record<string,string>={PRODUCT_PACKAGING:'产品包装',SHIPPING_MATERIAL:'发货打包材料'}
function category(value:string){return categories[value]||value}
async function load(){try{materials.value=(await api<any>(`/api/mobile/packages${q.value?`?q=${encodeURIComponent(q.value)}`:''}`)).items}catch(e){showToast(e instanceof Error?e.message:'包材加载失败')}}
function select(row:any){selected.value=row;quantity.value=undefined;reason.value='';photos.value=[];attachmentIds.value=[]}
async function uploadPhoto(file:any){try{const data=await uploadWarehousePhoto(file);file.attachmentId=data.id;file.status='done';attachmentIds.value.push(data.id)}catch(e){file.status='failed';onPhotoError(e)}}
function removePhoto(file:any){if(file.attachmentId)attachmentIds.value=attachmentIds.value.filter(id=>id!==file.attachmentId);return true}
async function submit(){if(!selected.value||quantity.value===undefined||Number.isNaN(Number(quantity.value))||quantity.value<0||(['INBOUND','OUTBOUND'].includes(operation.value)&&quantity.value===0)){showToast(operation.value==='COUNT'?'请选择包材并填写实盘数':'请选择包材并填写大于 0 的数量');return}if(operation.value==='COUNT'&&!reason.value.trim()){showToast('包材盘点必须填写原因');return}try{await showConfirmDialog({title:`确认包材${operation.value==='INBOUND'?'入库':operation.value==='COUNT'?'盘点':'出库'}`,message:`${selected.value.code}，数量 ${quantity.value}${operation.value==='COUNT'?'（实盘数）':''}。提交后会生成包材流水。`})}catch{return}saving.value=true;try{const result=await api<any>('/api/mobile/packages/operations',{method:'POST',body:JSON.stringify({operation:operation.value,materialId:selected.value.id,quantity:quantity.value,reason:reason.value,attachmentIds:attachmentIds.value})});showToast(result.warning||'操作成功，包材流水已生成');selected.value=undefined;quantity.value=undefined;reason.value='';photos.value=[];attachmentIds.value=[];await load()}catch(e){showToast(e instanceof Error?e.message:'包材操作失败')}finally{saving.value=false}}
onMounted(load)
</script>

<style scoped>
.photo-box{background:#fff;border-radius:10px;padding:14px;margin-top:13px}.photo-title{font-size:14px;font-weight:600;margin-bottom:10px}
</style>
