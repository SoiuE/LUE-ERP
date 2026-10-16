<template>
  <div v-for="row in numbers" :key="row.label" class="purchase-number">
    <span>{{ row.label }}</span><button v-if="row.value" @click="copy(row.value)">{{ row.value }} <small>复制</small></button><span v-else>—</span>
  </div>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import { showToast } from 'vant'
const props=defineProps<{trackingNo?:string;orderNo?:string}>()
const numbers=computed(()=>[{label:'快递单号',value:props.trackingNo},{label:'供应商订单号',value:props.orderNo}])
async function copy(value:string){
  try{
    if(navigator.clipboard&&window.isSecureContext)await navigator.clipboard.writeText(value)
    else{
      const input=document.createElement('textarea');input.value=value;input.style.position='fixed';input.style.opacity='0';document.body.appendChild(input)
      try{input.select();if(!document.execCommand('copy'))throw new Error('复制失败')}finally{input.remove()}
    }
    showToast('已复制')
  }catch{showToast('复制失败，请长按号码复制')}
}
</script>
<style scoped>
.purchase-number{display:grid;grid-template-columns:92px minmax(0,1fr);gap:8px;font-size:13px;line-height:1.6;margin-top:8px}.purchase-number>span:first-child{color:#747e8d}.purchase-number button{padding:0;text-align:left;word-break:break-all;color:#343b4c;user-select:text;border:0;background:transparent;font:inherit}.purchase-number small{color:#1989fa;white-space:nowrap}
</style>
