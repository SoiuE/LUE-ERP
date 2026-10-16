<template>
  <div>
    <div class="mobile-page-title"><h1>仓库任务工作台</h1><p>按收货、上架、拣货、交接和盘点进入作业</p></div>
    <van-notice-bar v-if="summary.openExceptions" left-icon="warning-o" :text="`当前有 ${summary.openExceptions} 条待处理异常`" />
    <div class="mobile-task-list">
      <button class="mobile-task-card" :class="{disabled:!canReceive}" @click="go('/mobile/goods-tasks?task=receipt')"><span class="task-order">01</span><span class="task-copy"><strong>待收货</strong><small>{{ canReceive?'勾选货品分批收货，或按采购单全部收货':'当前账号没有采购收货权限' }}</small></span><b v-if="canReceive" class="task-count">{{ summary.pendingReceipt }}</b><span v-else class="task-lock">受限</span></button>
      <button class="mobile-task-card" :class="{disabled:!canPutaway}" @click="go('/mobile/goods-tasks?task=putaway')"><span class="task-order">02</span><span class="task-copy"><strong>待上架</strong><small>{{ canPutaway?'从待上架位分配到正式库位':'当前账号没有上架权限' }}</small></span><b class="task-count">{{ summary.pendingPutaway }}</b></button>
      <button class="mobile-task-card" :class="{disabled:!canFulfill}" @click="go('/mobile/fulfillment')"><span class="task-order">03</span><span class="task-copy"><strong>待拣货</strong><small>{{ canFulfill?'按货品任务和来源库位逐项拣货':'当前账号没有履约操作权限' }}</small></span><b v-if="canFulfill" class="task-count">{{ summary.pendingPicking }}</b><span v-else class="task-lock">受限</span></button>
      <button class="mobile-task-card" :class="{disabled:!canFulfill}" @click="go('/mobile/fulfillment')"><span class="task-order">04</span><span class="task-copy"><strong>待交接</strong><small>{{ canFulfill?'核对实发数量、来源位与包材后确认交接':'当前账号没有履约操作权限' }}</small></span><b v-if="canFulfill" class="task-count">{{ summary.pendingHandoff }}</b><span v-else class="task-lock">受限</span></button>
    </div>
    <div class="mobile-metrics">
      <div><small>今日入库</small><strong>{{ summary.inboundToday }}</strong></div>
      <div><small>今日出库</small><strong>{{ summary.outboundToday }}</strong></div>
    </div>
    <h3 class="mobile-section-heading">其他作业</h3>
    <div class="mobile-action-grid">
      <button v-if="canPurchase" class="mobile-action" @click="go('/mobile/goods-tasks?task=confirm')"><strong>采购待确认</strong><span>核对采购明细与确认数量</span></button>
      <button v-if="canMove" class="mobile-action" @click="go('/mobile/goods-tasks?task=move')"><strong>同仓移库</strong><span>按来源位和目标位记账</span></button>
      <button class="mobile-action" @click="go('/mobile/packages')"><strong>包材出入库</strong><span>提交前确认数量与流水</span></button>
      <button v-if="canFulfill" class="mobile-action" @click="go('/mobile/fulfillment')"><strong>发货单 / 预约</strong><span>JIT 与平台仓备货</span></button>
    </div>
    <p class="mobile-field-note">相机扫码需要安全上下文和相机权限。不可用时可手工输入、模糊搜索或使用扫码枪。照片会关联到现有支持附件的业务提交。</p>
  </div>
</template>

