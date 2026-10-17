<template>
  <section class="master-page image-search-page">
    <div class="management-head"><div><div class="eyebrow">PRODUCT DATA / VISUAL SEARCH</div><h1>以图搜商品</h1><p>上传或拍摄实物图，查找销售 SKU 与仓库货品候选。确认候选不会修改任何业务数据。</p></div><el-button v-if="canManage" @click="loadStatus">刷新索引状态</el-button></div>

    <el-alert v-if="serviceUnavailable" type="warning" :closable="false" title="图片搜索服务当前不可用，可继续使用下方手工搜索；采购、库存和履约不受影响。" />
    <div class="search-layout">
      <div class="search-panel">
        <h2>查询图片</h2>
        <label class="query-drop">
          <img v-if="previewUrl" :src="previewUrl" alt="查询图预览" />
          <span v-else>选择图片或使用手机相机拍摄<br/><small>JPEG / PNG / GIF，最多 10 MB</small></span>
          <input type="file" accept="image/jpeg,image/png,image/gif" capture="environment" @change="selectFile" />
        </label>
        <div class="search-actions"><el-button type="primary" :disabled="!file" :loading="searching" @click="searchImage">搜索相似商品</el-button><el-button v-if="file" @click="clearFile">清除</el-button></div>
        <p class="privacy-note">查询图仅在内存中验证并发送给内部搜索服务，不写入商品图库或现场附件。</p>
      </div>

      <div class="manual-panel">
        <h2>手工搜索回退</h2>
        <el-input v-model="manualQuery" clearable placeholder="输入 SKU/货品编码、名称或条码" @keyup.enter="manualSearch"><template #append><el-button :loading="manualLoading" @click="manualSearch">搜索</el-button></template></el-input>
        <p>该查询直接读取主数据，即使图片模型或索引服务离线也可使用。</p>
      </div>
    </div>

    <el-alert v-if="error" type="error" :closable="false" :title="error" />
    <el-alert v-if="searched && indexCount===0 && !error" type="info" :closable="false" title="图片索引为空。管理员可先执行全量重建，当前仍可使用手工搜索。" />
    <el-alert v-else-if="lowSimilarity && !error" type="warning" :closable="false" title="索引中没有达到相似度阈值的候选，请更换角度清晰的图片或使用手工搜索。" />

    <section v-if="results.length" class="results-section">
      <div class="results-head"><h2>{{ resultMode==='image'?'相似图片候选':'手工搜索结果' }}</h2><span>{{ results.length }} 个对象</span></div>
      <div class="result-grid">
        <article v-for="item in results" :key="`${item.objectType}:${item.objectId}`" class="result-card" :class="{selected:selectedKey===key(item)}">
          <ProductReferenceImage :reference="reference(item)" :label="item.code" size="medium" />
          <div class="result-copy"><div><el-tag size="small">{{ item.objectType==='SKU'?'销售 SKU':'仓库货品' }}</el-tag><strong>{{ item.code }}</strong></div><h3>{{ item.name }}</h3><p>{{ item.specification||'无规格' }}</p><p>{{ item.inventoryText }}</p><p v-if="item.score!==undefined" class="score">匹配度 {{ percentage(item.score) }}</p></div>
          <div class="result-actions"><el-button link type="primary" @click="open(item)">查看档案</el-button><el-button :type="selectedKey===key(item)?'success':'primary'" @click="confirm(item)">{{ selectedKey===key(item)?'已确认':'确认候选' }}</el-button></div>
        </article>
      </div>
      <el-alert v-if="selected" type="success" :closable="false" :title="`已确认本次候选：${selected.code} · ${selected.name}。未修改店铺映射、组成清单、库存或履约记录。`" />
    </section>

    <section v-if="canManage" class="index-panel">
      <div class="results-head"><h2>索引运行状态</h2><span>模型 {{ status.service?.modelVersion||'未就绪' }}</span></div>
      <div class="index-counts"><span v-for="(count,name) in status.counts" :key="name"><b>{{ statusName(String(name)) }}</b>{{ count }}</span></div>
      <div class="index-actions"><el-button type="warning" :loading="rebuilding" @click="rebuild">全量重建索引</el-button><el-button :loading="backingUp" @click="backup">生成索引备份</el-button></div>
      <el-table v-if="status.failures?.length" :data="status.failures" size="small"><el-table-column prop="id" label="图片 ID" width="100"/><el-table-column prop="indexError" label="失败原因"/><el-table-column prop="indexAttemptCount" label="尝试" width="80"/><el-table-column label="操作" width="90"><template #default="{row}"><el-button link type="primary" @click="retry(row.id)">重试</el-button></template></el-table-column></el-table>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { api, type AuthUser } from '../api'
