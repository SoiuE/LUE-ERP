<template>
  <div>
    <div class="mobile-page-title"><h1>SKU 查询</h1><p>扫描条码，或按内部 SKU、商品名称搜索</p></div>
    <MobileBarcodeSearch v-model="q" @search="load"/>
    <van-empty v-if="!items.length&&!loading" description="输入 SKU、商品名称或扫描条码"/>
    <van-loading v-if="loading" vertical>查询中</van-loading>
    <div v-for="sku in items" :key="sku.id" class="sku-card">
      <img v-if="sku.imageUrl" :src="sku.imageUrl" class="sku-photo" alt="商品" @error="($event.target as HTMLImageElement).style.display='none'"/>
      <div class="sku-content"><strong>{{ sku.internalSku }}</strong><span>{{ sku.productName }}</span><small>条码：{{ sku.barcode||'—' }}</small><div class="stock-pill">可用库存 {{ sku.availableQty }}</div></div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { showToast } from 'vant'
import { api } from '../../api'
import MobileBarcodeSearch from './MobileBarcodeSearch.vue'
const q=ref(''),items=ref<any[]>([]),loading=ref(false)
async function load(value=q.value){q.value=value;if(!value.trim()){items.value=[];return}loading.value=true;try{items.value=(await api<any>(`/api/mobile/skus?q=${encodeURIComponent(value)}`)).items}catch(e){showToast(e instanceof Error?e.message:'SKU 查询失败')}finally{loading.value=false}}
</script>

<style scoped>
.sku-card{display:flex;gap:12px;align-items:center;background:#fff;border-radius:12px;padding:12px;margin-bottom:10px}.sku-photo{width:64px;height:64px;object-fit:cover;border-radius:8px;background:#f4f5f7}.sku-content{min-width:0;display:flex;flex-direction:column;gap:4px}.sku-content strong{font-size:14px}.sku-content span,.sku-content small{font-size:12px;color:#8f96a3}.stock-pill{font-size:12px;color:#168b63;margin-top:3px}
</style>
