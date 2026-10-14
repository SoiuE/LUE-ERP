<template>
  <div>
    <div class="mobile-page-title"><h1>仓库工作台</h1><p>采购、库存与包材作业入口</p></div>
    <van-notice-bar v-if="summary.openExceptions" left-icon="warning-o" :text="`当前有 ${summary.openExceptions} 条待处理异常`" />
    <div class="mobile-metrics">
      <div><small>待确认采购</small><strong>{{ summary.pendingConfirm }}</strong></div>
      <div><small>待入库采购</small><strong>{{ summary.pendingReceipt }}</strong></div>
      <div><small>今日入库</small><strong>{{ summary.inboundToday }}</strong></div>
      <div><small>今日出库</small><strong>{{ summary.outboundToday }}</strong></div>
    </div>
    <h3 class="mobile-section-heading">常用作业</h3>
    <div class="mobile-action-grid">
      <button class="mobile-action" @click="go('/mobile/purchases?stage=confirm')"><strong>采购确认</strong><span>确认到货数量</span></button>
      <button class="mobile-action" @click="go('/mobile/purchases?stage=receipt')"><strong>采购入库</strong><span>支持快递单号检索</span></button>
      <button class="mobile-action" @click="go('/mobile/inventory')"><strong>商品出库</strong><span>JIT、备货、手工出库</span></button>
      <button class="mobile-action" @click="go('/mobile/inventory?mode=count')"><strong>库存盘点</strong><span>填写实盘数和原因</span></button>
      <button class="mobile-action" @click="go('/mobile/packages')"><strong>包材出入库</strong><span>不足时记录异常</span></button>
      <button class="mobile-action" @click="go('/mobile/skus')"><strong>SKU 查询</strong><span>扫码、条码或名称搜索</span></button>
      <button class="mobile-action" @click="go('/mobile/fulfillment')"><strong>发货拣货</strong><span>JIT 订单与平台仓备货</span></button>
    </div>
    <p class="mobile-field-note">手机拍照与相机扫码需要浏览器相机权限。若相机扫码不可用，可使用扫码枪输入条码，或直接搜索 SKU。</p>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../../api'
const router=useRouter(),summary=reactive<any>({pendingConfirm:0,pendingReceipt:0,inboundToday:0,outboundToday:0,openExceptions:0,negativeStocks:0})
async function load(){try{Object.assign(summary,await api<any>('/api/mobile/dashboard'))}catch(e){showToast(e instanceof Error?e.message:'工作台加载失败')}}
function go(path:string){router.push(path)}
onMounted(load)
</script>

<style scoped>
.mobile-metrics{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin:14px 0 22px}.mobile-metrics div{background:#fff;border-radius:12px;padding:15px;box-shadow:0 2px 12px #2026370a}.mobile-metrics small,.mobile-metrics strong{display:block}.mobile-metrics small{color:#8991a0;font-size:12px}.mobile-metrics strong{font-size:25px;color:#242b3d;margin-top:8px}.mobile-section-heading{font-size:15px;margin:0 2px 11px;color:#343b4c}
</style>
