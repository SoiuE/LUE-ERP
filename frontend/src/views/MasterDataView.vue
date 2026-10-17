<template>
  <section class="master-page">
    <div class="management-head"><div><div class="eyebrow">MASTER DATA <span> / </span> {{ config.english }}</div><h1>{{ config.title }}</h1><p>{{ config.description }}</p></div><el-button v-if="canManage" type="primary" @click="openCreate">＋ 新增{{ config.shortName }}</el-button></div>
    <div class="master-toolbar">
      <el-input v-model="query" clearable :placeholder="`搜索${config.shortName}名称、编码…`" class="master-search" @keyup.enter="searchNow" @clear="searchNow"><template #prefix><el-icon><Search /></el-icon></template></el-input>
      <el-select v-model="statusFilter" clearable placeholder="全部状态" class="filter-select" @change="searchNow"><el-option label="启用" value="ACTIVE"/><el-option label="已停用" value="INACTIVE"/></el-select>
      <el-select v-if="kind === 'materials'" v-model="categoryFilter" clearable placeholder="全部包材分类" class="filter-select" @change="searchNow"><el-option label="产品包装" value="PRODUCT_PACKAGING"/><el-option label="发货打包材料" value="SHIPPING_MATERIAL"/></el-select>
      <el-button @click="searchNow">查询</el-button>
    </div>
    <div class="table-card master-table-card">
      <el-table :data="items" v-loading="loading" row-key="id" empty-text="暂无数据">
        <el-table-column v-if="kind==='skus'" label="主图" width="80"><template #default="scope"><ProductReferenceImage :reference="skuImages.get(scope.row.id)" :label="scope.row.internalSku" /></template></el-table-column>
        <el-table-column v-for="column in config.columns" :key="column.key" :prop="column.key" :label="column.label" :min-width="column.width || 120" show-overflow-tooltip>
          <template #default="scope"><el-tag v-if="column.key === 'status'" :type="scope.row.status === 'ACTIVE' ? 'success' : 'info'" effect="light" size="small">{{ scope.row.status === 'ACTIVE' ? '启用' : '已停用' }}</el-tag><el-tag v-else-if="column.key === 'category'" :type="scope.row.category === 'PRODUCT_PACKAGING' ? 'warning' : 'primary'" effect="light" size="small">{{ categoryName(scope.row.category) }}</el-tag><span v-else-if="column.money">¥ {{ formatMoney(scope.row[column.key]) }}</span><span v-else-if="column.key === 'supplierName'">{{ scope.row.supplierName || '—' }}</span><span v-else>{{ scope.row[column.key] ?? '—' }}</span></template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="最近更新" min-width="160"><template #default="scope">{{ formatDate(scope.row.updatedAt) }}</template></el-table-column>
        <el-table-column label="操作" fixed="right" :width="kind==='skus'?250:176"><template #default="scope"><el-button v-if="kind==='skus'" link type="success" @click="openSkuDetails(scope.row)">详情 / 多图</el-button><template v-if="canManage"><el-button link type="primary" @click="openEdit(scope.row)">编辑</el-button><el-button v-if="scope.row.status === 'ACTIVE'" link type="warning" @click="deactivate(scope.row)">停用</el-button><el-button link type="danger" @click="softDelete(scope.row)">删除</el-button></template></template></el-table-column>
      </el-table>
      <div class="master-pagination"><span>共 {{ total }} 条</span><el-pagination v-model:current-page="page" v-model:page-size="pageSize" :page-sizes="[10,20,50,100]" layout="sizes, prev, pager, next" :total="total" @current-change="load" @size-change="onSizeChange"/></div>
    </div>

    <el-dialog v-model="dialogVisible" :title="`${editingId ? '编辑' : '新增'}${config.shortName}`" width="min(760px, calc(100vw - 32px))" destroy-on-close>
      <el-form :model="form" label-position="top" class="master-form" @submit.prevent>
        <el-form-item v-for="field in config.fields" :key="field.key" :label="field.label" :required="field.required" :class="{ 'span-two': field.wide }">
          <template v-if="field.type === 'packagePlan'">
            <div class="package-plan-editor">
              <el-input v-model="materialSearch[field.category!]" clearable :placeholder="`搜索${field.label}包材名称或编码`" class="package-plan-search" />
              <el-checkbox-group :model-value="selectedMaterials[field.category!]" class="package-plan-options" @update:model-value="updateSelectedMaterials(field.category!, $event)">
                <el-checkbox v-for="material in filteredMaterials(field.category!)" :key="material.materialCode" :value="material.materialCode" class="package-plan-option">
                  <span>{{ material.materialName }} <small>{{ material.materialCode }} · {{ material.specification || '无规格' }} · {{ material.unit }}</small></span>
                </el-checkbox>
              </el-checkbox-group>
              <div v-if="selectedPlanMaterials(field.category!).length" class="package-plan-selected">
                <div v-for="material in selectedPlanMaterials(field.category!)" :key="material.materialCode" class="package-plan-row">
                  <span>{{ material.materialName }}<small>{{ material.materialCode }} · {{ material.unit }}</small></span>
                  <el-input-number v-model="planQuantities[field.category!][material.materialCode]" :min="0.0001" :precision="4" :step="1" controls-position="right" />
                </div>
                <div class="form-field-hint">数量表示每销售 / 出库 1 件该 SKU 时消耗的包材数量。</div>
              </div>
              <div v-else class="form-field-hint">勾选一个或多个包材；暂不配置时，该 SKU 不自动消耗此类包材。</div>
            </div>
          </template>
          <el-select v-else-if="field.options" v-model="form[field.key]" :placeholder="`请选择${field.label}`" clearable filterable>
            <el-option v-for="option in field.options" :key="String(option.value)" :label="option.label" :value="option.value"/>
          </el-select>
          <el-select v-else-if="field.key === 'supplierId'" v-model="form.supplierId" placeholder="选择启用中的供应商" clearable filterable>
            <el-option v-for="supplier in suppliers" :key="supplier.id" :label="`${supplier.supplierName}（${supplier.supplierCode}）`" :value="supplier.id"/>
          </el-select>
          <el-input-number v-else-if="field.type === 'number'" v-model="form[field.key]" :min="0" :precision="4" :step="field.money ? 1 : 0.1" controls-position="right" />
          <el-input v-else-if="field.type === 'json'" v-model="form[field.key]" type="textarea" :rows="3" placeholder='JSON，例如：[ {"materialCode":"BOX-01","quantity":1} ]' />
          <el-input v-else-if="field.type === 'textarea'" v-model="form[field.key]" type="textarea" :rows="2" :maxlength="field.maxlength || 1000" show-word-limit />
          <el-input v-else v-model="form[field.key]" :maxlength="field.maxlength || 255" :placeholder="`请输入${field.label}`" />
          <div v-if="field.hint" class="form-field-hint">{{ field.hint }}</div>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { api, type AuthUser } from '../api'