import ProductReferenceImage from '../components/ProductReferenceImage.vue'
import type { ProductReference } from '../utils/productReferences'
import { useProductReferences } from '../utils/productReferences'

type Candidate={objectType:'SKU'|'WAREHOUSE_GOODS';objectId:number;code:string;name:string;specification?:string;inventoryText:string;assetId?:number;sourceType?:string;externalUrl?:string;score?:number}
const props=defineProps<{user?:AuthUser}>(),router=useRouter()
const canManage=computed(()=>props.user?.permissions.includes('button.image-search.manage')??false)
const file=ref<File>(),previewUrl=ref(''),searching=ref(false),searched=ref(false),error=ref(''),results=ref<Candidate[]>([]),resultMode=ref<'image'|'manual'>('image'),indexCount=ref(0),lowSimilarity=ref(false)
const manualQuery=ref(''),manualLoading=ref(false),selected=ref<Candidate>(),selectedKey=computed(()=>selected.value?key(selected.value):'')
const status=reactive<any>({counts:{},failures:[],service:{}}),rebuilding=ref(false),backingUp=ref(false)
const skuImages=useProductReferences('SKU'),goodsImages=useProductReferences('WAREHOUSE_GOODS')
const serviceUnavailable=computed(()=>status.service?.ready===false||error.value.includes('图片搜索服务'))
function key(item:Candidate){return `${item.objectType}:${item.objectId}`}
function percentage(score:number){return `${Math.max(0,Math.min(100,score*100)).toFixed(1)}%`}
function statusName(value:string){return ({NOT_INDEXED:'未索引',PENDING:'待索引',PROCESSING:'处理中',INDEXED:'已索引',FAILED:'失败'} as Record<string,string>)[value]||value}
function reference(item:Candidate):ProductReference|undefined{if(item.assetId&&item.sourceType)return{id:item.assetId,sourceType:item.sourceType,externalUrl:item.externalUrl||''};return item.objectType==='SKU'?skuImages.get(item.objectId):goodsImages.get(item.objectId)}
function selectFile(event:Event){const next=(event.target as HTMLInputElement).files?.[0];if(!next)return;if(!['image/jpeg','image/png','image/gif'].includes(next.type)||next.size>10*1024*1024){ElMessage.error('请选择不超过 10 MB 的 JPEG、PNG 或 GIF 图片');return}clearFile();file.value=next;previewUrl.value=URL.createObjectURL(next)}
function clearFile(){if(previewUrl.value)URL.revokeObjectURL(previewUrl.value);previewUrl.value='';file.value=undefined}
async function searchImage(){if(!file.value)return;searching.value=true;error.value='';selected.value=undefined;try{const body=new FormData();body.append('file',file.value);const data=await api<{items:Candidate[];indexCount:number;lowSimilarity:boolean}>('/api/image-search/search',{method:'POST',body});results.value=data.items;indexCount.value=data.indexCount;lowSimilarity.value=data.lowSimilarity;searched.value=true;resultMode.value='image'}catch(e){results.value=[];searched.value=true;error.value=e instanceof Error?e.message:'图片搜索失败'}finally{searching.value=false;void loadStatus()}}
async function manualSearch(){const q=manualQuery.value.trim();if(!q)return;manualLoading.value=true;error.value='';selected.value=undefined;try{results.value=(await api<{items:Candidate[]}>(`/api/image-search/manual?q=${encodeURIComponent(q)}`)).items;resultMode.value='manual';void skuImages.load(results.value.filter(x=>x.objectType==='SKU').map(x=>x.objectId));void goodsImages.load(results.value.filter(x=>x.objectType==='WAREHOUSE_GOODS').map(x=>x.objectId))}catch(e){error.value=e instanceof Error?e.message:'手工搜索失败'}finally{manualLoading.value=false}}
function open(item:Candidate){router.push(item.objectType==='SKU'?`/skus/${item.objectId}/details`:{path:'/warehouse-goods',query:{q:item.code}})}
function confirm(item:Candidate){selected.value=item;ElMessage.success('已确认本次识别候选，未写入业务数据')}
async function loadStatus(){if(!canManage.value)return;try{Object.assign(status,await api<any>('/api/image-search/status'))}catch(e){status.service={ready:false,error:e instanceof Error?e.message:'状态读取失败'}}}
async function retry(id:number){try{await api(`/api/image-search/index/${id}/retry`,{method:'POST'});ElMessage.success('已重新排队');await loadStatus()}catch(e){ElMessage.error(e instanceof Error?e.message:'重试失败')}}
async function rebuild(){try{await ElMessageBox.confirm('将清空搜索服务索引，并从当前活动商品图片重新排队。继续？','全量重建',{type:'warning'});rebuilding.value=true;const data=await api<{queued:number}>('/api/image-search/index/rebuild',{method:'POST'});ElMessage.success(`已排队 ${data.queued} 张图片`);await loadStatus()}catch(e){if(e!=='cancel'&&e!=='close')ElMessage.error(e instanceof Error?e.message:'重建失败')}finally{rebuilding.value=false}}
async function backup(){backingUp.value=true;try{const data=await api<{filename:string}>('/api/image-search/index/backup',{method:'POST'});ElMessage.success(`已生成备份 ${data.filename}`)}catch(e){ElMessage.error(e instanceof Error?e.message:'备份失败')}finally{backingUp.value=false}}
onMounted(loadStatus);onBeforeUnmount(clearFile)
</script>

