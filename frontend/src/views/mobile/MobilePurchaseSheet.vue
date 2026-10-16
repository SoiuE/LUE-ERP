<template>
  <van-popup v-model:show="visible" position="bottom" class="purchase-popup" :close-on-click-overlay="false">
    <section v-if="purchase" class="purchase-screen" :style="screenStyle">
      <van-nav-bar :title="mode==='receipt'?'采购收货':mode==='confirm'?'确认采购':'采购明细'" left-text="返回" left-arrow @click-left="close" />
      <main ref="body" class="purchase-body" @focusin="revealInput">
        <article class="summary-card">
          <div class="card-heading"><strong>{{ purchase.poNo }}</strong><van-tag type="primary">{{ statusText(purchase.status) }}</van-tag></div>
          <p>{{ purchase.supplierName }} · {{ purchase.warehouseName }}</p>
          <MobilePurchaseNumbers :tracking-no="purchase.trackingNo" :order-no="purchase.orderNo" />
          <p v-if="purchase.expectedArrivalDate">预计到货：{{ purchase.expectedArrivalDate }}</p>
          <p v-if="purchase.note">采购备注：{{ purchase.note }}</p>
        </article>
        <div v-if="mode==='receipt'" class="selection-bar">
          <van-checkbox :model-value="allSelected" :disabled="saving||!receivable.length" @update:model-value="selectAll(Boolean($event))">全选</van-checkbox>
          <span>已选 {{ selected.length }} 项</span><button @click="expandAll">{{ allExpanded?'收起全部':'展开全部' }}</button>
        </div>
        <van-notice-bar v-if="mode==='receipt'&&!locations.length" wrapable :scrollable="false" text="没有可用收货库位，请先在仓库中配置有效库位。" />
        <article v-for="line in purchase.items" :id="`mobile-receipt-${line.id}`" :key="line.id" class="line-card" :class="{complete:mode==='receipt'&&!remaining(line)}">
          <div class="line-heading">
            <van-checkbox v-if="mode==='receipt'" v-model="line.selected" :disabled="saving||!remaining(line)" :aria-label="`选择${line.goodsName}`" />
            <div class="goods-title"><strong>{{ line.goodsName }}</strong><small>{{ line.goodsCode }} · {{ line.unit||'—' }}</small></div>
            <button v-if="mode==='receipt'&&remaining(line)" :aria-expanded="line.expanded" @click="line.expanded=!line.expanded">{{ line.expanded?'收起':'展开' }}</button>
            <van-tag v-if="mode==='receipt'&&!remaining(line)" type="success">已收齐</van-tag>
          </div>
          <div class="quantities"><span>采购<b>{{ line.orderedQty }}</b></span><span>确认<b>{{ line.confirmedQty }}</b></span><span>已收<b>{{ line.receivedQty }}</b></span><span>待收<b>{{ remaining(line) }}</b></span></div>
          <template v-if="mode==='confirm'">
            <van-field v-model="line.inputQty" type="number" inputmode="decimal" label="确认采购数量" :disabled="saving" placeholder="输入确认数量" />
            <van-field v-model="line.reason" label="调整原因" :disabled="saving" placeholder="超量确认时必填" />
          </template>
          <template v-if="mode==='receipt'&&remaining(line)&&line.expanded">
            <van-field v-model="line.receiveQty" type="number" inputmode="decimal" label="本次收货数量" :disabled="saving" placeholder="输入收货数量" />
            <van-field v-model="line.actualUnitPrice" type="number" inputmode="decimal" label="实际单价（元）" :disabled="saving" placeholder="输入实际单价" />
            <van-cell title="收货库位" is-link :value="locationName(line.locationId)" :class="{'invalid-location':line.locationId&&!validLocation(line)}" @click="chooseFor(line)" />
            <p v-if="line.locationId" class="binding-hint">{{ locationEffect(line.locationId) }}</p>
            <van-field v-if="Number(line.receiveQty)>remaining(line)" v-model="line.overReceiptReason" label="超收原因" type="textarea" rows="2" :disabled="saving" placeholder="超出确认数量，必须填写原因" maxlength="500" show-word-limit />
            <van-field v-model="line.receiptNote" label="收货备注" type="textarea" autosize rows="1" :disabled="saving" placeholder="选填" maxlength="1000" show-word-limit />
            <div class="line-amount">本项金额 <strong>¥{{ money(Number(line.receiveQty)*Number(line.actualUnitPrice)) }}</strong></div>
          </template>
        </article>
      </main>
      <footer v-if="mode!=='detail'" class="purchase-footer">
        <template v-if="mode==='receipt'">
          <div class="receipt-total">已选 {{ selected.length }} 项 <strong>¥{{ money(selectedTotal) }}</strong></div>
          <div class="receipt-buttons"><van-button :disabled="saving||!canReceive||!receivable.length" @click="prepare(true)">按采购单全部收货</van-button><van-button type="primary" :loading="saving" :disabled="!canReceive||!selected.length" @click="prepare(false)">收货已选货品</van-button></div>
        </template>
        <van-button v-else block type="primary" :loading="saving" :disabled="!canPurchase" @click="confirmPurchase">确认采购数量</van-button>
      </footer>
    </section>
  </van-popup>

  <van-popup v-model:show="pickerOpen" position="bottom" round class="location-popup">
    <div class="picker-heading"><strong>选择收货库位</strong><van-button size="small" @click="pickerOpen=false">关闭</van-button></div>
    <p class="picker-subtitle">{{ purchase?.warehouseName }} · {{ pickerLine?.goodsName }}</p>
    <van-search v-model="locationQuery" placeholder="搜索仓库、区域、货架、层、位或编码" />
    <div class="location-options">
      <van-empty v-if="!pickerLocations.length" description="没有符合条件的库位" />
      <button v-for="loc in pickerLocations" :key="loc.id" class="location-option" @click="chooseLocation(loc)"><span><LocationDisplay :location="loc"/><small>{{ locationEffect(loc.id) }}</small></span><van-tag :type="loc.locationType==='PENDING_PUTAWAY'?'warning':Number(loc.assignmentCount)>0?'primary':'success'">{{ loc.locationType==='PENDING_PUTAWAY'?'共用待上架':Number(loc.assignmentCount)>0?'本货品已绑定':'空闲库位' }}</van-tag></button>
    </div>
  </van-popup>

  <van-popup v-model:show="reviewOpen" position="bottom" round class="review-popup" :close-on-click-overlay="!saving">
    <section class="review-screen" :style="{maxHeight:`${viewportHeight}px`}">
      <div class="picker-heading"><strong>{{ reviewAll?'按采购单全部收货':'确认所选货品收货' }}</strong><van-button size="small" :disabled="saving" @click="reviewOpen=false">返回核对</van-button></div>
      <div class="review-body">
        <p>{{ purchase?.poNo }} · {{ purchase?.supplierName }}</p>
        <article v-for="line in reviewLines" :key="line.itemId" class="summary-card"><strong>{{ line.goodsName }}</strong><p>{{ line.goodsCode }}</p><p>数量 {{ line.quantity }} · 单价 ¥{{ money(line.actualUnitPrice) }}</p><p>金额 ¥{{ money(line.quantity*line.actualUnitPrice) }}</p><p>库位：{{ locationName(line.locationId) }}</p><p class="binding-hint">{{ locationEffect(line.locationId) }}</p><p v-if="line.overReceiptReason">超收原因：{{ line.overReceiptReason }}</p><p v-if="line.note">备注：{{ line.note }}</p></article>
      </div>
      <footer class="purchase-footer"><div class="receipt-total">共 {{ reviewLines.length }} 项 <strong>¥{{ money(reviewTotal) }}</strong></div><van-button block type="primary" :loading="saving" @click="commit">确认收货</van-button></footer>
    </section>
  </van-popup>
