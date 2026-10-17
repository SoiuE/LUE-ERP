<template>
  <section class="master-page" v-loading="loading">
    <div class="management-head">
      <div><div class="eyebrow">PRODUCT DATA / SKU DETAILS</div><h1>{{ sku?.internalSku || '销售 SKU 详情' }}</h1><p>{{ sku?.productName }}</p></div>
      <el-button @click="router.push('/skus')">返回 SKU 中心</el-button>
    </div>
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" />
    <el-tabs v-if="sku" v-model="tab">
      <el-tab-pane label="销售资料" name="sales">
        <div class="sku-reference"><ProductReferenceImage :reference="skuImages.get(sku.id)" :label="sku.internalSku" size="medium" /><span>{{ sku.internalSku }} · {{ sku.productName }} · {{ sku.specification || '无规格' }}</span></div>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="内部销售 SKU">{{ sku.internalSku }}</el-descriptions-item>
          <el-descriptions-item label="名称">{{ sku.productName }}</el-descriptions-item>
          <el-descriptions-item label="SPU">{{ sku.spu || '—' }}</el-descriptions-item>
          <el-descriptions-item label="规格">{{ sku.specification || '—' }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ sku.status==='ACTIVE'?'启用':'停用' }}</el-descriptions-item>
          <el-descriptions-item label="备注">{{ sku.note || '—' }}</el-descriptions-item>
        </el-descriptions>
      </el-tab-pane>
      <el-tab-pane v-if="has('menu.images')" label="商品图片" name="images">
        <ImageManager object-type="SKU" :object-id="sku.id" :can-manage="has('button.images.manage')" />
      </el-tab-pane>
      <el-tab-pane v-if="canViewMappings" label="店铺映射" name="mappings">
        <div class="subhead"><p>平台 SKU 归集到当前内部销售 SKU。</p><el-button v-if="canMap" type="primary" :disabled="sku.status!=='ACTIVE'" @click="openMapping()">新增映射</el-button></div>
        <div class="table-card">
          <el-table :data="sku.mappings || []" empty-text="该 SKU 暂无店铺映射">
            <el-table-column prop="shopName" label="店铺"/><el-table-column prop="platformSku" label="平台 SKU"/>
            <el-table-column prop="platformProductName" label="平台商品名称"/><el-table-column prop="defaultOrderMode" label="订单模式"/>
            <el-table-column label="状态"><template #default="s">{{ s.row.status==='ACTIVE'?'启用':'停用' }}</template></el-table-column>
            <el-table-column v-if="canMap" label="操作" width="150"><template #default="s"><el-button link type="primary" @click="openMapping(s.row)">编辑</el-button><el-button v-if="s.row.status==='ACTIVE'" link type="warning" @click="deactivateMapping(s.row)">停用</el-button></template></el-table-column>
          </el-table>
        </div>
      </el-tab-pane>
      <el-tab-pane v-if="canViewCompositions" label="组成清单版本" name="compositions">
        <div class="subhead"><p>版本内容只追加保存；修改组成需创建新版本。</p><el-button v-if="canCompose" type="primary" @click="openVersion()">新增版本</el-button></div>
        <div class="preview-controls">
          <label>销售数量</label><el-input-number v-model="previewQty" :min="0.0001" :precision="4"/>
          <el-date-picker v-model="previewDate" type="date" value-format="YYYY-MM-DD" placeholder="生效日期（默认今天）"/>
          <el-button :loading="previewLoading" @click="preview()">按日期展开预览</el-button>
        </div>
        <el-alert v-if="previewResult" type="info" :closable="false" :title="`版本 ${previewResult.versionNo} · 销售数量 ${previewResult.salesQuantity}`">
          <div v-for="line in previewResult.items" :key="line.id">
            {{ line.goodsCode }} · {{ line.goodsName }}：{{ line.requiredQuantity }} {{ line.unit }}
            <span v-if="line.alternatives.length">（可选替代：{{ line.alternatives.map(a=>`${a.goodsCode} × ${a.requiredQuantity} ${a.unit}`).join('、') }}；未计入主要货品需求）</span>
          </div>
        </el-alert>
        <el-empty v-if="!versions.length" description="暂无组成清单，请先维护仓库货品后新增版本"/>
        <div v-for="version in versions" :key="version.id" class="table-card version-card">
          <div class="version-head"><div><strong>版本 {{ version.versionNo }}</strong><el-tag :type="version.status==='ACTIVE'?'success':'info'">{{ version.status==='ACTIVE'?'启用':'停用' }}</el-tag><span>{{ version.effectiveFrom || '不限起始日期' }} 至 {{ version.effectiveTo || '不限结束日期' }}</span></div><div><el-button link type="primary" @click="preview(version.id)">展开此版本</el-button><el-button v-if="canCompose && version.status==='ACTIVE'" link type="warning" @click="deactivateVersion(version)">停用版本</el-button><el-button v-if="canCompose" link type="primary" @click="openVersion(version)">复制为新版本</el-button></div></div>
          <p>修改原因：{{ version.changeReason }}</p>
          <p class="muted">创建：{{ formatChinaDateTime(version.createdAt) }} · 操作人 ID {{ version.createdBy }}</p>
          <p v-if="version.deactivationReason" class="muted">停用：{{ formatChinaDateTime(version.deactivatedAt) }} · {{ version.deactivationReason }}</p>
          <el-table :data="version.items" size="small">
            <el-table-column label="参考图" width="72"><template #default="s"><ProductReferenceImage :reference="goodsImages.get(s.row.warehouseGoodsId)" :label="s.row.goodsCode" /></template></el-table-column>
            <el-table-column label="组成关系" min-width="190"><template #default="s">{{ parentLabel(version,s.row) }}</template></el-table-column>
            <el-table-column prop="goodsCode" label="货品编码"/><el-table-column prop="goodsName" label="货品名称"/>
            <el-table-column prop="quantity" label="单件用量"/><el-table-column prop="unit" label="单位" width="80"/><el-table-column prop="note" label="说明"/>
          </el-table>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="versionDialog" title="新增组成清单版本" width="min(1040px, calc(100vw - 32px))">
      <el-form :model="versionForm" label-position="top">
        <el-row :gutter="16">
          <el-col :span="8"><el-form-item label="生效日期"><el-date-picker v-model="versionForm.effectiveFrom" value-format="YYYY-MM-DD" type="date" style="width:100%"/></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="结束日期（含当天）"><el-date-picker v-model="versionForm.effectiveTo" value-format="YYYY-MM-DD" type="date" style="width:100%"/></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="初始状态"><el-select v-model="versionForm.status" style="width:100%"><el-option label="启用" value="ACTIVE"/><el-option label="停用（仅存档）" value="INACTIVE"/></el-select></el-form-item></el-col>
          <el-col :span="24"><el-form-item label="修改原因" required><el-input v-model="versionForm.changeReason" maxlength="1000"/></el-form-item></el-col>
        </el-row>
      </el-form>
      <p class="muted">每行用量均为每件销售 SKU 所需数量。替代件为可选关系，不自动替换；启用版本的日期区间不能重叠。</p>
      <div v-for="(row,index) in versionForm.items" :key="row.key" class="component-row">
        <span class="row-index">{{ index+1 }}</span>
        <el-select v-model="row.warehouseGoodsId" filterable placeholder="选择启用中的仓库货品" class="goods-select"><el-option v-for="g in goods" :key="g.id" :label="`${g.goodsCode} · ${g.goodsName} (${g.unit})`" :value="g.id" style="height:64px"><div class="goods-option"><ProductReferenceImage :reference="goodsImages.get(g.id)" :label="g.goodsCode"/><span>{{ g.goodsCode }} · {{ g.goodsName }}<small>{{ g.specification || '无规格' }} · {{ g.unit }}</small></span></div></el-option></el-select>
        <el-input-number v-model="row.quantity" :min="0.0001" :precision="4"/>
        <el-select v-if="row.parentKey" v-model="row.parentKey" placeholder="选择被替代的主要货品" class="relation-select"><el-option v-for="main in primaryRows" :key="main.key" :label="goodsLabel(main.warehouseGoodsId)" :value="main.key"/></el-select><span v-else class="relation-select">主要货品</span>
        <el-input v-model="row.note" placeholder="说明" maxlength="500" class="note-input"/>
        <el-button type="danger" plain @click="removeComponent(row)">移除</el-button>
      </div>
      <el-button @click="versionForm.items.push(newComponent())">＋ 添加主要货品</el-button><el-button @click="addSubstitute">＋ 添加替代货品</el-button>
      <template #footer><el-button @click="versionDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveVersion">创建新版本</el-button></template>
    </el-dialog>

    <el-dialog v-model="mappingDialog" :title="mappingEditing?'编辑店铺映射':'新增店铺映射'" width="min(620px, calc(100vw - 32px))">
      <el-form :model="mappingForm" label-position="top">
        <el-form-item label="店铺" required><el-select v-model="mappingForm.shopId" filterable style="width:100%"><el-option v-for="s in shops" :key="s.id" :label="`${s.shopName} · ${s.shopIdentifier}`" :value="s.id"/></el-select></el-form-item>
        <el-form-item label="平台 SKU" required><el-input v-model="mappingForm.platformSku" maxlength="160"/></el-form-item>
        <el-form-item label="平台商品 ID"><el-input v-model="mappingForm.platformProductId" maxlength="160"/></el-form-item>
        <el-form-item label="平台商品名称"><el-input v-model="mappingForm.platformProductName" maxlength="255"/></el-form-item>
        <el-form-item label="默认订单模式"><el-select v-model="mappingForm.defaultOrderMode"><el-option label="JIT" value="JIT"/><el-option label="WAREHOUSE" value="WAREHOUSE"/></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="mappingDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveMapping">保存</el-button></template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import {computed,reactive,ref,watch} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {ElMessage,ElMessageBox} from 'element-plus'