<script setup lang="ts">
import { computed, inject, watch, reactive, type Ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api, type AuthUser } from '../../api'
const router=useRouter(),authUser=inject<Ref<AuthUser|undefined>>('mobileAuthUser')
const summary=reactive<any>({pendingReceipt:0,pendingPutaway:0,pendingPicking:0,pendingHandoff:0,inboundToday:0,outboundToday:0,openExceptions:0})
const permissions=computed(()=>authUser?.value?.permissions||[])
const canReceive=computed(()=>permissions.value.includes('warehouse.goods.receive'))
const canPurchase=computed(()=>permissions.value.includes('warehouse.goods.purchase'))
const canMove=computed(()=>permissions.value.includes('warehouse.goods.move'))
const canPutaway=computed(()=>permissions.value.includes('warehouse.goods.putaway'))
const canFulfill=computed(()=>permissions.value.includes('menu.fulfillment')&&permissions.value.includes('button.fulfillment.operate'))
async function load(){try{
  const [dashboard,procurement,stock]=await Promise.all([api<any>('/api/mobile/dashboard'),api<any>('/api/goods-procurement/purchases'),api<any>('/api/goods-procurement/stock')])
  Object.assign(summary,dashboard)
  summary.pendingReceipt=procurement.items.filter((x:any)=>['CONFIRMED','PARTIALLY_RECEIVED'].includes(x.status)).length
  summary.pendingPutaway=stock.items.filter((x:any)=>Number(x.pendingPutawayQty)>0).length
  if(permissions.value.includes('menu.fulfillment')){
    const docs=await api<any>('/api/fulfillment/documents?page=1&pageSize=100')
    const active=docs.items.filter((x:any)=>!['SHIPPED','CANCELLED','NEEDS_REVIEW','SKU_UNMATCHED'].includes(x.localStatus))
    summary.pendingPicking=active.filter((x:any)=>['READY_TO_PICK','PICKING','SHORTAGE'].includes(x.localStatus)).length
    const detailRows=await Promise.all(active.filter((x:any)=>x.localStatus==='PICKING').map((x:any)=>api<any>(`/api/fulfillment/documents/${x.id}`)))
    const dispatches=detailRows.flatMap((x:any)=>x.dispatches||[])
    summary.pendingHandoff=dispatches.filter((x:any)=>x.status==='PICKED').length
  }
}catch(e){showToast(e instanceof Error?e.message:'工作台任务读取失败')}}
function go(path:string){if(path.includes('task=receipt')&&!canReceive.value)return showToast('当前角色没有采购收货权限');if(path.includes('task=confirm')&&!canPurchase.value)return showToast('当前角色没有采购确认权限');if(path.includes('task=move')&&!canMove.value)return showToast('当前角色没有移库权限');if(path.includes('task=putaway')&&!canPutaway.value)return showToast('当前角色没有上架权限');if(path.includes('/fulfillment')&&!canFulfill.value)return showToast('当前角色没有履约操作权限');router.push(path)}
watch(()=>authUser?.value,user=>{if(user)void load()},{immediate:true})
</script>

<style scoped>
.mobile-task-list{display:grid;gap:9px;margin:14px 0 18px}.mobile-task-card{display:flex;align-items:center;gap:12px;width:100%;min-height:74px;padding:12px;border:0;border-radius:12px;text-align:left;background:#fff;box-shadow:0 2px 12px #2026370a;color:#293044}.mobile-task-card.disabled{opacity:.64}.task-order{width:30px;height:30px;flex:0 0 30px;border-radius:9px;display:grid;place-items:center;background:#eff0ff;color:#6459d9;font-size:12px;font-weight:700}.task-copy{display:flex;flex:1;min-width:0;flex-direction:column;gap:5px}.task-copy strong{font-size:15px}.task-copy small{font-size:11px;line-height:1.4;color:#8b93a1}.task-count{font-size:22px;color:#262d40}.task-action,.task-lock{font-size:12px;color:#6258d8}.task-lock{color:#9a6b26}.mobile-metrics{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin:14px 0 22px}.mobile-metrics div{background:#fff;border-radius:12px;padding:13px;box-shadow:0 2px 12px #2026370a}.mobile-metrics small,.mobile-metrics strong{display:block}.mobile-metrics small{color:#8991a0;font-size:12px}.mobile-metrics strong{font-size:22px;color:#242b3d;margin-top:6px}.mobile-section-heading{font-size:15px;margin:0 2px 11px;color:#343b4c}
</style>
