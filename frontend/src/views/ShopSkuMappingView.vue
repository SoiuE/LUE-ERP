<template>
  <section class="master-page">
    <div class="management-head"><div><div class="eyebrow">PRODUCT DATA <span> / </span> SHOP SKU MAPPING</div><h1>店铺 SKU 映射</h1><p>将各店铺的平台 SKU 归集到系统内部 SKU，并设置默认订单模式。</p></div><el-button v-if="canManage" type="primary" @click="openCreate">＋ 新增映射</el-button></div>
    <div class="master-toolbar mapping-toolbar">
      <el-select v-model="shopFilter" clearable filterable remote :remote-method="loadFilterShops" :loading="shopFilterLoading" placeholder="全部店铺" class="mapping-shop-filter" @change="searchNow"><el-option v-for="shop in filterShops" :key="shop.id" :label="shop.shopName" :value="shop.id"/></el-select>
      <el-input v-model="platformSkuFilter" clearable placeholder="搜索平台 SKU" class="mapping-filter-input" @keyup.enter="searchNow" @clear="searchNow"/>
      <el-input v-model="internalSkuFilter" clearable placeholder="搜索内部 SKU" class="mapping-filter-input" @keyup.enter="searchNow" @clear="searchNow"/>
      <el-select v-model="statusFilter" clearable placeholder="全部状态" class="mapping-status-filter" @change="searchNow"><el-option label="启用" value="ACTIVE"/><el-option label="已停用" value="INACTIVE"/></el-select>
      <el-button @click="searchNow">查询</el-button>
    </div>
    <div class="table-card master-table-card">
      <el-table :data="items" v-loading="loading" empty-text="暂无映射数据">
        <el-table-column prop="shopName" label="店铺" min-width="150" show-overflow-tooltip/>
        <el-table-column prop="platformSku" label="平台 SKU" min-width="150" show-overflow-tooltip/>
        <el-table-column prop="platformProductId" label="平台商品 ID" min-width="145" show-overflow-tooltip/>
        <el-table-column prop="platformProductName" label="平台商品名称" min-width="180" show-overflow-tooltip/>
        <el-table-column label="内部 SKU" min-width="145"><template #default="scope"><strong>{{ scope.row.internalSku }}</strong><small class="mapping-product-name">{{ scope.row.productName }}</small></template></el-table-column>
        <el-table-column prop="defaultOrderMode" label="默认订单模式" width="165"><template #default="scope"><el-tag :type="scope.row.defaultOrderMode === 'JIT' ? 'warning' : 'primary'" effect="light" size="small">{{ scope.row.defaultOrderMode === 'JIT' ? 'JIT（即时履约）' : '仓发（WAREHOUSE）' }}</el-tag></template></el-table-column>
        <el-table-column prop="status" label="状态" width="90"><template #default="scope"><el-tag :type="scope.row.status === 'ACTIVE' ? 'success' : 'info'" effect="light" size="small">{{ scope.row.status === 'ACTIVE' ? '启用' : '已停用' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" fixed="right" width="132"><template #default="scope"><el-button v-if="canManage" link type="primary" @click="openEdit(scope.row)">编辑</el-button><el-button v-if="canManage && scope.row.status === 'ACTIVE'" link type="warning" @click="deactivate(scope.row)">停用</el-button></template></el-table-column>
      </el-table>
      <div class="master-pagination"><span>共 {{ total }} 条</span><el-pagination v-model:current-page="page" v-model:page-size="pageSize" :page-sizes="[10,20,50,100]" layout="sizes, prev, pager, next" :total="total" @current-change="load" @size-change="onSizeChange"/></div>
    </div>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑店铺 SKU 映射' : '新增店铺 SKU 映射'" width="min(680px, calc(100vw - 32px))" destroy-on-close>
      <el-form :model="form" label-position="top" class="mapping-form">
        <el-form-item label="店铺" required><el-select v-model="form.shopId" filterable remote :remote-method="loadShops" :loading="shopLoading" placeholder="搜索并选择启用中的店铺"><el-option v-for="shop in shops" :key="shop.id" :label="`${shop.shopName} · ${shop.shopIdentifier}`" :value="shop.id"/></el-select></el-form-item>
        <el-form-item label="内部 SKU" required><el-select v-model="form.skuId" filterable remote :remote-method="loadSkus" :loading="skuLoading" placeholder="搜索并选择启用中的内部 SKU"><el-option v-for="sku in skus" :key="sku.id" :label="`${sku.internalSku} · ${sku.productName}`" :value="sku.id"/></el-select></el-form-item>
        <el-form-item label="平台 SKU" required><el-input v-model="form.platformSku" maxlength="160" placeholder="填写店铺侧的平台 SKU"/></el-form-item>
        <el-form-item label="默认订单模式" required><el-select v-model="form.defaultOrderMode"><el-option label="JIT（即时履约）" value="JIT"/><el-option label="仓发（WAREHOUSE）" value="WAREHOUSE"/></el-select></el-form-item>
        <el-form-item label="平台商品 ID"><el-input v-model="form.platformProductId" maxlength="160" placeholder="选填"/></el-form-item>
        <el-form-item label="平台商品名称"><el-input v-model="form.platformProductName" maxlength="255" placeholder="选填"/></el-form-item>
      </el-form>
      <div class="mapping-rule-note"><span>i</span>同一店铺的平台 SKU 只能有一条启用映射；停用后可重新绑定。不同店铺可将相同平台 SKU 映射到同一内部 SKU。</div>
      <template #footer><el-button @click="dialogVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存映射</el-button></template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, type AuthUser } from '../api'

