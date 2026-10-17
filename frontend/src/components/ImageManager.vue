<template>
  <section class="image-manager">
    <div class="image-manager-head">
      <div><h2>{{ objectId ? '商品图片' : '图片库' }}</h2><p>图片仅辅助识别；现场操作附件独立保存。</p></div>
      <div v-if="canManage" class="image-manager-actions"><el-button type="primary" @click="openCreate('upload')">上传图片</el-button><el-button @click="openCreate('url')">添加外链</el-button><el-button @click="openAttach">关联已有图片</el-button></div>
    </div>
    <div v-if="!objectId" class="image-filters">
      <el-input v-model="filters.q" clearable placeholder="SKU / 货品编码或名称" @keyup.enter="search" />
      <el-select v-model="filters.objectType" clearable placeholder="对象类型"><el-option label="销售 SKU" value="SKU"/><el-option label="仓库货品" value="WAREHOUSE_GOODS"/></el-select>
      <el-select v-model="filters.purpose" clearable placeholder="图片用途"><el-option v-for="item in purposes" :key="item.value" :label="item.label" :value="item.value"/></el-select>
      <el-input v-model="filters.tag" clearable placeholder="标签" @keyup.enter="search" />
      <el-select v-model="filters.status" clearable placeholder="全部状态"><el-option label="启用" value="ACTIVE"/><el-option label="停用" value="INACTIVE"/></el-select>
      <el-button @click="search">查询</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false"><el-button link type="primary" @click="load">重试</el-button></el-alert>
    <div v-loading="loading" class="image-grid">
      <article v-for="asset in items" :key="asset.id" class="image-card">
        <button class="image-card-picture" type="button" @click="preview(asset)"><ImageAssetPicture :asset="asset" thumbnail :alt="`图片 ${asset.id}`" /></button>
        <div class="image-card-body">
          <div class="image-card-title"><strong>图片 #{{ asset.id }}</strong><el-tag size="small" :type="asset.status==='ACTIVE'?'success':'info'">{{ asset.status==='ACTIVE'?'启用':'停用' }}</el-tag></div>
          <p>{{ sourceLabel(asset.sourceType) }} · {{ asset.originalFilename || '无文件名' }}</p>
          <p>归档：{{ archiveLabel(asset.archiveStatus) }}（尝试 {{ asset.archiveAttemptCount||0 }} 次） · 缩略图：{{ thumbnailLabel(asset.thumbnailStatus) }} · 索引：{{ indexLabel(asset.indexStatus) }}</p>
          <p v-if="asset.archiveLastAttemptAt">最近尝试：{{ formatChinaDateTime(asset.archiveLastAttemptAt) }}<span v-if="asset.archiveCompletedAt"> · 完成：{{ formatChinaDateTime(asset.archiveCompletedAt) }}</span></p>
          <p v-if="asset.archiveError" class="image-error">{{ asset.archiveError }}</p>
          <p v-if="asset.thumbnailError" class="image-error">{{ asset.thumbnailError }}</p>
          <p v-for="link in activeLinks(asset)" :key="link.id">{{ link.objectType==='SKU'?'SKU':'仓库货品' }} {{ link.objectCode || `#${link.objectId}` }} · {{ link.objectName || '' }} · {{ purposeLabel(link.purpose) }}<b v-if="link.isPrimary"> · 主图</b><span v-if="link.tags.length"> · {{ link.tags.join('、') }}</span></p>
          <div class="image-card-buttons"><el-button link type="primary" @click="preview(asset)">预览</el-button><el-button v-if="canManage && asset.status==='ACTIVE' && asset.sourceType==='EXTERNAL_URL'" link type="primary" :loading="['PENDING','PROCESSING'].includes(asset.archiveStatus)" :disabled="['PENDING','PROCESSING'].includes(asset.archiveStatus)" @click="archive(asset)">{{ asset.archiveStatus==='FAILED'?'重试归档':'归档到本地' }}</el-button><el-button v-if="canManage && asset.status==='ACTIVE'" link type="primary" @click="openEdit(asset)">维护</el-button><el-button v-if="canManage && asset.status==='ACTIVE' && !objectId" link type="danger" @click="deactivate(asset)">停用</el-button></div>
        </div>
      </article>
      <el-empty v-if="!loading && !error && !items.length" description="暂无图片" />
    </div>
    <el-pagination v-if="total>pageSize || !objectId && total>0" v-model:current-page="page" v-model:page-size="pageSize" :page-sizes="[12,24,48]" layout="total, sizes, prev, pager, next" :total="total" @current-change="load" @size-change="search" />

    <el-dialog v-model="editorVisible" :title="editing ? '维护图片' : '添加图片'" width="min(680px, calc(100vw - 24px))" destroy-on-close>
      <el-form label-position="top" @submit.prevent>
        <template v-if="!editing || replacing">
          <el-form-item label="图片来源"><el-radio-group v-model="mode"><el-radio-button value="upload">上传文件</el-radio-button><el-radio-button value="url">外部 URL</el-radio-button><el-radio-button value="existing">已有图片 ID</el-radio-button></el-radio-group></el-form-item>
          <el-form-item v-if="mode==='upload'" label="JPEG / PNG / GIF，最多 10 MB"><input type="file" accept="image/jpeg,image/png,image/gif" @change="fileChange" /></el-form-item>
          <el-form-item v-else-if="mode==='url'" label="图片 URL"><el-input v-model="form.url" maxlength="2048" placeholder="https://..." /></el-form-item>
          <el-form-item v-else label="图片资产 ID"><el-input-number v-model="form.assetId" :min="1" :precision="0" /></el-form-item>
        </template>
        <template v-if="editing && !replacing"><p>来源：{{ editing.sourceType }} <span v-if="editing.externalUrl">· {{ editing.externalUrl }}</span></p><el-button v-if="editing.sourceType==='EXTERNAL_URL'" @click="replacing=true;mode='url';form.url=editing.externalUrl||''">替换失效链接或上传图片</el-button></template>
        <el-divider>关联与属性</el-divider>
        <el-form-item v-if="!objectId && (!editing || replacing || !form.linkId)" label="关联对象"><el-select v-model="form.objectType" clearable placeholder="可先仅保存资产"><el-option label="销售 SKU" value="SKU"/><el-option label="仓库货品" value="WAREHOUSE_GOODS"/></el-select><el-input-number v-if="form.objectType" v-model="form.objectId" :min="1" :precision="0" placeholder="对象 ID" /></el-form-item>
        <el-form-item v-if="editing && !replacing && !objectId" label="选择关联"><el-select v-model="form.linkId" clearable placeholder="清空以新增关联"><el-option v-for="link in activeLinks(editing)" :key="link.id" :label="`${link.objectCode || link.objectType+' #'+link.objectId} · ${link.objectName || ''}`" :value="link.id" /></el-select></el-form-item>
        <template v-if="objectId || form.objectType || form.linkId">
          <el-form-item label="图片用途"><el-select v-model="form.purpose"><el-option v-for="item in purposes" :key="item.value" :label="item.label" :value="item.value"/></el-select></el-form-item>
          <el-form-item label="主图"><el-switch v-model="form.isPrimary" /></el-form-item>
          <el-form-item label="显示顺序"><el-input-number v-model="form.sortOrder" :min="0" :max="1000000" :precision="0" /></el-form-item>
          <el-form-item label="标签（逗号分隔）"><el-input v-model="form.tags" maxlength="1500" /></el-form-item>
          <el-form-item label="备注"><el-input v-model="form.note" type="textarea" maxlength="1000" show-word-limit /></el-form-item>
        </template>
      </el-form>
      <template #footer><el-button v-if="editing && form.linkId && !replacing" type="danger" plain @click="unlink">解除关联</el-button><el-button @click="editorVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="viewerVisible" title="图片预览" width="min(900px, calc(100vw - 24px))" destroy-on-close>
      <div v-if="selected" class="image-viewer"><div class="image-viewer-picture"><ImageAssetPicture :asset="selected" :alt="`图片 ${selected.id}`" /></div><div class="image-viewer-meta"><span>#{{ selected.id }} · {{ sourceLabel(selected.sourceType) }}</span><span>{{ selected.externalUrl || selected.sourceUrl || selected.originalFilename }}</span></div></div>
      <template #footer><el-button :disabled="selectedIndex<=0" @click="step(-1)">上一张</el-button><el-button :disabled="selectedIndex>=items.length-1" @click="step(1)">下一张</el-button><el-button @click="viewerVisible=false">关闭</el-button><el-button v-if="canManage && selected?.status==='ACTIVE'" type="primary" @click="viewerVisible=false;openEdit(selected!)">维护图片</el-button></template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import ImageAssetPicture from './ImageAssetPicture.vue'
