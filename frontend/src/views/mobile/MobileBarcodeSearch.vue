<template>
  <div class="barcode-search">
    <van-search :model-value="modelValue" :placeholder="placeholder" show-action @update:model-value="$emit('update:modelValue',$event)" @search="$emit('search',$event)"><template #action><div class="search-actions"><span @click="$emit('search',modelValue)">搜索</span><van-button v-if="canUseLiveCamera" size="mini" type="primary" plain @click.stop="startScan">实时扫码</van-button><label v-else class="photo-scan-trigger">拍照扫码<input ref="photoInput" class="barcode-photo-input" type="file" accept="image/*" capture="environment" @change="decodePhoto"></label></div></template></van-search>
    <van-popup v-model:show="cameraOpen" position="bottom" round :style="{padding:'16px'}" @opened="beginScan" @closed="stopScan">
      <div class="scanner-title">{{ scanResult ? '请确认扫码结果' : '扫描商品条码' }}</div>
      <template v-if="scanResult">
        <div class="scan-result"><span>识别到的条码</span><strong>{{ scanResult }}</strong></div>
        <div class="scan-result-actions"><van-button plain @click="rescan">重新扫码</van-button><van-button type="primary" @click="confirmScan">确认并搜索</van-button></div>
      </template>
      <template v-else>
        <video ref="video" class="mobile-camera-video" autoplay muted playsinline></video>
        <p class="mobile-field-note">将条码放入镜头中央，识别后会暂停并等待你确认。</p>
        <van-button block @click="closeScan">关闭</van-button>
      </template>
    </van-popup>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref } from 'vue'
import { showToast } from 'vant'
import { BrowserMultiFormatReader, type IScannerControls } from '@zxing/browser'
const props=withDefaults(defineProps<{modelValue:string;placeholder?:string}>(),{placeholder:'扫码或输入 SKU、条码、名称'})
const emit=defineEmits<{(e:'update:modelValue',value:string):void;(e:'search',value:string):void}>()
const cameraOpen=ref(false),video=ref<HTMLVideoElement>(),photoInput=ref<HTMLInputElement>(),scanResult=ref('')
const reader=new BrowserMultiFormatReader()
const canUseLiveCamera=window.isSecureContext&&Boolean(navigator.mediaDevices?.getUserMedia)
let scannerControls:IScannerControls|undefined
async function startScan(){
  scanResult.value=''
  cameraOpen.value=true
}
async function decodePhoto(event:Event){
  const input=event.target as HTMLInputElement,file=input.files?.[0]
  if(!file)return
  const url=URL.createObjectURL(file)
  try{const result=await reader.decodeFromImageUrl(url);showScanResult(result.getText())}
  catch{showToast('未识别到条码，请让条码清晰入镜或手工输入')}
  finally{URL.revokeObjectURL(url);input.value=''}
}
async function beginScan(){
  if(!video.value||!cameraOpen.value||scanResult.value)return
  try{
    scannerControls=await reader.decodeFromConstraints({video:{facingMode:{ideal:'environment'}},audio:false},video.value,(result)=>{
      if(!result)return
      const value=result.getText()
      showScanResult(value)
    })
  }catch(error){cameraOpen.value=false;showToast(error instanceof Error?`相机扫码失败：${error.message}`:'相机扫码失败，请检查权限或改用手工搜索')}
}
function showScanResult(value:string){scanResult.value=value;scannerControls?.stop();scannerControls=undefined}
function confirmScan(){const value=scanResult.value;if(!value)return;emit('update:modelValue',value);closeScan();emit('search',value)}
async function rescan(){scanResult.value='';stopScan();if(!canUseLiveCamera){cameraOpen.value=false;setTimeout(()=>photoInput.value?.click(),150);return}await nextTick();beginScan()}
function closeScan(){cameraOpen.value=false;stopScan()}
function stopScan(){scannerControls?.stop();scannerControls=undefined;if(video.value){const stream=video.value.srcObject as MediaStream|null;stream?.getTracks().forEach(track=>track.stop());video.value.srcObject=null}}
onBeforeUnmount(stopScan)
</script>

<style scoped>
.barcode-search{margin:0 -14px 12px}.search-actions{display:flex;align-items:center;gap:9px;padding-left:7px;color:#1989fa}.scanner-title{font-size:17px;font-weight:650;text-align:center;margin:2px 0 14px}
.barcode-photo-input{position:absolute;width:1px;height:1px;overflow:hidden;opacity:0;pointer-events:none}.photo-scan-trigger{position:relative;color:#1989fa;cursor:pointer;white-space:nowrap;font-size:13px}
.scan-result{display:flex;flex-direction:column;align-items:center;gap:12px;padding:20px 12px 24px;background:#f6f8fb;border-radius:12px}.scan-result span{font-size:13px;color:#767e8c}.scan-result strong{font-size:22px;line-height:1.4;word-break:break-all;text-align:center;color:#202637}.scan-result-actions{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:14px}.scan-result-actions .van-button{height:44px}
</style>