import {api,type AuthUser} from '../api'
import ImageManager from '../components/ImageManager.vue'
import ProductReferenceImage from '../components/ProductReferenceImage.vue'
import { useProductReferences } from '../utils/productReferences'
import {formatChinaDateTime} from '../utils/chinaTime'

type Goods={id:number;goodsCode:string;goodsName:string;specification?:string;unit:string}
type Line={id:number;goodsCode:string;goodsName:string;warehouseGoodsId:number;quantity:number;unit:string;substituteForItemId:number|null;note?:string}
type Version={id:number;versionNo:number;status:string;effectiveFrom?:string;effectiveTo?:string;changeReason:string;createdAt:string;createdBy:number;deactivationReason?:string;deactivatedAt?:string;items:Line[]}
type Mapping={id:number;shopId:number;shopName:string;shopIdentifier:string;skuId:number;platformSku:string;platformProductId?:string;platformProductName?:string;defaultOrderMode:string;status:string}
type Sku={id:number;internalSku:string;productName:string;spu?:string;specification?:string;status:string;note?:string;mappings?:Mapping[]}
type Preview={versionNo:number;salesQuantity:number;effectiveDate:string;items:Array<Line&{requiredQuantity:number;alternatives:Array<Line&{requiredQuantity:number}>}>}
type ComponentRow={key:string;warehouseGoodsId?:number;quantity:number;parentKey:string|null;note:string}
type Shop={id:number;shopName:string;shopIdentifier:string}
const props=defineProps<{user?:AuthUser}>(),route=useRoute(),router=useRouter()
const skuId=computed(()=>Number(route.params.skuId))
const has=(p:string)=>props.user?.permissions.includes(p)??false
const canViewMappings=computed(()=>has('master.mappings.view')),canViewCompositions=computed(()=>has('master.compositions.view'))
const canCompose=computed(()=>canViewCompositions.value&&has('button.compositions.manage')&&has('master.goods.view'))
const canMap=computed(()=>canViewMappings.value&&has('master.data.manage'))
const sku=ref<Sku>(),tab=ref('sales'),versions=ref<Version[]>([]),goods=ref<Goods[]>([]),shops=ref<Shop[]>([])
const skuImages=useProductReferences('SKU'),goodsImages=useProductReferences('WAREHOUSE_GOODS')
const loading=ref(false),loadError=ref(''),saving=ref(false),versionDialog=ref(false),mappingDialog=ref(false),mappingEditing=ref<Mapping>()
const previewQty=ref(1),previewDate=ref(''),previewLoading=ref(false),previewResult=ref<Preview>()
const versionForm=reactive({effectiveFrom:'',effectiveTo:'',status:'ACTIVE',changeReason:'',items:[] as ComponentRow[]})
const mappingForm=reactive({shopId:undefined as number|undefined,platformSku:'',platformProductId:'',platformProductName:'',defaultOrderMode:'JIT'})
const primaryRows=computed(()=>versionForm.items.filter(x=>!x.parentKey))
let nextKey=0
function newComponent():ComponentRow{return {key:String(++nextKey),warehouseGoodsId:undefined,quantity:1,parentKey:null,note:''}}
function message(e:unknown){return e instanceof Error?e.message:'操作失败，请重试'}
watch([skuId,()=>props.user],async()=>{if(!props.user)return;loading.value=true;loadError.value='';sku.value=undefined;versions.value=[];previewResult.value=undefined;versionDialog.value=false;mappingDialog.value=false;try{await loadDetails();if(canViewCompositions.value)await loadVersions()}catch(e){loadError.value=message(e)}finally{loading.value=false}},{immediate:true})
async function loadDetails(){sku.value=await api<Sku>(`/api/skus/${skuId.value}/product-details`);void skuImages.load([skuId.value])}
async function loadVersions(){versions.value=(await api<{versions:Version[]}>(`/api/skus/${skuId.value}/compositions`)).versions;void goodsImages.load(versions.value.flatMap(v=>v.items.map(item=>item.warehouseGoodsId)))}
async function allPages<T>(path:string):Promise<T[]>{const first=await api<{items:T[];total:number}>(`${path}&page=1&pageSize=100`);const result=[...first.items];for(let p=2;p<=Math.ceil(first.total/100);p++)result.push(...(await api<{items:T[]}>(`${path}&page=${p}&pageSize=100`)).items);return result}
function goodsLabel(id?:number){const g=goods.value.find(x=>x.id===id);return g?`${g.goodsCode} · ${g.goodsName}`:'尚未选择主要货品'}
function parentLabel(v:Version,line:Line){return line.substituteForItemId?`替代 ${v.items.find(x=>x.id===line.substituteForItemId)?.goodsCode||line.substituteForItemId}`:'主要货品'}
async function openVersion(source?:Version){try{goods.value=await allPages<Goods>('/api/warehouse-goods?status=ACTIVE');void goodsImages.load(goods.value.map(g=>g.id));const rows=source?source.items.map(line=>({...newComponent(),warehouseGoodsId:line.warehouseGoodsId,quantity:Number(line.quantity),note:line.note||''})):[newComponent()];if(source)source.items.forEach((line,i)=>{if(line.substituteForItemId)rows[i].parentKey=rows[source.items.findIndex(x=>x.id===line.substituteForItemId)]?.key||null});Object.assign(versionForm,{effectiveFrom:'',effectiveTo:'',status:'ACTIVE',changeReason:'',items:rows});versionDialog.value=true}catch(e){ElMessage.error(message(e))}}
function addSubstitute(){if(!primaryRows.value.length){ElMessage.warning('请先添加主要货品');return}versionForm.items.push({...newComponent(),parentKey:primaryRows.value[0].key})}
function removeComponent(row:ComponentRow){if(versionForm.items.some(x=>x.parentKey===row.key)){ElMessage.warning('请先移除或重新关联该货品的替代件');return}versionForm.items=versionForm.items.filter(x=>x.key!==row.key)}
async function saveVersion(){
  if(!versionForm.changeReason.trim()||!primaryRows.value.length||versionForm.items.some(x=>!x.warehouseGoodsId||!x.quantity||x.quantity<=0)){ElMessage.warning('请填写修改原因，并完整设置主要货品与用量');return}
  if(versionForm.items.some(x=>!goods.value.some(g=>g.id===x.warehouseGoodsId))){ElMessage.warning('清单含已停用货品，请重新选择启用中的货品');return}
  const items=versionForm.items.map(row=>({warehouseGoodsId:row.warehouseGoodsId,quantity:row.quantity,note:row.note,substituteForIndex:row.parentKey?versionForm.items.findIndex(x=>x.key===row.parentKey):null}))
  saving.value=true;try{await api(`/api/skus/${skuId.value}/compositions`,{method:'POST',body:JSON.stringify({...versionForm,items})});ElMessage.success('组成清单新版本已创建');versionDialog.value=false;previewResult.value=undefined;await loadVersions()}catch(e){ElMessage.error(message(e))}finally{saving.value=false}
}
async function deactivateVersion(v:Version){try{const {value}=await ElMessageBox.prompt('请输入停用原因。历史版本及其明细将保留。','停用组成清单版本',{inputPattern:/\S+/,inputErrorMessage:'停用原因必填'});await api(`/api/skus/${skuId.value}/compositions/${v.id}/deactivate`,{method:'POST',body:JSON.stringify({reason:value})});ElMessage.success('版本已停用');previewResult.value=undefined;await loadVersions()}catch(e){if(e!=='cancel'&&e!=='close')ElMessage.error(message(e))}}
async function preview(versionId?:number){previewResult.value=undefined;previewLoading.value=true;try{const p=new URLSearchParams({quantity:String(previewQty.value)});if(previewDate.value)p.set('date',previewDate.value);if(versionId)p.set('versionId',String(versionId));previewResult.value=await api<Preview>(`/api/skus/${skuId.value}/compositions/preview?${p}`)}catch(e){ElMessage.error(message(e))}finally{previewLoading.value=false}}
async function openMapping(row?:Mapping){try{shops.value=await allPages<Shop>('/api/master/shops?status=ACTIVE');if(row&&!shops.value.some(x=>x.id===row.shopId))shops.value.push({id:row.shopId,shopName:row.shopName,shopIdentifier:row.shopIdentifier});mappingEditing.value=row;Object.assign(mappingForm,row?{shopId:row.shopId,platformSku:row.platformSku,platformProductId:row.platformProductId||'',platformProductName:row.platformProductName||'',defaultOrderMode:row.defaultOrderMode}:{shopId:undefined,platformSku:'',platformProductId:'',platformProductName:'',defaultOrderMode:'JIT'});mappingDialog.value=true}catch(e){ElMessage.error(message(e))}}
async function saveMapping(){if(!mappingForm.shopId||!mappingForm.platformSku.trim()){ElMessage.warning('请选择店铺并填写平台 SKU');return}saving.value=true;try{const b={...mappingForm,skuId:skuId.value};const id=mappingEditing.value?.id;await api(id?`/api/shop-sku-mappings/${id}`:'/api/shop-sku-mappings',{method:id?'PUT':'POST',body:JSON.stringify(b)});mappingDialog.value=false;ElMessage.success('店铺映射已保存');await loadDetails()}catch(e){ElMessage.error(message(e))}finally{saving.value=false}}
async function deactivateMapping(m:Mapping){try{await ElMessageBox.confirm(`停用平台 SKU ${m.platformSku} 的映射？`,'确认停用',{type:'warning'});await api(`/api/shop-sku-mappings/${m.id}/deactivate`,{method:'POST'});await loadDetails()}catch(e){if(e!=='cancel'&&e!=='close')ElMessage.error(message(e))}}
</script>

<style scoped>
.subhead,.version-head{display:flex;justify-content:space-between;gap:12px;align-items:center;margin:12px 0}
.version-head>div,.preview-controls{display:flex;gap:12px;align-items:center;flex-wrap:wrap}
.preview-controls{margin:16px 0}.version-card{margin:14px 0;padding:14px}.muted{color:#737b8b;font-size:12px}
.component-row{display:flex;gap:8px;align-items:center;margin-bottom:12px}.goods-select{flex:2;min-width:180px}.relation-select{width:170px}.note-input{flex:1}.row-index{width:20px}
.sku-reference,.goods-option{display:flex;align-items:center;gap:12px}.sku-reference{margin:0 0 16px}.goods-option span{white-space:normal;line-height:1.3}.goods-option small{display:block;color:#909399}
@media(max-width:800px){.component-row,.subhead,.version-head{flex-wrap:wrap}.goods-select{min-width:230px}}
</style>