import { formatChinaDateTime } from '../utils/chinaTime'

type Link = { id:number; objectType:'SKU'|'WAREHOUSE_GOODS'; objectId:number; objectCode?:string; objectName?:string; purpose:string; isPrimary:boolean; sortOrder:number; tags:string[]; note?:string|null; status:string }
type Asset = { id:number; sourceType:string; externalUrl?:string|null; sourceUrl?:string|null; originalFilename?:string|null; status:string; archiveStatus:string; archiveError?:string|null; archiveAttemptCount:number; archiveLastAttemptAt?:string|null; archiveCompletedAt?:string|null; thumbnailStatus:string; thumbnailError?:string|null; indexStatus:string; indexVersion?:string|null; links:Link[] }
const props = defineProps<{ objectType?:'SKU'|'WAREHOUSE_GOODS'; objectId?:number; canManage:boolean }>()
const purposes = [{value:'MAIN_IMAGE',label:'商品主图'},{value:'PRODUCT_PHOTO',label:'商品实物图'},{value:'PACKAGING',label:'包装图'},{value:'WAREHOUSE_PHOTO',label:'仓库拍照图'},{value:'OTHER',label:'其他'}]
const items=ref<Asset[]>([]),total=ref(0),page=ref(1),pageSize=ref(12),loading=ref(false),error=ref('')
const filters=reactive({q:'',objectType:'',purpose:'',tag:'',status:'ACTIVE'})
const editorVisible=ref(false),viewerVisible=ref(false),selected=ref<Asset>(),editing=ref<Asset>(),replacing=ref(false),saving=ref(false),mode=ref<'upload'|'url'|'existing'>('upload'),file=ref<File>()
const form=reactive({url:'',assetId:undefined as number|undefined,objectType:'' as ''|'SKU'|'WAREHOUSE_GOODS',objectId:undefined as number|undefined,linkId:undefined as number|undefined,purpose:'PRODUCT_PHOTO',isPrimary:false,sortOrder:0,tags:'',note:''})
const selectedIndex=computed(()=>items.value.findIndex(item=>item.id===selected.value?.id))
function activeLinks(asset:Asset){return asset.links.filter(link=>link.status==='ACTIVE' && (!props.objectId || link.objectType===props.objectType && link.objectId===props.objectId)).sort((a,b)=>Number(b.isPrimary)-Number(a.isPrimary)||a.sortOrder-b.sortOrder||a.id-b.id)}
function purposeLabel(value:string){return purposes.find(p=>p.value===value)?.label||value}
function sourceLabel(value:string){return ({EXTERNAL_URL:'外部链接',ARCHIVED_URL:'已归档外链',UPLOAD:'上传图片'} as Record<string,string>)[value]||value}
function archiveLabel(value:string){return ({NOT_REQUESTED:'未请求',PENDING:'排队中',PROCESSING:'处理中',SUCCEEDED:'已归档',FAILED:'失败'} as Record<string,string>)[value]||value}
function thumbnailLabel(value:string){return ({NOT_GENERATED:'未生成',READY:'可用',FAILED:'失败（原图可用）'} as Record<string,string>)[value]||value}
function indexLabel(value:string){return ({NOT_INDEXED:'未索引',PENDING:'待索引',INDEXED:'已索引',FAILED:'失败'} as Record<string,string>)[value]||value}
async function load(){
  loading.value=true;error.value=''
  try { const query=new URLSearchParams({page:String(page.value),pageSize:String(pageSize.value)})
    if(props.objectId && props.objectType){query.set('objectType',props.objectType);query.set('objectId',String(props.objectId));query.set('status','ACTIVE')}
    else for(const [key,value] of Object.entries(filters))if(value)query.set(key,value)
    const result=await api<{items:Asset[];total:number}>(`/api/images?${query}`);items.value=result.items;total.value=result.total
  }catch(e){error.value=e instanceof Error?e.message:'图片读取失败';items.value=[];total.value=0}finally{loading.value=false}
}
function search(){page.value=1;void load()}
watch(()=>[props.objectType,props.objectId],search,{immediate:true})
function resetForm(){Object.assign(form,{url:'',assetId:undefined,objectType:props.objectType||'',objectId:props.objectId,linkId:undefined,purpose:'PRODUCT_PHOTO',isPrimary:false,sortOrder:0,tags:'',note:''});file.value=undefined}
function openCreate(next:'upload'|'url'){resetForm();editing.value=undefined;replacing.value=false;mode.value=next;editorVisible.value=true}
function openAttach(){resetForm();editing.value=undefined;replacing.value=false;mode.value='existing';editorVisible.value=true}
function openEdit(asset:Asset){resetForm();editing.value=asset;replacing.value=false;const link=activeLinks(asset)[0];if(link)fillLink(link);editorVisible.value=true}
function fillLink(link:Link){Object.assign(form,{linkId:link.id,objectType:link.objectType,objectId:link.objectId,purpose:link.purpose,isPrimary:link.isPrimary,sortOrder:link.sortOrder,tags:link.tags.join(', '),note:link.note||''})}
watch(()=>form.linkId,id=>{if(id && editing.value){const link=activeLinks(editing.value).find(x=>x.id===id);if(link)fillLink(link)}})
function fileChange(event:Event){file.value=(event.target as HTMLInputElement).files?.[0]}
function parseTags(){const tags=[...new Set(form.tags.split(/[,，]/).map(x=>x.trim()).filter(Boolean))];if(tags.length>30||tags.some(x=>x.length>50))throw new Error('最多 30 个标签，每个最长 50 字');return tags}
function validUrl(value:string){try{const url=new URL(value.trim());return ['http:','https:'].includes(url.protocol)&&!!url.hostname&&!url.username&&!url.password}catch{return false}}
async function assetFromForm(){
  if(mode.value==='existing'){if(!form.assetId)throw new Error('请输入已有图片 ID');return form.assetId}
  if(mode.value==='url'){if(!validUrl(form.url))throw new Error('请输入不含账号密码的 HTTP/HTTPS 图片 URL');return (await api<Asset>('/api/images/external-links',{method:'POST',body:JSON.stringify({url:form.url.trim()})})).id}
  if(!file.value)throw new Error('请选择图片文件');if(file.value.size>10*1024*1024)throw new Error('图片不能超过 10 MB')
  const body=new FormData();body.append('file',file.value);return (await api<Asset>('/api/images/uploads',{method:'POST',body})).id
}
async function save(){
  if(saving.value)return
  saving.value=true
  try{
    const payload={purpose:form.purpose,isPrimary:form.isPrimary,sortOrder:form.sortOrder,tags:parseTags(),note:form.note}
    if(editing.value && !replacing.value && form.linkId){await api(`/api/images/links/${form.linkId}`,{method:'PATCH',body:JSON.stringify(payload)})}
    else if(editing.value && !replacing.value){if(!form.objectType||!form.objectId)throw new Error('请选择关联对象');await api('/api/images/links',{method:'POST',body:JSON.stringify({...payload,assetId:editing.value.id,objectType:form.objectType,objectId:form.objectId})})}
    else {
      if((props.objectId || form.objectType || mode.value==='existing') && (!form.objectType || !form.objectId))throw new Error('请选择关联对象并输入 ID')
      const assetId=await assetFromForm()
      if(form.objectType && form.objectId){await api('/api/images/links',{method:'POST',body:JSON.stringify({...payload,assetId,objectType:form.objectType,objectId:form.objectId})})}
      if(replacing.value && form.linkId)await api(`/api/images/links/${form.linkId}`,{method:'DELETE'})
    }
    ElMessage.success('图片已保存');editorVisible.value=false;await load()
  }catch(e){ElMessage.error(e instanceof Error?e.message:'保存失败')}finally{saving.value=false}
}
async function unlink(){if(!form.linkId)return;try{await ElMessageBox.confirm('解除此图片与对象的关联？','确认解除',{type:'warning'});await api(`/api/images/links/${form.linkId}`,{method:'DELETE'});editorVisible.value=false;ElMessage.success('已解除关联');await load()}catch(e){if(e!=='cancel'&&e!=='close')ElMessage.error(e instanceof Error?e.message:'解除失败')}}
async function deactivate(asset:Asset){try{await ElMessageBox.confirm(`停用图片 #${asset.id} 及其全部活动关联？`,'确认停用',{type:'warning'});await api(`/api/images/${asset.id}/deactivate`,{method:'POST'});ElMessage.success('已停用');await load()}catch(e){if(e!=='cancel'&&e!=='close')ElMessage.error(e instanceof Error?e.message:'停用失败')}}
let archiveTimer:number|undefined
async function archive(asset:Asset){try{await api(`/api/images/${asset.id}/archive`,{method:'POST'});ElMessage.success('已提交归档任务');await load();pollArchive(asset.id,0)}catch(e){ElMessage.error(e instanceof Error?e.message:'归档提交失败')}}
function pollArchive(id:number,count:number){window.clearTimeout(archiveTimer);const asset=items.value.find(x=>x.id===id);if(!asset||!['PENDING','PROCESSING'].includes(asset.archiveStatus)||count>=20)return;archiveTimer=window.setTimeout(async()=>{await load();pollArchive(id,count+1)},1500)}
onBeforeUnmount(()=>window.clearTimeout(archiveTimer))
function preview(asset:Asset){selected.value=asset;viewerVisible.value=true}
function step(delta:number){selected.value=items.value[selectedIndex.value+delta]||selected.value}
</script>

