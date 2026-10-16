<template>
  <el-select :model-value="modelValue" filterable :filter-method="filter" class="location-select" popper-class="location-select-dropdown" :placeholder="placeholder||'搜索或选择具体位置'" @update:model-value="emit('update:modelValue',$event)" @visible-change="query=''">
    <el-option v-for="loc in filtered" :key="loc.locationId??loc.id" :value="loc.locationId??loc.id" :label="label(loc)">
      <LocationDisplay :location="loc"/><small v-if="loc.availableQty!=null||loc.primaryPick||loc.isPrimaryPick" class="location-option-note">{{ extra(loc) }}</small>
      <small v-if="receipt" class="location-option-note">{{ receiptHint(loc) }}</small>
      <small v-if="goodsId" class="location-option-note">{{ putawayHint(loc) }}</small>
    </el-option>
  </el-select>
</template>
<script setup lang="ts">
import { computed, ref } from 'vue'
import LocationDisplay from './LocationDisplay.vue'
import { locationLabel, locationSearch, type LocationInfo } from '../utils/location'
const props=defineProps<{modelValue?:number|null;locations:LocationInfo[];placeholder?:string;receipt?:boolean;goodsId?:number}>()
const emit=defineEmits<{'update:modelValue':[value:number|undefined]}>(),query=ref('')
const filtered=computed(()=>props.locations.filter(row=>locationSearch(row).includes(query.value.trim().toLowerCase())))
function filter(value:string){query.value=value}
function extra(row:LocationInfo){return [row.availableQty!=null?`可用 ${row.availableQty}`:'',row.primaryPick||row.isPrimaryPick?'主拣货位':''].filter(Boolean).join(' · ')}
function receiptHint(row:LocationInfo){return row.locationType==='PENDING_PUTAWAY'?'共用待上架位':row.assignedGoodsId!=null?'本货品已绑定 · 收货并上架':'空闲库位 · 收货时绑定'}
function putawayHint(row:LocationInfo){return Number(row.assignedGoodsId)===Number(props.goodsId)?'本货品已绑定':Number(row.assignmentCount)===0?'空闲库位 · 上架时绑定':'已绑定其他货品'}
function label(row:LocationInfo){return [locationLabel(row),extra(row),props.receipt?receiptHint(row):'',props.goodsId?putawayHint(row):''].filter(Boolean).join(' · ')}
</script>