type Option = {id:number;shopName?:string;shopIdentifier?:string;internalSku?:string;productName?:string}
type Mapping = {id:number;shopId:number;shopName:string;shopIdentifier?:string;platformSku:string;platformProductId?:string;platformProductName?:string;skuId:number;internalSku:string;productName:string;defaultOrderMode:'JIT'|'WAREHOUSE';status:'ACTIVE'|'INACTIVE'}
const props=defineProps<{user?:AuthUser}>()
const canManage=computed(()=>props.user?.permissions.includes('master.data.manage')??false)
const items=ref<Mapping[]>([]);const total=ref(0);const page=ref(1);const pageSize=ref(20);const loading=ref(false)
const shopFilter=ref<number>();const platformSkuFilter=ref('');const internalSkuFilter=ref('');const statusFilter=ref('')
const filterShops=ref<Option[]>([]);const shopFilterLoading=ref(false)
const dialogVisible=ref(false);const editingId=ref<number>();const saving=ref(false);const shops=ref<Option[]>([]);const skus=ref<Option[]>([]);const shopLoading=ref(false);const skuLoading=ref(false)
const form=reactive<{shopId?:number;skuId?:number;platformSku:string;platformProductId:string;platformProductName:string;defaultOrderMode:'JIT'|'WAREHOUSE'}>({platformSku:'',platformProductId:'',platformProductName:'',defaultOrderMode:'JIT'})
onMounted(()=>{load();loadFilterShops('')})
async function load(){loading.value=true;try{const params=new URLSearchParams({page:String(page.value),pageSize:String(pageSize.value)});if(shopFilter.value)params.set('shopId',String(shopFilter.value));if(platformSkuFilter.value.trim())params.set('platformSku',platformSkuFilter.value.trim());if(internalSkuFilter.value.trim())params.set('internalSku',internalSkuFilter.value.trim());if(statusFilter.value)params.set('status',statusFilter.value);const result=await api<{items:Mapping[];total:number}>(`/api/shop-sku-mappings?${params}`);items.value=result.items;total.value=result.total;for(const row of result.items){if(!filterShops.value.some(x=>x.id===row.shopId))filterShops.value.push({id:row.shopId,shopName:row.shopName});}}catch(error){ElMessage.error(error instanceof Error?error.message:'映射列表读取失败')}finally{loading.value=false}}
function searchNow(){page.value=1;load()}
function onSizeChange(){page.value=1;load()}
async function loadFilterShops(q:string){shopFilterLoading.value=true;try{const params=new URLSearchParams({page:'1',pageSize:'100',status:'ACTIVE'});if(q.trim())params.set('q',q.trim());const result=await api<{items:Option[]}>(`/api/master/shops?${params}`);const selected=filterShops.value.filter(s=>s.id===shopFilter.value);filterShops.value=[...selected,...result.items.filter(s=>!selected.some(x=>x.id===s.id))]}catch{}finally{shopFilterLoading.value=false}}
async function loadShops(q:string){shopLoading.value=true;try{const params=new URLSearchParams({page:'1',pageSize:'100',status:'ACTIVE'});if(q.trim())params.set('q',q.trim());const result=await api<{items:Option[]}>(`/api/master/shops?${params}`);const selected=shops.value.filter(s=>s.id===form.shopId);shops.value=[...selected,...result.items.filter(s=>!selected.some(x=>x.id===s.id))]}catch(error){ElMessage.error(error instanceof Error?error.message:'店铺读取失败')}finally{shopLoading.value=false}}
async function loadSkus(q:string){skuLoading.value=true;try{const params=new URLSearchParams({page:'1',pageSize:'100',status:'ACTIVE'});if(q.trim())params.set('q',q.trim());const result=await api<{items:Option[]}>(`/api/master/skus?${params}`);const selected=skus.value.filter(s=>s.id===form.skuId);skus.value=[...selected,...result.items.filter(s=>!selected.some(x=>x.id===s.id))]}catch(error){ElMessage.error(error instanceof Error?error.message:'SKU 读取失败')}finally{skuLoading.value=false}}
async function openCreate(){editingId.value=undefined;Object.assign(form,{shopId:undefined,skuId:undefined,platformSku:'',platformProductId:'',platformProductName:'',defaultOrderMode:'JIT'});dialogVisible.value=true;await Promise.all([loadShops(''),loadSkus('')])}
async function openEdit(row:Mapping){editingId.value=row.id;Object.assign(form,{shopId:row.shopId,skuId:row.skuId,platformSku:row.platformSku,platformProductId:row.platformProductId||'',platformProductName:row.platformProductName||'',defaultOrderMode:row.defaultOrderMode});if(!shops.value.some(x=>x.id===row.shopId))shops.value.unshift({id:row.shopId,shopName:row.shopName,shopIdentifier:row.shopIdentifier});if(!skus.value.some(x=>x.id===row.skuId))skus.value.unshift({id:row.skuId,internalSku:row.internalSku,productName:row.productName});dialogVisible.value=true;await Promise.all([loadShops(''),loadSkus('')])}
async function save(){if(!form.shopId||!form.skuId||!form.platformSku.trim()){ElMessage.warning('请选择店铺、内部 SKU 并填写平台 SKU');return}const body={...form,platformSku:form.platformSku.trim()};saving.value=true;try{if(editingId.value)await api(`/api/shop-sku-mappings/${editingId.value}`,{method:'PUT',body:JSON.stringify(body)});else await api('/api/shop-sku-mappings',{method:'POST',body:JSON.stringify(body)});ElMessage.success(editingId.value?'映射已更新':'映射已创建');dialogVisible.value=false;await load()}catch(error){ElMessage.error(error instanceof Error?error.message:'保存失败；请检查该店铺下的平台 SKU 是否已有启用映射')}finally{saving.value=false}}
async function deactivate(row:Mapping){try{await ElMessageBox.confirm(`确定停用店铺“${row.shopName}”下的平台 SKU“${row.platformSku}”映射吗？停用后该平台 SKU 可重新绑定。`,'确认停用映射',{type:'warning',confirmButtonText:'确认停用',cancelButtonText:'取消'});await api(`/api/shop-sku-mappings/${row.id}/deactivate`,{method:'POST'});ElMessage.success('映射已停用');await load()}catch(error){if(error!=='cancel'&&error!=='close')ElMessage.error(error instanceof Error?error.message:'停用失败')}}
</script>
