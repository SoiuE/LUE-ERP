<template>
  <div class="goods-task-page">
    <div class="mobile-page-title"><h1>收货与库位任务</h1><p>通过采购单、待上架位和货品库位逐项作业</p></div>
    <van-tabs v-model:active="task" sticky @change="load">
      <van-tab v-if="canReceive" title="待收货" name="receipt" />
      <van-tab v-if="canPurchase" title="待确认" name="confirm" />
      <van-tab v-if="canPutaway" title="待上架" name="putaway" />
      <van-tab v-if="canMove" title="移库" name="move" />
    </van-tabs>
    <MobileBarcodeSearch v-model="query" :placeholder="task==='receipt'||task==='confirm'?'采购单 / 快递单 / 供应商订单号':'搜索货品、区域、货架、层或库位'" @search="load" />
    <van-pull-refresh v-model="refreshing" @refresh="load">
      <template v-if="task==='receipt'||task==='confirm'">
        <van-empty v-if="!loading&&!filteredPurchases.length" :description="query?'没有匹配的采购单':task==='receipt'?'暂无待收货采购单':'暂无待确认采购单'" />
        <article v-for="po in filteredPurchases" :key="po.id" class="purchase-card">
          <div class="purchase-heading"><strong>{{ po.poNo }}</strong><van-tag :type="po.status==='PARTIALLY_RECEIVED'?'warning':'primary'">{{ statusText(po.status) }}</van-tag></div>
          <p>{{ po.supplierName }} · {{ po.warehouseName||warehouseName(po.warehouseId) }}</p>
          <MobilePurchaseNumbers :tracking-no="po.trackingNo" :order-no="po.orderNo" />
          <div class="purchase-actions"><van-button size="small" @click="purchaseSheet?.open(po.id,'detail')">查看明细</van-button><van-button v-if="task==='confirm'?canPurchase:canReceive" size="small" type="primary" @click="purchaseSheet?.open(po.id,task==='confirm'?'confirm':'receipt')">{{ task==='confirm'?'确认采购':'开始收货' }}</van-button></div>
        </article>
      </template>
      <template v-else>
        <van-empty v-if="!loading&&!stockRows.length" :description="task==='putaway'?'没有待上架货品':'没有可移库货品'" />
        <van-cell-group v-for="row in filteredStock" :key="`${row.warehouseId}-${row.goodsId}-${row.locationId}`" inset class="mobile-section">
          <van-cell :title="`${row.goodsCode} · ${row.goodsName}`" :label="`${row.specification||'无规格'} · ${locationLabel(row)} · 均价 ¥${row.averageUnitCost||0}`" :value="task==='putaway'?`待上架 ${row.pendingPutawayQty}`:`可用 ${row.availableQty}`"><template #icon><ProductReferenceImage :reference="goodsImages.get(row.goodsId)" :label="row.goodsCode" /></template></van-cell>
          <van-cell v-if="task==='putaway'?canPutaway:canMove" title="选择任务" is-link value="办理" @click="openStockTask(row)" />
        </van-cell-group>
      </template>
      <div v-if="loading" class="mobile-empty-space"><van-loading vertical>读取作业任务</van-loading></div>
    </van-pull-refresh>

    <MobilePurchaseSheet ref="purchaseSheet" @completed="load" />
    <MobileLocationPicker v-model:show="stockPickerOpen" v-model="stockTarget" :options="targetLocations" :binding-goods-id="task==='putaway'?stockTask?.goodsId:undefined" title="选择目标库位"/>

    <van-popup v-model:show="stockOpen" position="bottom" round closeable :style="{height:'72%'}">
      <div class="task-sheet" v-if="stockTask">
        <h2>{{ task==='putaway'?'上架确认':'同仓移库' }}</h2>
        <van-cell-group inset>
          <van-cell title="货品" :value="`${stockTask.goodsCode} · ${stockTask.goodsName}`" />
          <div class="mobile-reference-task"><ProductReferenceImage :reference="goodsImages.get(stockTask.goodsId)" :label="stockTask.goodsCode" size="medium"/><span>{{ stockTask.goodsCode }} · {{ stockTask.goodsName }} · {{ stockTask.specification||'无规格' }}</span></div>
          <van-cell title="仓库" :value="warehouseName(stockTask.warehouseId)" />
          <van-cell title="来源库位"><template #value><LocationDisplay :location="stockTask"/></template></van-cell>
          <van-cell :title="task==='putaway'?'待上架数量':'來源位可用数量'" :value="task==='putaway'?stockTask.pendingPutawayQty:stockTask.availableQty" />
        </van-cell-group>
        <van-cell title="目标库位" :label="targetBindingHint" is-link @click="stockPickerOpen=true"><template #value><LocationDisplay :location="targetLocations.find(x=>x.id===stockTarget)"/></template></van-cell>
        <van-field v-model.number="stockQty" type="number" label="本次数量" input-align="right" placeholder="输入数量" />
        <van-cell title="成本影响预估" :value="`¥${(Number(stockQty||0)*Number(stockTask.averageUnitCost||0)).toFixed(4)}（同仓上架/移库守恒）`" />
        <div class="mobile-form-actions"><van-button block type="primary" :loading="saving" @click="submitStock">确认{{ task==='putaway'?'上架':'移库' }}</van-button></div>
      </div>
    </van-popup>
  </div>
