<template>
  <div ref="frame" class="asset-picture">
    <img v-if="visible && source && !failed" :src="source" :alt="alt" :loading="thumbnail ? 'lazy' : 'eager'" decoding="async" referrerpolicy="no-referrer" @error="failed = true" />
    <div v-else class="asset-picture-placeholder">{{ failed ? '图片加载失败' : '图片' }}</div>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { getToken } from '../api'

const props = defineProps<{ asset: { id:number; sourceType:string; externalUrl?:string|null; sourceUrl?:string|null }; thumbnail?:boolean; alt?:string }>()
const frame = ref<HTMLElement>()
const visible = ref(false)
const source = ref('')
const failed = ref(false)
let observer: IntersectionObserver | undefined
let objectUrl: string | undefined
let controller: AbortController | undefined

function reset() {
  controller?.abort()
  if (objectUrl) URL.revokeObjectURL(objectUrl)
  objectUrl = undefined
  source.value = ''
  failed.value = false
  if (visible.value) void load()
}
async function load() {
  if (props.asset.sourceType === 'EXTERNAL_URL') {
    source.value = props.asset.externalUrl || props.asset.sourceUrl || ''
    if (!source.value) failed.value = true
    return
  }
  const current = new AbortController()
  controller = current
  try {
    const path = `/api/images/${props.asset.id}/${props.thumbnail ? 'thumbnail' : 'content'}`
    const response = await fetch(path, { headers: { Authorization: `Bearer ${getToken() || ''}` }, signal: current.signal })
    if (!response.ok) throw new Error('图片读取失败')
    const blob = await response.blob()
    if (current.signal.aborted) return
    objectUrl = URL.createObjectURL(blob)
    source.value = objectUrl
  } catch {
    if (!current.signal.aborted) failed.value = true
  }
}
onMounted(() => {
  if (!props.thumbnail || !frame.value || !('IntersectionObserver' in window)) { visible.value = true; void load(); return }
  observer = new IntersectionObserver(entries => {
    if (entries.some(entry => entry.isIntersecting)) { visible.value = true; observer?.disconnect(); void load() }
  }, { rootMargin: '180px' })
  observer.observe(frame.value)
})
watch(() => [props.asset.id, props.asset.externalUrl, props.thumbnail], reset)
onBeforeUnmount(() => { observer?.disconnect(); controller?.abort(); if (objectUrl) URL.revokeObjectURL(objectUrl) })
</script>

<style scoped>
.asset-picture { width: 100%; height: 100%; min-height: 110px; background: #f3f5f7; overflow: hidden; border-radius: 8px; }
.asset-picture img { width: 100%; height: 100%; object-fit: contain; display: block; }
.asset-picture-placeholder { width: 100%; height: 100%; min-height: 110px; display: grid; place-items: center; color: #909399; font-size: 13px; }
</style>