</template>

<script setup lang="ts">
import { computed, inject, nextTick, onBeforeUnmount, onMounted, ref, type Ref } from 'vue'
import { showConfirmDialog, showLoadingToast, showToast } from 'vant'
import { api, type AuthUser } from '../../api'
import MobilePurchaseNumbers from './MobilePurchaseNumbers.vue'
import LocationDisplay from '../../components/LocationDisplay.vue'
import { locationLabel, locationSearch } from '../../utils/location'

type Mode='detail'|'confirm'|'receipt'
type ReviewLine={itemId:number;goodsCode:string;goodsName:string;locationId:number;quantity:number;actualUnitPrice:number;overReceiptReason:string;note:string}
const emit=defineEmits<{completed:[]}>(),user=inject<Ref<AuthUser|undefined>>('mobileAuthUser'),root='/api/goods-procurement'
const visible=ref(false),mode=ref<Mode>('detail'),purchase=ref<any>(),locations=ref<any[]>([]),opening=ref(false),saving=ref(false),body=ref<HTMLElement>()
const pickerOpen=ref(false),pickerLine=ref<any>(),locationQuery=ref(''),reviewOpen=ref(false),reviewAll=ref(false),reviewLines=ref<ReviewLine[]>([])
const canReceive=computed(()=>user?.value?.permissions.includes('warehouse.goods.receive')===true),canPurchase=computed(()=>user?.value?.permissions.includes('warehouse.goods.purchase')===true)
const receivable=computed<any[]>(()=>purchase.value?.items.filter((line:any)=>remaining(line)>0)||[])
const selected=computed(()=>receivable.value.filter(line=>line.selected))
const allSelected=computed(()=>receivable.value.length>0&&selected.value.length===receivable.value.length)
const allExpanded=computed(()=>receivable.value.length>0&&receivable.value.every(line=>line.expanded))
const selectedTotal=computed(()=>selected.value.reduce((sum,line)=>sum+Number(line.receiveQty||0)*Number(line.actualUnitPrice||0),0))
const reviewTotal=computed(()=>reviewLines.value.reduce((sum,line)=>sum+line.quantity*line.actualUnitPrice,0))
const pickerLocations=computed(()=>pickerLine.value?availableLocations(pickerLine.value).filter(loc=>locationSearch(loc).includes(locationQuery.value.trim().toLowerCase())):[])
const viewportHeight=ref(window.visualViewport?.height||window.innerHeight),viewportTop=ref(window.visualViewport?.offsetTop||0)
const screenStyle=computed(()=>({height:`${viewportHeight.value}px`,marginTop:`${viewportTop.value}px`}))
function updateViewport(){viewportHeight.value=window.visualViewport?.height||window.innerHeight;viewportTop.value=window.visualViewport?.offsetTop||0}
function revealInput(event:FocusEvent){const element=event.target as HTMLElement;setTimeout(()=>{if(element.isConnected)element.scrollIntoView({block:'nearest',behavior:'smooth'})},300)}
const keys=new Map<string,string>()
function requestKey(operation:string,payload:any){const signature=JSON.stringify([operation,payload]);if(!keys.has(signature))keys.set(signature,typeof crypto.randomUUID==='function'?crypto.randomUUID():Array.from(crypto.getRandomValues(new Uint8Array(16)),x=>x.toString(16).padStart(2,'0')).join(''));return {signature,key:keys.get(signature)!}}
function error(e:unknown){return e instanceof Error?e.message:'操作失败'}
function remaining(line:any){return Math.max(0,Math.round((Number(line.confirmedQty)-Number(line.receivedQty))*10000)/10000)}
function money(value:number){return Number.isFinite(value)?value.toFixed(4):'0.0000'}
function validNumber(value:any,positive=false){return value!==''&&value!==null&&value!==undefined&&Number.isFinite(Number(value))&&(positive?Number(value)>0:Number(value)>=0)&&Math.abs(Number(value)*10000-Math.round(Number(value)*10000))<0.00001}
function statusText(status:string){return ({DRAFT:'待确认',CONFIRMED:'待收货',PARTIALLY_RECEIVED:'部分收货',RECEIVED:'已收齐',CANCELLED:'已取消'} as Record<string,string>)[status]||'未知状态'}
function locationName(id?:number){return locationLabel(locations.value.find(loc=>Number(loc.id)===Number(id)))}
function locationEffect(id?:number){const loc=locations.value.find(loc=>Number(loc.id)===Number(id));return !loc?'请选择有效库位':loc.locationType==='PENDING_PUTAWAY'?'增加待上架库存，不建立专属绑定':Number(loc.assignmentCount)>0?'使用已有绑定，收货后增加可用库存':'收货成功后同步货品位点绑定，增加可用库存'}
function availableLocations(line:any){
  const own=locations.value.filter(loc=>loc.locationType!=='PENDING_PUTAWAY'&&Number(loc.assignmentCount)===1&&Number(loc.assignedGoodsId)===Number(line.goodsId))
  return locations.value.filter(loc=>loc.locationType==='PENDING_PUTAWAY'||own.some(x=>x.id===loc.id)||(!own.length&&Number(loc.assignmentCount)===0&&Number(loc.occupiedRows)===0&&Number(loc.openTasks)===0&&!selected.value.some(other=>other.id!==line.id&&other.locationId===loc.id)))
    .sort((a,b)=>Number(own.some(x=>x.id===b.id))-Number(own.some(x=>x.id===a.id)))
}
function validLocation(line:any){return !!line.locationId&&availableLocations(line).some(loc=>loc.id===line.locationId)}
function selectAll(value:boolean){if(!saving.value)receivable.value.forEach(line=>line.selected=value)}
function expandAll(){const value=!allExpanded.value;receivable.value.forEach(line=>line.expanded=value)}
function chooseFor(line:any){if(saving.value)return;pickerLine.value=line;locationQuery.value='';pickerOpen.value=true}
function chooseLocation(loc:any){if(!pickerLine.value||!availableLocations(pickerLine.value).some(x=>x.id===loc.id))return;pickerLine.value.locationId=loc.id;pickerOpen.value=false}
async function open(id:number,nextMode:Mode){
  if(opening.value||saving.value)return
  if(nextMode==='receipt'&&!canReceive.value)return showToast('当前账号没有采购收货权限')
  if(nextMode==='confirm'&&!canPurchase.value)return showToast('当前账号没有采购确认权限')
  opening.value=true;const toast=showLoadingToast({message:'读取采购明细',duration:0,forbidClick:true})
  try{
    const detail=await api<any>(`${root}/purchases/${id}`)
    if(nextMode==='receipt'&&!['CONFIRMED','PARTIALLY_RECEIVED'].includes(detail.status))throw new Error('采购单状态已变化，请刷新列表')
    if(nextMode==='confirm'&&detail.status!=='DRAFT')throw new Error('该采购单已确认，请刷新列表')
    if(nextMode==='receipt')locations.value=(await api<{items:any[]}>(`${root}/receipt-locations?warehouseId=${detail.warehouseId}`)).items
    const first=detail.items.findIndex((line:any)=>remaining(line)>0)
    detail.items.forEach((line:any,index:number)=>Object.assign(line,{inputQty:Number(line.confirmedQty||line.orderedQty),reason:'',selected:remaining(line)>0,expanded:index===first,receiveQty:remaining(line),actualUnitPrice:Number(line.suggestedPrice||0),locationId:undefined,overReceiptReason:'',receiptNote:''}))
    purchase.value=detail;mode.value=nextMode
    if(nextMode==='receipt'){
      if(!receivable.value.length)throw new Error('该采购单没有待收数量')
      receivable.value.forEach(line=>{const options=availableLocations(line),own=options.filter(loc=>loc.locationType!=='PENDING_PUTAWAY'&&Number(loc.assignedGoodsId)===Number(line.goodsId)),pending=options.filter(loc=>loc.locationType==='PENDING_PUTAWAY');if(own.length===1)line.locationId=own[0].id;else if(!own.length&&pending.length===1)line.locationId=pending[0].id})
    }
    updateViewport();visible.value=true;await nextTick();body.value?.scrollTo({top:0});toast.close()
  }catch(e){toast.close();showToast(error(e))}finally{opening.value=false}
}
async function close(){
  if(saving.value)return
  if(mode.value!=='detail'){try{await showConfirmDialog({title:'返回任务列表',message:'本次填写的内容尚未提交，确认返回？'})}catch{return}}
  visible.value=false
}
async function lineError(line:any,message:string){line.expanded=true;showToast(`${line.goodsCode}：${message}`);await nextTick();document.getElementById(`mobile-receipt-${line.id}`)?.scrollIntoView({block:'center',behavior:'smooth'})}
async function prepare(all:boolean){
  if(saving.value||!purchase.value)return
  if(!canReceive.value)return showToast('当前账号没有采购收货权限')
  if(all){selectAll(true);receivable.value.forEach(line=>line.receiveQty=remaining(line))}
  if(!selected.value.length)return showToast('请勾选待收货品')
  for(const line of selected.value){
    if(!validLocation(line))return lineError(line,'请选择符合规则的收货库位')
    if(!validNumber(line.receiveQty,true))return lineError(line,'收货数量必须大于零，最多四位小数')
    if(!validNumber(line.actualUnitPrice))return lineError(line,'实际单价必须大于或等于零，最多四位小数')
    if(Number(line.receiveQty)>remaining(line)&&!line.overReceiptReason.trim())return lineError(line,'超收必须填写原因')
  }
  reviewLines.value=selected.value.map(line=>({itemId:line.id,goodsCode:line.goodsCode,goodsName:line.goodsName,locationId:line.locationId,quantity:Number(line.receiveQty),actualUnitPrice:Number(line.actualUnitPrice),overReceiptReason:line.overReceiptReason||'',note:line.receiptNote||''}))
  reviewAll.value=all;reviewOpen.value=true
}
async function commit(){
  if(saving.value||!purchase.value||!reviewLines.value.length)return
  if(!canReceive.value)return showToast('当前账号没有采购收货权限')
  const orderId=purchase.value.id,payload={warehouseId:purchase.value.warehouseId,receiveAll:reviewAll.value,items:reviewLines.value.map(({goodsCode,goodsName,...line})=>line)},request=requestKey(`receive-${orderId}`,payload)
  saving.value=true
  try{await api(`${root}/purchases/${orderId}/receipts`,{method:'POST',body:JSON.stringify({...payload,idempotencyKey:request.key})});keys.delete(request.signature);reviewOpen.value=false;visible.value=false;showToast('收货成功，库存与库位绑定已同步');emit('completed')}
  catch(e){showToast(error(e))}finally{saving.value=false}
}
async function confirmPurchase(){
  if(saving.value||!purchase.value)return
  if(!canPurchase.value)return showToast('当前账号没有采购确认权限')
  for(const line of purchase.value.items){if(!validNumber(line.inputQty)||Number(line.inputQty)<Number(line.receivedQty))return lineError(line,'请输入有效确认数量');if(Number(line.inputQty)>Number(line.orderedQty)&&!line.reason.trim())return lineError(line,'超量确认时请填写原因')}
  saving.value=true
  try{const payload={items:purchase.value.items.map((line:any)=>({itemId:line.id,confirmedQty:Number(line.inputQty),reason:line.reason||''}))};await showConfirmDialog({title:'确认采购数量',message:`${purchase.value.poNo}\n${purchase.value.items.map((line:any)=>`${line.goodsCode}：${line.inputQty}`).join('\n')}\n确认后只更新采购确认量，不增加库存。`});const request=requestKey(`confirm-${purchase.value.id}`,payload);await api(`${root}/purchases/${purchase.value.id}/confirm`,{method:'POST',body:JSON.stringify({...payload,idempotencyKey:request.key})});keys.delete(request.signature);visible.value=false;showToast('采购数量已确认');emit('completed')}
  catch(e){if(e!=='cancel'&&e!=='close')showToast(error(e))}finally{saving.value=false}
}
onMounted(()=>{window.visualViewport?.addEventListener('resize',updateViewport);window.visualViewport?.addEventListener('scroll',updateViewport);window.addEventListener('resize',updateViewport)})
onBeforeUnmount(()=>{window.visualViewport?.removeEventListener('resize',updateViewport);window.visualViewport?.removeEventListener('scroll',updateViewport);window.removeEventListener('resize',updateViewport)})
defineExpose({open})
</script>

