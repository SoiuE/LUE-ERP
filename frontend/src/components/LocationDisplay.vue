<template><span class="location-display"><span>{{ locationPath(location) }}</span><small v-if="locationMeta(location)">{{ locationMeta(location) }}</small></span></template>
<script setup lang="ts">
import { locationPath, locationMeta, type LocationInfo } from '../utils/location'
defineProps<{location?:LocationInfo|null}>()
</script>
<style scoped>
.location-display{display:inline-flex;flex-direction:column;gap:4px;white-space:normal;overflow-wrap:anywhere;line-height:1.5;text-align:left;max-width:100%}.location-display small{font-size:12px;color:#7c8593;font-weight:normal}
</style>
