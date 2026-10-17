<template><section class="master-page"><div class="management-head"><div><div class="eyebrow">PRODUCT DATA / IMAGES</div><h1>图片库</h1><p>集中维护销售 SKU 和仓库货品的参考图片。</p></div></div><ImageManager :can-manage="canManage" /></section></template>
<script setup lang="ts">
import { computed } from 'vue'
import ImageManager from '../components/ImageManager.vue'
import type { AuthUser } from '../api'
const props=defineProps<{user?:AuthUser}>()
const canManage=computed(()=>props.user?.permissions.includes('button.images.manage')??false)
</script>
