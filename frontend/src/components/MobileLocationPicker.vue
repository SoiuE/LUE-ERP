<template>
  <van-popup :show="show" position="bottom" round class="mobile-location-picker" @update:show="emit('update:show',$event)">
    <div class="picker-heading"><strong>{{ title||'选择库位' }}</strong><van-button size="small" @click="emit('update:show',false)">关闭</van-button></div>
    <van-search v-model="query" placeholder="搜索仓库、区域、货架、层、位或编码" />
    <div class="picker-options"><van-empty v-if="!filtered.length" description="没有匹配的库位"/><button v-for="loc in filtered" :key="loc.locationId??loc.id" class="picker-option" @click="choose(loc)"><span class="option-copy"><LocationDisplay :location="loc"/><small v-if="bindingGoodsId" class="binding-state">{{ Number(loc.assignedGoodsId)===Number(bindingGoodsId)?'本货品已绑定':'空闲库位 · 上架时绑定' }}</small></span><span v-if="loc.availableQty!=null" class="quantity">可用 {{ loc.availableQty }}</span><van-icon v-if="Number(modelValue)===Number(loc.locationId??loc.id)" name="success" color="#1989fa"/></button></div>
  </van-popup>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import LocationDisplay from './LocationDisplay.vue'
import { locationSearch, type LocationInfo } from '../utils/location'
const props=defineProps<{show:boolean;modelValue?:number;options:LocationInfo[];title?:string;bindingGoodsId?:number}>()
const emit=defineEmits<{'update:show':[value:boolean];'update:modelValue':[value:number]}>()
const query=ref(''),filtered=computed(()=>props.options.filter(row=>locationSearch(row).includes(query.value.trim().toLowerCase())))
watch(()=>props.show,show=>{if(show)query.value=''})
function choose(row:LocationInfo){emit('update:modelValue',Number(row.locationId??row.id));emit('update:show',false)}
</script>
<style scoped>
.mobile-location-picker{display:flex;flex-direction:column;max-height:85dvh;padding-bottom:env(safe-area-inset-bottom)}.picker-heading{display:flex;align-items:center;justify-content:space-between;padding:16px 14px 4px;gap:12px}.picker-options{overflow-y:auto;min-height:0;padding:0 14px 16px}.picker-option{display:flex;align-items:center;gap:12px;width:100%;padding:15px 0;min-height:64px;border:0;border-bottom:1px solid #eee;background:#fff;text-align:left;font:inherit}.option-copy{flex:1;min-width:0}.binding-state{display:block;font-size:12px;color:#737d8c;margin-top:5px}.quantity{color:#737d8c;font-size:12px;flex-shrink:0}
</style>