</template>

<script setup lang="ts">
import { computed, inject, watch, ref, type Ref } from 'vue'
import { useRoute } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { api, type AuthUser } from '../../api'
import MobileBarcodeSearch from './MobileBarcodeSearch.vue'
import MobilePurchaseSheet from './MobilePurchaseSheet.vue'
import MobilePurchaseNumbers from './MobilePurchaseNumbers.vue'
import LocationDisplay from '../../components/LocationDisplay.vue'
import MobileLocationPicker from '../../components/MobileLocationPicker.vue'
import { locationLabel, locationSearch } from '../../utils/location'
import ProductReferenceImage from '../../components/ProductReferenceImage.vue'
import { useProductReferences } from '../../utils/productReferences'

const authUser=inject<Ref<AuthUser|undefined>>('mobileAuthUser')
const route=useRoute()
const root='/api/goods-procurement',task=ref<'receipt'|'confirm'|'putaway'|'move'>('receipt'),query=ref(''),loading=ref(false),refreshing=ref(false),saving=ref(false)
const purchases=ref<any[]>([]),stockRows=ref<any[]>([]),warehouses=ref<any[]>([]),locations=ref<any[]>([])
const goodsImages=useProductReferences('WAREHOUSE_GOODS')
const purchaseSheet=ref<InstanceType<typeof MobilePurchaseSheet>>()
const putawayLocations=ref<any[]>([])
const targetLocations=computed(()=>task.value==='putaway'?putawayLocations.value:locations.value.filter(x=>!['PENDING_PUTAWAY','QUARANTINE'].includes(x.locationType)&&x.id!==stockTask.value?.locationId))
const stockPickerOpen=ref(false),stockOpen=ref(false),stockTask=ref<any>(),stockTarget=ref<number>(),stockQty=ref<number>(0)
const targetBindingHint=computed(()=>{if(task.value!=='putaway'||!stockTarget.value)return '';const target=targetLocations.value.find(x=>x.id===stockTarget.value);return target?Number(target.assignmentCount)>0?'本货品已绑定，本次上架沿用绑定':'空闲库位，上架成功后新增货品位点绑定':''})
const canReceive=computed(()=>authUser?.value?.permissions.includes('warehouse.goods.receive')===true)
const canPurchase=computed(()=>authUser?.value?.permissions.includes('warehouse.goods.purchase')===true)
const canPutaway=computed(()=>authUser?.value?.permissions.includes('warehouse.goods.putaway')===true)
const canMove=computed(()=>authUser?.value?.permissions.includes('warehouse.goods.move')===true)
const requestKeys=new Map<string,string>()
function requestKey(payload:any){const signature=JSON.stringify(payload);if(!requestKeys.has(signature))requestKeys.set(signature,crypto.randomUUID());return {signature,key:requestKeys.get(signature)!}}
function requestCommitted(signature:string){requestKeys.delete(signature)}
const filteredPurchases=computed(()=>purchases.value.filter(x=>!query.value||`${x.poNo} ${x.trackingNo||''} ${x.orderNo||''} ${x.supplierName||''}`.toLowerCase().includes(query.value.toLowerCase())))
const filteredStock=computed(()=>stockRows.value.filter(x=>!query.value||`${x.goodsCode} ${x.goodsName} ${locationSearch(x)}`.toLowerCase().includes(query.value.toLowerCase())))
function statusText(status:string){return ({DRAFT:'待确认',CONFIRMED:'待收货',PARTIALLY_RECEIVED:'部分收货',RECEIVED:'已收齐'} as Record<string,string>)[status]||'未知状态'}
function warehouseName(id:number){return warehouses.value.find(x=>x.id===id)?.warehouseName||`仓库 ${id||'—'}`}
function locationName(id?:number){return locationLabel(locations.value.find(x=>x.id===id))}
let loadVersion=0
async function load(){
  const version=++loadVersion,current=task.value;loading.value=true;refreshing.value=true
  try{
    if(current==='receipt'||current==='confirm'){
      const result=await api<{items:any[]}>(`${root}/purchases`)
      if(version===loadVersion)purchases.value=result.items.filter(x=>current==='confirm'?x.status==='DRAFT':['CONFIRMED','PARTIALLY_RECEIVED'].includes(x.status))
    }else{
      const [stock,lookups]=await Promise.all([api<{items:any[]}>(`${root}/stock`),api<any>(`${root}/lookups`)])
      if(version===loadVersion){stockRows.value=stock.items.filter(x=>current==='putaway'?Number(x.pendingPutawayQty)>0:Number(x.availableQty)>0&&!['PENDING_PUTAWAY','QUARANTINE'].includes(x.locationType));warehouses.value=lookups.warehouses;void goodsImages.load(stockRows.value.map(x=>x.goodsId))}
    }
  }catch(e){if(version===loadVersion)showToast(e instanceof Error?e.message:'任务读取失败')}
  finally{if(version===loadVersion){loading.value=false;refreshing.value=false}}
}
async function loadLocations(warehouseId:number){const r=await api<{items:any[]}>(`${root}/locations?warehouseId=${warehouseId}`);locations.value=r.items}
async function loadPutawayLocations(warehouseId:number,goodsId:number){putawayLocations.value=[];const r=await api<{items:any[]}>(`${root}/putaway-locations?warehouseId=${warehouseId}&goodsId=${goodsId}`);putawayLocations.value=r.items}
async function openStockTask(row:any){stockTask.value=row;stockQty.value=0;stockTarget.value=undefined;putawayLocations.value=[];await loadLocations(row.warehouseId);if(task.value==='putaway')await loadPutawayLocations(row.warehouseId,row.goodsId);stockOpen.value=true}
async function submitStock(){if(saving.value)return;if(!stockTask.value||!stockTarget.value||Number(stockQty.value)<=0)return showToast('请选择目标库位并填写数量');if(task.value==='putaway'&&!putawayLocations.value.some(x=>x.id===stockTarget.value))return showToast('请选择本货品已绑定位或符合条件的空闲位');const payload={warehouseId:stockTask.value.warehouseId,goodsId:stockTask.value.goodsId,sourceLocationId:stockTask.value.locationId,targetLocationId:stockTarget.value,quantity:stockQty.value};const target=targetLocations.value.find(x=>x.id===stockTarget.value);const binds=task.value==='putaway'&&Number(target?.assignmentCount)===0;const cost=(Number(stockQty.value)*Number(stockTask.value.averageUnitCost||0)).toFixed(4);try{await showConfirmDialog({title:task.value==='putaway'?'确认上架':'确认同仓移库',message:`仓库：${warehouseName(stockTask.value.warehouseId)}\n货品：${stockTask.value.goodsCode} · ${stockTask.value.goodsName}\n来源：${locationLabel(stockTask.value)}\n目标：${locationName(stockTarget.value)}${binds?'（完成上架时绑定本货品）':''}\n数量：${stockQty.value}\n成本金额：¥${cost}（总成本守恒）`});const req=requestKey(payload);saving.value=true;const path=task.value==='putaway'?'putaway':'moves';const result=await api<any>(`${root}/${path}`,{method:'POST',body:JSON.stringify({...payload,idempotencyKey:req.key})});requestCommitted(req.signature);showToast({message:task.value==='putaway'?('上架完成，单据 #'+result.documentId+'，'+(result.bindingCreated?'已新增货品位点绑定':'使用已有货品位点绑定')+'，已生成上架流水'):'移库完成，总量与总成本守恒',duration:3500});stockOpen.value=false;await load()}catch(e){if(e!=='cancel')showToast(e instanceof Error?e.message:'库存作业失败')}finally{saving.value=false}}
watch([()=>authUser?.value,()=>route.query.task,()=>route.query.stage],()=>{
  if(!authUser?.value)return
  const allowed={receipt:canReceive.value,confirm:canPurchase.value,putaway:canPutaway.value,move:canMove.value}
  const requested=String(route.query.task||route.query.stage||'receipt') as keyof typeof allowed
  task.value=allowed[requested]?requested:(Object.keys(allowed) as (keyof typeof allowed)[]).find(key=>allowed[key])||'receipt'
  void load()
},{immediate:true})
</script>

<style scoped>
.purchase-card{background:#fff;border-radius:12px;padding:14px;margin-bottom:12px;overflow-wrap:anywhere}.purchase-heading{display:flex;align-items:center;justify-content:space-between;gap:10px}.purchase-heading strong{font-size:16px;min-width:0}.purchase-card p{font-size:13px;color:#747e8d;line-height:1.6}.purchase-actions{display:flex;justify-content:flex-end;gap:10px;margin-top:14px}.purchase-actions .van-button{min-height:44px;min-width:96px}
.task-sheet{height:100%;overflow:auto;padding:20px 14px 30px}.task-sheet h2{font-size:19px;margin:3px 0 8px}.sheet-subtitle{font-size:13px;color:#87909e;margin:0 0 14px}.task-sheet .mobile-form-actions{position:sticky;bottom:0;padding:10px 0 max(12px,env(safe-area-inset-bottom));background:#f5f7fa}
.native-select{width:100%;min-width:0;border:0;background:transparent;color:#323743;font:inherit;text-align:right;outline:none}
.mobile-reference-task{display:flex;align-items:center;gap:10px;margin:10px 0;padding:10px;background:#fff;border-radius:10px}.mobile-reference-task span{overflow-wrap:anywhere;font-size:13px}
</style>