<style scoped>
.image-manager { padding: 4px 0 20px; }
.image-manager-head { display:flex; justify-content:space-between; align-items:flex-start; gap:16px; margin-bottom:16px; }
.image-manager-head h2 { font-size:20px; margin:0 0 5px; }.image-manager-head p { color:#909399; margin:0; }
.image-manager-actions,.image-filters { display:flex; gap:8px; flex-wrap:wrap; }.image-filters { margin-bottom:18px; }.image-filters>* { width:160px; }.image-filters>.el-input:first-child { width:240px; }
.image-grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(205px,1fr)); gap:15px; min-height:130px; margin-bottom:20px; }
.image-card { background:white; border:1px solid #e4e7ed; border-radius:10px; overflow:hidden; min-width:0; }.image-card-picture { width:100%; height:160px; padding:8px; border:0; background:#f5f7fa; cursor:pointer; }.image-card-body { padding:10px 12px; }.image-card-title { display:flex; justify-content:space-between; align-items:center; }.image-card p { font-size:12px; color:#606266; overflow-wrap:anywhere; margin:7px 0; }.image-card p.image-error{color:#c45656}.image-card-buttons { display:flex; flex-wrap:wrap; }
.image-viewer-picture { height:min(65vh,650px); }.image-viewer-meta { display:flex; flex-wrap:wrap; gap:12px; margin-top:12px; color:#606266; overflow-wrap:anywhere; }
.image-manager p { overflow-wrap:anywhere; }
.image-manager :deep(.el-form-item__content) { gap:8px; }.image-manager :deep(.el-form-item__content > .el-select) { min-width:150px; flex:1; }
@media(max-width:700px){.image-manager-head{flex-direction:column}.image-filters>* ,.image-filters>.el-input:first-child{width:calc(50% - 4px)}.image-grid{grid-template-columns:repeat(auto-fill,minmax(150px,1fr))}}
</style>
