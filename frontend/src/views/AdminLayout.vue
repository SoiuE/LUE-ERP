<template>
  <div class="admin-shell">
    <aside class="sidebar" :class="{ collapsed }">
      <div class="sidebar-brand"><div class="brand-mark small">L</div><div v-if="!collapsed" class="brand-copy"><strong>LUE ERP</strong><span>COMMERCE OPERATIONS</span></div><button class="collapse-button" @click="collapsed = !collapsed">{{ collapsed ? '›' : '‹' }}</button></div>
      <div v-if="!collapsed" class="nav-caption">工作空间</div>
      <el-menu :default-active="route.path" router :collapse="collapsed" class="side-menu">
        <el-menu-item v-for="item in visibleMenus" :key="item.path" :index="item.path"><el-icon><component :is="item.icon" /></el-icon><template #title>{{ item.title }}</template></el-menu-item>
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
import { DataBoard, User, Key, Document, Shop, Goods, Box, Connection, Upload, Tickets, Coin, Files, Iphone, Van } from '@element-plus/icons-vue'
import { api, clearToken, getToken, type AuthUser } from '../api'

const route = useRoute(); const router = useRouter()
const user = ref<AuthUser>()
const collapsed = ref(false)
const menus = [
  { title: '工作台', path: '/', permission: 'menu.dashboard', icon: DataBoard },
  { title: '用户管理', path: '/users', permission: 'menu.users', icon: User },
  { title: '角色与权限', path: '/roles', permission: 'menu.roles', icon: Key },
  { title: '操作日志', path: '/logs', permission: 'menu.logs', icon: Document },
  { title: '店铺管理', path: '/shops', permission: 'master.shops.view', icon: Shop },
  { title: 'SKU 中心', path: '/skus', permission: 'master.skus.view', icon: Goods },
  { title: '供应商档案', path: '/suppliers', permission: 'master.suppliers.view', icon: User },
  { title: '包材档案', path: '/materials', permission: 'master.materials.view', icon: Box },
  { title: '店铺 SKU 映射', path: '/mappings', permission: 'master.mappings.view', icon: Connection },
  { title: '订单数据', path: '/orders', permission: 'menu.imports', icon: Document },
  { title: '异常中心', path: '/exceptions', permission: 'menu.imports', icon: Connection },
  { title: '导入中心', path: '/imports', permission: 'menu.imports', icon: Upload },
  { title: '采购管理', path: '/purchases', permission: 'menu.purchases', icon: Tickets },
  { title: '本地仓库存', path: '/inventory', permission: 'menu.inventory', icon: Coin },
  { title: '包材库存', path: '/package-stocks', permission: 'menu.package-stocks', icon: Files },
  { title: 'SKU 利润报表', path: '/profits', permission: 'menu.profits', icon: Coin },
  { title: '本地发货履约', path: '/fulfillment', permission: 'menu.fulfillment', icon: Van },
  { title: '手机仓库', path: '/mobile', permission: 'menu.mobile', icon: Iphone },
]
const visibleMenus = computed(() => menus.filter(item => user.value?.permissions.includes(item.permission)))
const currentTitle = computed(() => menus.find(item => item.path === route.path)?.title || '工作台')
onMounted(async () => { user.value = await api<AuthUser>('/api/auth/me') })
async function handleCommand(command: string) {
  if (command !== 'logout') return
  try { await api('/api/auth/logout', { method: 'POST' }) } catch { /* Local logout still completes when the server is unavailable. */ }
  clearToken(); user.value = undefined; await router.replace('/login')
}
</script>
