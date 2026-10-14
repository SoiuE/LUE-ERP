import { createRouter, createWebHistory } from 'vue-router'
import { api, clearToken, getToken, type AuthUser } from './api'
import LoginView from './views/LoginView.vue'
import AdminLayout from './views/AdminLayout.vue'
import PlaceholderView from './views/PlaceholderView.vue'
import MasterDataView from './views/MasterDataView.vue'
import ShopSkuMappingView from './views/ShopSkuMappingView.vue'
import ImportCenterView from './views/ImportCenterView.vue'
import OrdersView from './views/OrdersView.vue'
import ExceptionsView from './views/ExceptionsView.vue'
import WarehouseView from './views/WarehouseView.vue'
import MobileLayout from './views/mobile/MobileLayout.vue'
import MobileHomeView from './views/mobile/MobileHomeView.vue'
import MobilePurchaseView from './views/mobile/MobilePurchaseView.vue'
import MobileInventoryView from './views/mobile/MobileInventoryView.vue'
import MobilePackageView from './views/mobile/MobilePackageView.vue'
import MobileSkuView from './views/mobile/MobileSkuView.vue'
import ProfitView from './views/ProfitView.vue'
import FulfillmentView from './views/FulfillmentView.vue'
import MobileFulfillmentView from './views/mobile/MobileFulfillmentView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView, meta: { public: true } },
    { path: '/', component: AdminLayout, children: [
      { path: '', component: PlaceholderView, meta: { title: '工作台', permission: 'menu.dashboard' } },
      { path: 'users', component: PlaceholderView, meta: { title: '用户管理', permission: 'menu.users' } },
      { path: 'roles', component: PlaceholderView, meta: { title: '角色与权限', permission: 'menu.roles' } },
      { path: 'logs', component: PlaceholderView, meta: { title: '操作日志', permission: 'menu.logs' } },
      { path: 'shops', component: MasterDataView, props: { kind: 'shops' }, meta: { title: '店铺管理', permission: 'master.shops.view' } },
      { path: 'skus', component: MasterDataView, props: { kind: 'skus' }, meta: { title: 'SKU 中心', permission: 'master.skus.view' } },
      { path: 'suppliers', component: MasterDataView, props: { kind: 'suppliers' }, meta: { title: '供应商档案', permission: 'master.suppliers.view' } },
      { path: 'materials', component: MasterDataView, props: { kind: 'materials' }, meta: { title: '包材档案', permission: 'master.materials.view' } },
      { path: 'mappings', component: ShopSkuMappingView, meta: { title: '店铺 SKU 映射', permission: 'master.mappings.view' } },
      { path: 'imports', component: ImportCenterView, meta: { title: '导入中心', permission: 'menu.imports' } },
      { path: 'orders', component: OrdersView, meta: { title: '订单数据', permission: 'menu.imports' } },
      { path: 'exceptions', component: ExceptionsView, meta: { title: '异常中心', permission: 'menu.imports' } },
      { path: 'purchases', component: WarehouseView, props: { section: 'purchases' }, meta: { title: '采购管理', permission: 'menu.purchases' } },
      { path: 'inventory', component: WarehouseView, props: { section: 'inventory' }, meta: { title: '本地仓库存', permission: 'menu.inventory' } },
      { path: 'package-stocks', component: WarehouseView, props: { section: 'packages' }, meta: { title: '包材库存', permission: 'menu.package-stocks' } },
      { path: 'profits', component: ProfitView, meta: { title: 'SKU 利润报表', permission: 'menu.profits' } },
      { path: 'fulfillment', component: FulfillmentView, meta: { title: '本地发货履约', permission: 'menu.fulfillment' } },
    ] },
    { path: '/mobile', component: MobileLayout, meta: { permission: 'menu.mobile' }, children: [
      { path: '', component: MobileHomeView, meta: { title: '仓库工作台' } },
      { path: 'purchases', component: MobilePurchaseView, meta: { title: '采购处理' } },
      { path: 'inventory', component: MobileInventoryView, meta: { title: '商品出库与盘点' } },
      { path: 'packages', component: MobilePackageView, meta: { title: '包材出入库' } },
      { path: 'skus', component: MobileSkuView, meta: { title: 'SKU 查询' } },
      { path: 'fulfillment', component: MobileFulfillmentView, meta: { title: '发货拣货' } },
    ] },
  ],
})

router.beforeEach(async (to) => {
  if (to.meta.public) {
    if (getToken()) {
      try { const user=await api<AuthUser>('/api/auth/me'); return user.permissions.includes('menu.dashboard')?'/':user.permissions.includes('menu.mobile')?'/mobile':'/login' } catch { clearToken() }
    }
    return true
  }
  if (!getToken()) return { path: '/login', query: { redirect: to.fullPath } }
  try {
    const user = await api<AuthUser>('/api/auth/me')
    if (to.meta.permission && !user.permissions.includes(String(to.meta.permission))) return user.permissions.includes('menu.mobile')&&!to.path.startsWith('/mobile')?'/mobile':'/login'
    return true
  } catch { clearToken(); return '/login' }
})

export default router
