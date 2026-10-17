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
import GoodsProcurementView from './views/GoodsProcurementView.vue'
import MobileLayout from './views/mobile/MobileLayout.vue'
import MobileHomeView from './views/mobile/MobileHomeView.vue'
import MobilePackageView from './views/mobile/MobilePackageView.vue'
import ProfitView from './views/ProfitView.vue'
import FulfillmentView from './views/FulfillmentView.vue'
import MobileFulfillmentView from './views/mobile/MobileFulfillmentView.vue'
import MobileGoodsTasksView from './views/mobile/MobileGoodsTasksView.vue'
import WarehouseGoodsView from './views/WarehouseGoodsView.vue'
import SkuProductDetailsView from './views/SkuProductDetailsView.vue'
import WarehouseLocationsView from './views/WarehouseLocationsView.vue'
import ImageLibraryView from './views/ImageLibraryView.vue'
import ImageSearchView from './views/ImageSearchView.vue'

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
      { path: 'skus/:skuId/details', component: SkuProductDetailsView, meta: { title: '销售 SKU 详情', permission: 'master.skus.view' } },
      { path: 'warehouse-goods', component: WarehouseGoodsView, meta: { title: '仓库货品', permission: 'master.goods.view' } },
      { path: 'images', component: ImageLibraryView, meta: { title: '图片库', permission: 'menu.images' } },
      { path: 'image-search', component: ImageSearchView, meta: { title: '以图搜商品', permission: 'menu.image-search' } },
      { path: 'warehouse-locations', component: WarehouseLocationsView, meta: { title: '仓库库位', permission: 'warehouse.locations.view' } },
      { path: 'location-inventory', component: WarehouseLocationsView, props: { initialTab: 'stock' }, meta: { title: '货品库位库存', permission: 'warehouse.location_stock.view' } },
      { path: 'suppliers', component: MasterDataView, props: { kind: 'suppliers' }, meta: { title: '供应商档案', permission: 'master.suppliers.view' } },
      { path: 'materials', component: MasterDataView, props: { kind: 'materials' }, meta: { title: '包材档案', permission: 'master.materials.view' } },
      { path: 'mappings', component: ShopSkuMappingView, meta: { title: '店铺 SKU 映射', permission: 'master.mappings.view' } },
      { path: 'imports', component: ImportCenterView, meta: { title: '导入中心', permission: 'menu.imports' } },
      { path: 'orders', component: OrdersView, meta: { title: '订单数据', permission: 'menu.imports' } },
      { path: 'exceptions', component: ExceptionsView, meta: { title: '异常中心', permission: 'menu.imports' } },
      { path: 'purchases', component: GoodsProcurementView, meta: { title: '货品采购与库存作业', permission: 'menu.purchases' } },
      { path: 'package-stocks', component: () => import('./views/WarehouseView.vue'), props: { section: 'packages' }, meta: { title: '包材库存', permission: 'menu.package-stocks' } },
      { path: 'profits', component: ProfitView, meta: { title: 'SKU 利润报表', permission: 'menu.profits' } },
      { path: 'fulfillment', component: FulfillmentView, meta: { title: '本地发货履约', permission: 'menu.fulfillment' } },
    ] },
    { path: '/mobile', component: MobileLayout, meta: { permissions: ['menu.mobile'] }, children: [
      { path: '', component: MobileHomeView, meta: { title: '仓库工作台', permissions: ['menu.mobile'] } },
      { path: 'goods-tasks', component: MobileGoodsTasksView, meta: { title: '收货与库位任务', permissions: ['menu.mobile'] } },
      { path: 'packages', component: MobilePackageView, meta: { title: '包材出入库', permissions: ['menu.mobile'] } },
      { path: 'fulfillment', component: MobileFulfillmentView, meta: { title: '发货拣货', permissions: ['menu.mobile','menu.fulfillment'] } },
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
    const required = (to.meta.permissions as string[]|undefined) || (to.meta.permission ? [String(to.meta.permission)] : [])
    if (required.some(permission=>!user.permissions.includes(permission))) return user.permissions.includes('menu.dashboard')?'/':user.permissions.includes('menu.mobile')?'/mobile':'/login'
    return true
  } catch { clearToken(); return '/login' }
})

export default router