<style scoped>
.purchase-popup{height:100%;width:100%;top:0;bottom:auto;max-height:none;overflow:hidden;background:#f5f7fa}.purchase-screen{display:flex;flex-direction:column;max-width:640px;margin-left:auto;margin-right:auto;box-sizing:border-box;overflow:hidden}.purchase-body{flex:1;min-height:0;overflow-y:auto;padding:12px 12px 20px;overscroll-behavior:contain;scroll-padding:20px}.summary-card,.line-card{background:#fff;border-radius:12px;padding:14px;margin-bottom:12px;overflow-wrap:anywhere}.card-heading{display:flex;justify-content:space-between;align-items:center;gap:10px}.summary-card p{font-size:13px;color:#737d8c;line-height:1.6}.selection-bar{display:flex;align-items:center;gap:12px;position:sticky;top:0;background:#f5f7fa;z-index:1;padding:10px 2px;min-height:48px;box-sizing:border-box;font-size:13px}.selection-bar button{margin-left:auto;color:#1989fa;min-height:44px}.selection-bar span{color:#737d8c}.line-heading{display:flex;align-items:center;gap:12px}.line-heading .van-checkbox{padding:10px 2px}.goods-title{min-width:0;flex:1}.goods-title strong,.goods-title small{display:block}.goods-title strong{font-size:16px;line-height:1.5}.goods-title small{font-size:12px;color:#7b8492;margin-top:4px}.line-heading button{color:#1989fa;min-height:44px;min-width:44px}.quantities{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:6px;font-size:12px;color:#737d8c;margin:14px 0;background:#f7f8fa;padding:10px 6px;border-radius:8px;text-align:center}.quantities b{display:block;margin-top:5px;color:#333;font-size:14px;overflow-wrap:anywhere}.line-card.complete{background:#f9fafb}.line-card .van-field,.line-card .van-cell{padding-left:0;padding-right:0}.line-card :deep(.van-cell__value){overflow-wrap:anywhere}.binding-hint{font-size:12px!important;color:#677b68!important;line-height:1.6;margin:8px 0}.invalid-location{color:#d74b40}.line-amount{text-align:right;font-size:13px;padding-top:10px}.line-amount strong{font-size:16px;margin-left:8px}
.purchase-footer{flex-shrink:0;padding:10px 12px max(12px,env(safe-area-inset-bottom));background:#fff;border-top:1px solid #eee}.receipt-total{display:flex;align-items:center;justify-content:space-between;font-size:14px;padding:0 2px 10px}.receipt-total strong{font-size:19px;color:#1989fa}.receipt-buttons{display:grid;grid-template-columns:1fr 1fr;gap:8px}.purchase-footer .van-button{height:48px;border-radius:9px;font-size:14px;padding:0 8px}.location-popup{max-height:85dvh;display:flex;flex-direction:column;padding-bottom:env(safe-area-inset-bottom)}.picker-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:16px 16px 8px;flex-shrink:0}.picker-heading strong{font-size:17px}.picker-subtitle{margin:4px 16px;font-size:13px;color:#737d8c}.location-options{overflow-y:auto;min-height:0;padding:0 14px 16px}.location-option{display:flex;align-items:center;justify-content:space-between;gap:8px;text-align:left;width:100%;padding:16px 0;border-bottom:1px solid #eee!important}.location-option>span{min-width:0}.location-option strong,.location-option small{display:block;overflow-wrap:anywhere}.location-option small{font-size:12px;color:#737d8c;margin-top:6px;line-height:1.6}.location-option .van-tag{flex-shrink:0}.review-popup{max-height:100dvh;overflow:hidden}.review-screen{display:flex;flex-direction:column;box-sizing:border-box}.review-body{flex:1;min-height:0;overflow-y:auto;padding:0 14px;background:#f5f7fa}.review-body p{font-size:13px;line-height:1.6;overflow-wrap:anywhere}.purchase-screen button:not(.van-button),.location-popup button:not(.van-button){border:0;background:transparent;font:inherit}
@media(max-width:360px){.receipt-buttons{grid-template-columns:1fr}.quantities{gap:3px}}
</style>