<style scoped>
.image-search-page{display:grid;gap:18px}.search-layout{display:grid;grid-template-columns:minmax(280px,1fr) minmax(280px,1fr);gap:16px}.search-panel,.manual-panel,.index-panel{background:#fff;border:1px solid #e4e7ed;border-radius:12px;padding:18px}.search-panel h2,.manual-panel h2,.index-panel h2,.results-section h2{margin:0 0 14px;font-size:18px}.query-drop{height:260px;border:1px dashed #a8abb2;border-radius:12px;background:#f7f8fa;display:grid;place-items:center;text-align:center;color:#606266;cursor:pointer;overflow:hidden}.query-drop img{width:100%;height:100%;object-fit:contain}.query-drop input{display:none}.query-drop small,.privacy-note,.manual-panel p{color:#909399}.search-actions{display:flex;gap:8px;margin-top:12px}.privacy-note{font-size:12px}.results-head{display:flex;justify-content:space-between;align-items:center}.result-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:12px;margin-bottom:16px}.result-card{display:grid;grid-template-columns:92px 1fr;gap:12px;padding:14px;background:#fff;border:1px solid #e4e7ed;border-radius:12px}.result-card.selected{border-color:#67c23a;box-shadow:0 0 0 1px #67c23a}.result-copy{min-width:0}.result-copy>div{display:flex;gap:8px;align-items:center}.result-copy h3{font-size:15px;margin:8px 0}.result-copy p{font-size:12px;color:#606266;margin:5px 0;overflow-wrap:anywhere}.result-copy .score{font-size:15px;color:#409eff;font-weight:700}.result-actions{grid-column:1/-1;display:flex;justify-content:flex-end;gap:8px}.index-counts{display:flex;gap:10px;flex-wrap:wrap}.index-counts span{display:flex;flex-direction:column;min-width:105px;background:#f5f7fa;padding:10px;border-radius:8px}.index-counts b{font-size:12px;color:#606266}.index-actions{display:flex;gap:8px;margin:14px 0}@media(max-width:720px){.search-layout{grid-template-columns:1fr}.query-drop{height:210px}.result-grid{grid-template-columns:1fr}}
</style>
