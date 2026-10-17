<template>
  <button class="reference-image" :class="size" type="button" :aria-label="reference ? `查看${label}标准参考图` : `${label}暂无标准参考图`" @click.stop="reference && (open=true)">
    <ImageAssetPicture v-if="reference" :asset="reference" thumbnail :alt="`${label}标准参考图`" />
    <span v-else class="reference-empty">无图</span>
  </button>
  <Teleport to="body">
    <div v-if="open && reference" class="reference-overlay" role="dialog" aria-modal="true" :aria-label="`${label}标准参考图`" @click.self="open=false">
      <div class="reference-viewer">
        <button class="reference-close" type="button" aria-label="关闭图片预览" @click="open=false">×</button>
        <div class="reference-large"><ImageAssetPicture :asset="reference" :alt="`${label}标准参考图`" /></div>
        <p>{{ label }} · 标准参考图</p>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import ImageAssetPicture from './ImageAssetPicture.vue'
import type { ProductReference } from '../utils/productReferences'
withDefaults(defineProps<{ reference?:ProductReference; label?:string; size?:'small'|'medium' }>(),{label:'商品',size:'small'})
const open=ref(false)
function escape(event:KeyboardEvent){if(event.key==='Escape')open.value=false}
onMounted(()=>window.addEventListener('keydown',escape))
onBeforeUnmount(()=>window.removeEventListener('keydown',escape))
</script>

<style scoped>
.reference-image{width:56px;height:56px;flex:0 0 56px;padding:0;border:1px solid #dcdfe6;border-radius:8px;background:#f5f7fa;overflow:hidden;cursor:pointer;color:#909399}.reference-image.medium{width:92px;height:92px;flex-basis:92px}.reference-empty{display:grid;place-items:center;width:100%;height:100%;font-size:12px}.reference-image :deep(.asset-picture),.reference-image :deep(.asset-picture-placeholder){min-height:0;height:100%}.reference-overlay{position:fixed;inset:0;z-index:3000;background:#000c;display:grid;place-items:center;padding:16px}.reference-viewer{position:relative;width:min(900px,100%);background:#fff;border-radius:12px;padding:16px}.reference-close{position:absolute;right:10px;top:8px;border:0;background:#fff;font-size:30px;line-height:1;cursor:pointer;z-index:1}.reference-large{height:min(72vh,680px);margin-top:26px}.reference-viewer p{margin:12px 0 0;color:#606266;overflow-wrap:anywhere}
@media(max-width:600px){.reference-image.medium{width:76px;height:76px;flex-basis:76px}.reference-large{height:60vh}}
</style>