import ProductReferenceImage from '../components/ProductReferenceImage.vue'
import { useProductReferences } from '../utils/productReferences'
import { formatChinaDateTime } from '../utils/chinaTime'

type Field = { key: string; label: string; type?: 'number'|'textarea'|'json'|'packagePlan'; category?:'PRODUCT_PACKAGING'|'SHIPPING_MATERIAL'; required?: boolean; wide?: boolean; money?: boolean; maxlength?: number; hint?:string; options?: {label:string;value:string}[] }
const props = defineProps<{ kind: 'shops'|'skus'|'suppliers'|'materials'; user?: AuthUser }>()
const router = useRouter()
const commonStatus: Field = { key: 'status', label: '状态', options: [{label:'启用',value:'ACTIVE'},{label:'停用',value:'INACTIVE'}] }
const configs: Record<string, {title:string;shortName:string;english:string;description:string;columns:{key:string;label:string;width?:number;money?:boolean}[];fields:Field[]}> = {
  shops: { title:'店铺管理', shortName:'店铺', english:'SHOPS', description:'维护平台店铺档案，停用店铺不会出现在后续业务单据的默认选项中。', columns:[{key:'shopName',label:'店铺名称',width:170},{key:'platform',label:'平台',width:110},{key:'shopIdentifier',label:'店铺标识',width:160},{key:'accountEntity',label:'账号主体'},{key:'ownerName',label:'负责人'},{key:'status',label:'状态',width:90}], fields:[{key:'shopName',label:'店铺名称',required:true},{key:'platform',label:'平台',required:true,options:[{label:'速卖通',value:'ALIEXPRESS'},{label:'Temu',value:'TEMU'},{label:'TikTok',value:'TIKTOK'},{label:'Shopee',value:'SHOPEE'},{label:'其他',value:'OTHER'}]},{key:'shopIdentifier',label:'店铺账号或标识',required:true},{key:'accountEntity',label:'账号主体'},{key:'ownerName',label:'店铺负责人'},commonStatus,{key:'note',label:'备注',type:'textarea',wide:true}] },
  suppliers: { title:'供应商档案', shortName:'供应商', english:'SUPPLIERS', description:'维护供应商联系人和主体信息，停用供应商后不能选作新 SKU 的默认供应商。', columns:[{key:'supplierCode',label:'供应商编码',width:170},{key:'supplierName',label:'供应商名称',width:190},{key:'contactName',label:'联系人'},{key:'phone',label:'联系电话'},{key:'email',label:'邮箱'},{key:'status',label:'状态',width:90}], fields:[{key:'supplierCode',label:'供应商编码',required:true,hint:'自动规则：SUP + 日期（YYYYMMDD）+ 6 位序号；可修改。自定义编码限 2–64 位大写字母、数字、短横线或下划线，且不能重复。'},{key:'supplierName',label:'供应商名称',required:true},{key:'contactName',label:'联系人'},{key:'phone',label:'联系电话'},{key:'email',label:'邮箱'},{key:'address',label:'地址',type:'textarea',wide:true},{key:'status',label:'状态',options:[{label:'启用',value:'ACTIVE'},{label:'停用',value:'INACTIVE'}]},{key:'note',label:'备注',type:'textarea',wide:true}] },
  materials: { title:'包材档案', shortName:'包材', english:'PACKAGING MATERIALS', description:'维护包材分类、规格和人民币成本；分类用于区分商品包装与发货打包材料。', columns:[{key:'materialCode',label:'包材编码',width:170},{key:'materialName',label:'包材名称',width:180},{key:'category',label:'包材分类',width:150},{key:'specification',label:'规格'},{key:'unit',label:'单位',width:80},{key:'unitPrice',label:'单价（人民币）',money:true,width:140},{key:'safetyStock',label:'安全库存',width:110},{key:'status',label:'状态',width:90}], fields:[{key:'materialCode',label:'包材编码',required:true,hint:'自动规则：MAT + 日期（YYYYMMDD）+ 6 位序号；可修改。自定义编码限 2–64 位大写字母、数字、短横线或下划线，且不能重复。'},{key:'materialName',label:'包材名称',required:true},{key:'category',label:'包材分类',required:true,options:[{label:'产品包装',value:'PRODUCT_PACKAGING'},{label:'发货打包材料',value:'SHIPPING_MATERIAL'}]},{key:'specification',label:'规格'},{key:'unit',label:'单位',required:true},{key:'unitPrice',label:'单价（人民币）',type:'number',money:true},{key:'safetyStock',label:'安全库存',type:'number'},{key:'status',label:'状态',options:[{label:'启用',value:'ACTIVE'},{label:'停用',value:'INACTIVE'}]},{key:'note',label:'备注',type:'textarea',wide:true}] },
  skus: { title:'SKU 中心', shortName:'SKU', english:'SKU CATALOG', description:'维护内部 SKU、成本、规格，并从包材档案勾选多个默认产品包装和发货打包材料，可分别设置单件消耗量。金额统一为人民币。图片请在 SKU 详情中维护。', columns:[{key:'internalSku',label:'内部 SKU',width:145},{key:'productName',label:'商品名称',width:200},{key:'spu',label:'SPU',width:110},{key:'specification',label:'规格'},{key:'supplierName',label:'供应商',width:150},{key:'purchasePrice',label:'采购价（人民币）',money:true,width:145},{key:'status',label:'状态',width:90}], fields:[{key:'internalSku',label:'内部 SKU',required:true},{key:'spu',label:'SPU'},{key:'productName',label:'商品名称',required:true,wide:true},{key:'nameZh',label:'中文名称'},{key:'nameEn',label:'英文名称'},{key:'specification',label:'规格'},{key:'color',label:'颜色'},{key:'size',label:'尺码'},{key:'barcode',label:'条码'},{key:'category',label:'品类'},{key:'supplierId',label:'默认供应商'},{key:'purchasePrice',label:'采购价（人民币）',type:'number',money:true},{key:'supplyPrice',label:'供货价（人民币）',type:'number',money:true},{key:'weight',label:'重量',type:'number'},{key:'length',label:'长度',type:'number'},{key:'width',label:'宽度',type:'number'},{key:'height',label:'高度',type:'number'},{key:'defaultProductPackagingPlan',label:'默认产品包装方案',type:'packagePlan',category:'PRODUCT_PACKAGING',wide:true},{key:'defaultShippingMaterialPlan',label:'默认发货打包材料方案',type:'packagePlan',category:'SHIPPING_MATERIAL',wide:true},{key:'status',label:'状态',options:[{label:'启用',value:'ACTIVE'},{label:'停用',value:'INACTIVE'}]},{key:'note',label:'备注',type:'textarea',wide:true}] },
}
const config = computed(() => configs[props.kind])
const canManage = computed(() => props.user?.permissions.includes('master.data.manage') ?? false)
const items = ref<Record<string,any>[]>([]); const suppliers = ref<Record<string,any>[]>([])
type PackageMaterial = {id:number;materialCode:string;materialName:string;category:string;specification:string;unit:string;status:string}
const packageMaterials = ref<PackageMaterial[]>([])
const selectedMaterials = reactive<Record<string,string[]>>({PRODUCT_PACKAGING:[],SHIPPING_MATERIAL:[]})
const planQuantities = reactive<Record<string,Record<string,number>>>({PRODUCT_PACKAGING:{},SHIPPING_MATERIAL:{}})
const materialSearch = reactive<Record<string,string>>({PRODUCT_PACKAGING:'',SHIPPING_MATERIAL:''})
const page = ref(1); const pageSize = ref(20); const total = ref(0); const loading = ref(false); const saving = ref(false)
const query = ref(''); const statusFilter = ref(''); const categoryFilter = ref(''); const dialogVisible = ref(false); const editingId = ref<number>()
const form = reactive<Record<string,any>>({})
const skuImages = useProductReferences('SKU')
onMounted(() => { load(); if (props.kind === 'skus') loadSuppliers() })
watch(() => props.kind, () => { page.value=1; query.value=''; statusFilter.value=''; categoryFilter.value=''; load(); if (props.kind === 'skus') loadSuppliers() })
async function load() {
  loading.value = true
  try {
    const params = new URLSearchParams({ page:String(page.value), pageSize:String(pageSize.value) })
    if (query.value.trim()) params.set('q',query.value.trim())
    if (statusFilter.value) params.set('status',statusFilter.value)
    if (categoryFilter.value) params.set('category',categoryFilter.value)
    const result = await api<{items:Record<string,any>[];total:number}>(`/api/master/${props.kind}?${params}`)
    items.value=result.items; total.value=result.total
    if(props.kind==='skus')void skuImages.load(result.items.map(item=>Number(item.id)))
  } catch(error) { ElMessage.error(error instanceof Error ? error.message : '列表读取失败') }
  finally { loading.value=false }
}
function searchNow(){page.value=1;load()}
function openSkuDetails(row:Record<string,any>){router.push(`/skus/${row.id}/details`)}
function onSizeChange(){page.value=1;load()}
async function loadSuppliers(){try{const data=await api<{items:Record<string,any>[]}>(`/api/master/suppliers?page=1&pageSize=100&status=ACTIVE`);suppliers.value=data.items}catch{}}
function blankForm(){for(const field of config.value.fields){form[field.key]=field.type==='json'?'':field.type==='packagePlan'?[]:field.type==='number'?0:field.key==='status'?'ACTIVE':field.key==='platform'?'ALIEXPRESS':''}for(const category of ['PRODUCT_PACKAGING','SHIPPING_MATERIAL']){selectedMaterials[category]=[];planQuantities[category]={};materialSearch[category]=''}}
function readPlan(value:any):{materialCode:string;quantity?:number}[]{if(!value)return[];try{const result=typeof value==='string'?JSON.parse(value):value;return Array.isArray(result)?result:[]}catch{return[]}}
function setPlan(category:string,value:any){const rows=readPlan(value);selectedMaterials[category]=rows.map(row=>String(row.materialCode));planQuantities[category]=Object.fromEntries(rows.map(row=>[String(row.materialCode),Number(row.quantity||1)]))}
async function loadPackageMaterials(){const loadCategory=async(category:string)=>{const first=await api<{items:PackageMaterial[];total:number}>(`/api/master/materials?page=1&pageSize=100&status=ACTIVE&category=${category}`);const pages=Math.ceil(first.total/100);if(pages<=1)return first.items;const remaining=await Promise.all(Array.from({length:pages-1},(_,index)=>api<{items:PackageMaterial[]}>(`/api/master/materials?page=${index+2}&pageSize=100&status=ACTIVE&category=${category}`)));return [...first.items,...remaining.flatMap(page=>page.items)]};const [product,shipping]=await Promise.all(['PRODUCT_PACKAGING','SHIPPING_MATERIAL'].map(loadCategory));packageMaterials.value=[...product,...shipping]}
function filteredMaterials(category:string){const q=materialSearch[category].trim().toLowerCase();return packageMaterials.value.filter(item=>item.category===category&&(!q||`${item.materialCode} ${item.materialName} ${item.specification||''}`.toLowerCase().includes(q)))}
function selectedPlanMaterials(category:string){return selectedMaterials[category].map(code=>packageMaterials.value.find(item=>item.materialCode===code)).filter((item):item is PackageMaterial=>!!item)}
function updateSelectedMaterials(category:string,value:string[]){selectedMaterials[category]=value;for(const code of value)if(!planQuantities[category][code])planQuantities[category][code]=1}
async function openCreate(){editingId.value=undefined;blankForm();dialogVisible.value=true;if(props.kind==='skus'){try{await loadPackageMaterials()}catch(error){ElMessage.error(error instanceof Error?error.message:'包材档案读取失败')}}const field=props.kind==='suppliers'?'supplierCode':props.kind==='materials'?'materialCode':'';if(field){try{const next=await api<{code:string}>(`/api/master/${props.kind}/next-code`);if(dialogVisible.value&&!editingId.value&&!form[field])form[field]=next.code}catch(error){ElMessage.error(error instanceof Error?error.message:'自动编码获取失败')}}}
async function openEdit(row:Record<string,any>){editingId.value=row.id;blankForm();for(const field of config.value.fields){const value=row[field.key];if(field.type==='packagePlan'){setPlan(field.category!,value);form[field.key]=selectedMaterials[field.category!]}else form[field.key]=field.type==='json'&&value?JSON.stringify(typeof value==='string'?JSON.parse(value):value,null,2):value??form[field.key]}dialogVisible.value=true;if(props.kind==='skus'){try{await loadPackageMaterials()}catch(error){ElMessage.error(error instanceof Error?error.message:'包材档案读取失败')}}}
async function save(){
  const missing=config.value.fields.find(field=>field.required&&(form[field.key]===undefined||form[field.key]===null||String(form[field.key]).trim()===''))
  if(missing){ElMessage.warning(`请填写${missing.label}`);return}
  for(const field of config.value.fields.filter(item=>item.type==='json'&&form[item.key])){try{JSON.parse(form[field.key])}catch{ElMessage.warning(`${field.label}必须为有效 JSON`);return}}
  const body:Record<string,any>={...form};for(const field of config.value.fields.filter(item=>item.type==='json'))body[field.key]=body[field.key]?JSON.parse(body[field.key]):null
  for(const field of config.value.fields.filter(item=>item.type==='packagePlan')){const category=field.category!;body[field.key]=selectedMaterials[category].map(materialCode=>({materialCode,quantity:Number(planQuantities[category][materialCode]||1)}))}
  saving.value=true
  try{if(editingId.value)await api(`/api/master/${props.kind}/${editingId.value}`,{method:'PUT',body:JSON.stringify(body)});else await api(`/api/master/${props.kind}`,{method:'POST',body:JSON.stringify(body)});ElMessage.success(editingId.value?'已保存修改':'已新增');dialogVisible.value=false;await load()}
  catch(error){ElMessage.error(error instanceof Error?error.message:'保存失败')}
  finally{saving.value=false}
}
async function deactivate(row:Record<string,any>){try{await ElMessageBox.confirm(`确定停用“${row[config.value.columns[0].key]}”吗？停用后该档案不会作为新业务的默认选项。`,'确认停用',{type:'warning',confirmButtonText:'确认停用',cancelButtonText:'取消'});await api(`/api/master/${props.kind}/${row.id}/deactivate`,{method:'POST'});ElMessage.success('已停用');await load()}catch(error){if(error!=='cancel'&&error!=='close')ElMessage.error(error instanceof Error?error.message:'停用失败')}}
async function softDelete(row:Record<string,any>){try{await ElMessageBox.confirm(`确定删除“${row[config.value.columns[0].key]}”吗？记录将从日常列表隐藏。`,'确认删除',{type:'warning',confirmButtonText:'确认删除',cancelButtonText:'取消'});await api(`/api/master/${props.kind}/${row.id}`,{method:'DELETE'});ElMessage.success('已删除');await load()}catch(error){if(error!=='cancel'&&error!=='close')ElMessage.error(error instanceof Error?error.message:'删除失败')}}
function categoryName(category:string){return category==='PRODUCT_PACKAGING'?'产品包装':category==='SHIPPING_MATERIAL'?'发货打包材料':category||'—'}
function formatMoney(value:any){return Number(value||0).toFixed(4)}
function formatDate(value:any){return formatChinaDateTime(value)}
</script>
