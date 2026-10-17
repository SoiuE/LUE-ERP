<template>
  <div class="admin-shell">
    <aside class="sidebar" :class="{ collapsed }">
      <div class="sidebar-brand"><div class="brand-mark small">L</div><div v-if="!collapsed" class="brand-copy"><strong>LUE ERP</strong><span>COMMERCE OPERATIONS</span></div><button class="collapse-button" @click="collapsed = !collapsed">{{ collapsed ? '›' : '‹' }}</button></div>
      <div v-if="!collapsed" class="nav-caption">岗位工作区</div>
      <el-menu :default-active="activeMenuPath" :default-openeds="defaultOpenGroups" router :collapse="collapsed" class="side-menu">
        <el-sub-menu v-for="group in visibleGroups" :key="group.id" :index="group.id">
          <template #title><el-icon><component :is="group.icon" /></el-icon><span>{{ group.title }}</span></template>
          <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path"><el-icon><component :is="item.icon" /></el-icon><template #title>{{ item.title }}</template></el-menu-item>
        </el-sub-menu>
      </el-menu>
      <div class="sidebar-bottom"><div class="sidebar-help"><div class="help-icon">?</div><div v-if="!collapsed"><strong>需要帮助？</strong><span>联系系统管理员</span></div></div><div class="version">{{ collapsed ? 'v0.1' : 'LUE ERP · 内部管理系统 v0.1' }}</div></div>
    </aside>
    <section class="main-column">
      <header class="topbar"><div class="breadcrumb"><span>工作空间</span><b>/</b><strong>{{ currentTitle }}</strong></div><div class="top-actions"><div class="system-status"><i></i>系统运行正常</div><el-dropdown @command="handleCommand"><button class="user-chip"><span class="avatar">{{ user?.displayName?.slice(0, 1) || '管' }}</span><span class="user-name">{{ user?.displayName || '用户' }}</span><span class="chevron">⌄</span></button><template #dropdown><el-dropdown-menu><el-dropdown-item command="logout">退出登录</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div></header>
      <main class="page-area"><router-view :user="user" /></main>
      <footer class="main-footer">© 2026 LUE Commerce ERP <span>内部管理系统</span></footer>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { DataBoard, User, Key, Document, Shop, Goods, Box, Connection, Upload, Tickets, Coin, Files, Iphone, Van, TakeawayBox, Operation, Wallet, Setting, Picture } from '@element-plus/icons-vue'
import { api, clearToken, getToken, type AuthUser } from '../api'

const route = useRoute(); const router = useRouter()
const user = ref<AuthUser>()
const collapsed = ref(false)
const groups = [
  { id:'overview', title:'经营总览', icon:DataBoard, items:[{title:'经营工作台',path:'/',permission:'menu.dashboard',icon:DataBoard}] },
  { id:'products', title:'商品与映射', icon:Goods, items:[
    {title:'内部销售 SKU 与组成清单',path:'/skus',permission:'master.skus.view',icon:Goods},
    {title:'仓库货品',path:'/warehouse-goods',permission:'master.goods.view',icon:TakeawayBox},
    {title:'图片库',path:'/images',permission:'menu.images',icon:Picture},
    {title:'以图搜商品',path:'/image-search',permission:'menu.image-search',icon:Picture},
    {title:'店铺 SKU 映射',path:'/mappings',permission:'master.mappings.view',icon:Connection},
    {title:'店铺档案',path:'/shops',permission:'master.shops.view',icon:Shop},
  ] },
  { id:'orders', title:'订单与履约', icon:Van, items:[
    {title:'订单',path:'/orders',permission:'menu.imports',icon:Document},
    {title:'平台导入',path:'/imports',permission:'menu.imports',icon:Upload},
    {title:'发货单 / 拣货 / 短缺 / 预约',path:'/fulfillment',permission:'menu.fulfillment',icon:Van},
    {title:'异常中心',path:'/exceptions',permission:'menu.imports',icon:Connection},
  ] },
  { id:'warehouse', title:'仓库作业', icon:Operation, items:[
    {title:'仓库与库位',path:'/warehouse-locations',permission:'warehouse.locations.view',icon:Box},
    {title:'货品库存 / 待上架 / 移库 / 流水',path:'/location-inventory',permission:'warehouse.location_stock.view',icon:Coin},
    {title:'移动仓库工作台',path:'/mobile',permission:'menu.mobile',icon:Iphone},
  ] },
  { id:'purchases', title:'采购', icon:Tickets, items:[
    {title:'货品采购 / 收货 / 上架',path:'/purchases',permission:'menu.purchases',icon:Tickets},
    {title:'供应商档案',path:'/suppliers',permission:'master.suppliers.view',icon:User},
  ] },
  { id:'packages', title:'包材', icon:Files, items:[
    {title:'包材档案与方案',path:'/materials',permission:'master.materials.view',icon:Box},
    {title:'包材库存与流水',path:'/package-stocks',permission:'menu.package-stocks',icon:Files},
  ] },
  { id:'profit', title:'利润与结算', icon:Wallet, items:[{title:'利润 / 结算明细',path:'/profits',permission:'menu.profits',icon:Wallet}] },
  { id:'system', title:'系统管理', icon:Setting, items:[
    {title:'用户管理',path:'/users',permission:'menu.users',icon:User},
    {title:'角色与权限',path:'/roles',permission:'menu.roles',icon:Key},
    {title:'操作日志',path:'/logs',permission:'menu.logs',icon:Document},
  ] },
]
const visibleGroups = computed(() => groups.map(group=>({...group,items:group.items.filter(item=>user.value?.permissions.includes(item.permission))})).filter(group=>group.items.length>0))
const activeItem = computed(() => visibleGroups.value.flatMap(group=>group.items.map(item=>({group,item}))).find(({item})=>route.path===item.path||route.path.startsWith(item.path==='/'?'/__never__':item.path+'/')))
const activeMenuPath = computed(() => activeItem.value?.item.path || route.path)
const defaultOpenGroups = computed(() => activeItem.value ? [activeItem.value.group.id] : [])
const currentTitle = computed(() => activeItem.value?.item.title || String(route.meta.title || '工作台'))
onMounted(async () => { user.value = await api<AuthUser>('/api/auth/me') })
async function handleCommand(command: string) {
  if (command !== 'logout') return
  try { await api('/api/auth/logout', { method: 'POST' }) } catch { /* Local logout still completes when the server is unavailable. */ }
  clearToken(); user.value = undefined; await router.replace('/login')
}
</script>
