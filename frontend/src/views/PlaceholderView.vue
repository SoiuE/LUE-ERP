<template>
  <section v-if="route.path === '/'" class="welcome-page">
    <div class="welcome-heading"><div><div class="eyebrow">LUE COMMERCE ERP <span> / </span> {{ title }}</div><h1>{{ greeting }}，{{ user?.displayName || '欢迎使用' }}</h1><p>欢迎回到 LUE ERP 管理后台。基础账户与权限已就绪。</p></div><div class="date-pill"><span>◷</span> {{ today }}</div></div>
    <div class="welcome-banner"><div class="banner-copy"><div class="banner-label">YOUR WORKSPACE</div><h2>运营工作台已准备就绪</h2><p>登录身份、角色和菜单权限均由系统账户统一管理。</p></div><div class="banner-art"><div class="banner-ring ring-a"></div><div class="banner-ring ring-b"></div><div class="banner-glyph">L</div></div></div>
    <div class="quick-work"><router-link v-for="item in quickWork" :key="item.path" :to="item.path" class="quick-work-card"><strong>{{ item.title }}</strong><span>{{ item.description }}</span><b>进入 →</b></router-link></div>
    <div class="section-heading"><div><h3>账户概览</h3><p>当前登录账户的访问范围</p></div><span class="live-label"><i></i> 实时</span></div>
    <div class="overview-grid"><article class="overview-card"><div class="card-top"><span>当前角色</span><div class="metric-icon violet">♙</div></div><strong class="role-value">{{ roleNames }}</strong><small>由管理员分配的系统角色</small></article><article class="overview-card"><div class="card-top"><span>菜单权限</span><div class="metric-icon blue">▤</div></div><strong class="metric-number">{{ user?.permissions.filter(p => p.startsWith('menu.')).length || 0 }}</strong><small>可访问的后台菜单</small></article><article class="overview-card"><div class="card-top"><span>账户状态</span><div class="metric-icon green">✓</div></div><strong class="status-value">正常</strong><small>账户已启用，可正常使用</small></article></div>
    <div class="notice-row"><div class="notice-icon">✦</div><div><strong>基础权限框架已启用</strong><p>菜单按角色权限显示。后续业务模块将在对应开发阶段逐步加入。</p></div><span class="notice-tag">阶段 1</span></div>
  </section>
  <section v-else-if="route.path === '/users'" class="management-page">
    <div class="management-head"><div><div class="eyebrow">SYSTEM SETTINGS <span> / </span> USER ACCESS</div><h1>用户管理</h1><p>创建后台账户并为用户分配角色。</p></div><el-button v-if="canManageUsers" type="primary" @click="dialog = true">＋ 新建用户</el-button></div>
    <div class="table-card"><el-table :data="users" v-loading="loading" stripe><el-table-column prop="displayName" label="用户" min-width="150"><template #default="scope"><div class="table-user"><span class="avatar">{{ scope.row.displayName?.slice(0,1) }}</span><div><strong>{{ scope.row.displayName }}</strong><small>{{ scope.row.username }}</small></div></div></template></el-table-column><el-table-column label="角色" min-width="150"><template #default="scope">{{ scope.row.roleNames || '未分配' }} <small v-if="scope.row.roleCodes">({{ scope.row.roleCodes }})</small></template></el-table-column><el-table-column label="状态" width="90"><template #default="scope"><el-tag :type="scope.row.enabled ? 'success' : 'info'" effect="light" size="small">{{ scope.row.enabled ? '启用' : '禁用' }}</el-tag></template></el-table-column><el-table-column prop="createdAt" label="创建时间" min-width="180"><template #default="scope">{{ formatChinaDateTime(scope.row.createdAt) }}</template></el-table-column><el-table-column v-if="canManageUsers" label="操作" width="190" fixed="right"><template #default="scope"><el-button link type="primary" @click="openReset(scope.row)">重置密码</el-button><el-button link :type="scope.row.enabled ? 'danger' : 'success'" @click="toggleUser(scope.row)">{{ scope.row.enabled ? '禁用' : '启用' }}</el-button></template></el-table-column></el-table></div>
    <el-dialog v-model="dialog" title="新建用户" width="440px"><el-form label-position="top"><el-form-item label="姓名"><el-input v-model="form.displayName" placeholder="用户显示名称"/></el-form-item><el-form-item label="用户名"><el-input v-model="form.username" placeholder="用于登录"/></el-form-item><el-form-item label="初始密码"><el-input v-model="form.password" type="password" show-password placeholder="至少 8 位"/></el-form-item><el-form-item label="角色"><el-select v-model="form.roleCode" style="width:100%" :loading="rolesLoading" placeholder="选择角色"><el-option v-for="role in roles" :key="role.code" :label="`${role.name} (${role.code})`" :value="role.code"/></el-select></el-form-item></el-form><template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" :loading="saving" :disabled="!roles.length" @click="createUser">创建用户</el-button></template></el-dialog>
    <el-dialog v-model="resetDialog" title="重置用户密码" width="420px"><p>为 <strong>{{ selectedUser?.username }}</strong> 设置新密码。</p><el-input v-model="newPassword" type="password" show-password autocomplete="new-password" placeholder="请输入至少 8 位新密码"/><template #footer><el-button @click="resetDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="resetPassword">确认重置</el-button></template></el-dialog>
  </section>
  <section v-else-if="route.path === '/roles'" class="management-page"><div class="management-head"><div><div class="eyebrow">SYSTEM SETTINGS <span> / </span> ACCESS CONTROL</div><h1>角色与权限</h1><p>首版权限按菜单和按钮授予，不包含行级数据范围。</p></div></div><div class="role-grid"><article v-for="role in roles" :key="role.code" class="role-card"><div class="role-card-top"><div class="metric-icon violet">♙</div><el-tag effect="plain" size="small">{{ role.userCount }} 位用户</el-tag></div><h3>{{ role.name }}</h3><p>{{ role.code }}</p><div class="permission-chips"><el-tag v-for="permission in (role.permissions || '').split(',').filter(Boolean)" :key="permission" effect="light" size="small">{{ permission }}</el-tag></div></article></div></section>
  <section v-else class="management-page"><div class="management-head"><div><div class="eyebrow">SYSTEM SETTINGS <span> / </span> AUDIT TRAIL</div><h1>操作日志</h1><p>登录与退出事件已记录至 operation_logs，查询界面后续完善。</p></div></div><div class="notice-row"><div class="notice-icon">✓</div><div><strong>操作日志表已就绪</strong><p>记录用户、操作、对象、IP 地址、成功状态和发生时间。</p></div><span class="notice-tag">基础能力</span></div></section>
</template>
<style scoped>
.quick-work{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:10px;margin:18px 0 22px}.quick-work-card{display:flex;flex-direction:column;gap:7px;padding:15px;border:1px solid #eceef3;border-radius:10px;background:#fff;color:#303648;text-decoration:none;min-height:104px}.quick-work-card:hover{border-color:#bcb6f4;background:#fbfaff}.quick-work-card strong{font-size:14px}.quick-work-card span{font-size:12px;color:#8d93a2;line-height:1.5;flex:1}.quick-work-card b{font-size:12px;color:#6258d8}
</style>
<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import type { AuthUser } from '../api'
import { api } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { formatChinaDateTime } from '../utils/chinaTime'
const props = defineProps<{ user?: AuthUser }>()
const route = useRoute()
const quickWork = computed(() => [
  {title:'订单与导入',description:'查看订单和平台导入批次',path:'/orders',permission:'menu.imports'},
  {title:'本地履约',description:'拣货、短缺、交接与预约',path:'/fulfillment',permission:'menu.fulfillment'},
  {title:'采购与收货',description:'采购单、实收与上架作业',path:'/purchases',permission:'menu.purchases'},
  {title:'库位库存',description:'查库存分布、待上架和流水',path:'/location-inventory',permission:'warehouse.location_stock.view'},
  {title:'利润与结算',description:'订单日利润、结算和成本追溯',path:'/profits',permission:'menu.profits'},
].filter(item=>props.user?.permissions.includes(item.permission)))
const users = ref<any[]>([]); const roles = ref<any[]>([]); const loading = ref(false); const rolesLoading = ref(false); const dialog = ref(false); const saving = ref(false); const resetDialog = ref(false); const selectedUser = ref<any>(null); const newPassword = ref('')
const form = reactive({ username: '', displayName: '', password: '', roleCode: '' })
const canManageUsers = computed(() => props.user?.permissions.includes('button.users.manage') || false)
const title = computed(() => String(route.meta.title || '工作台'))
const roleNames = computed(() => props.user?.roles.map(role => ({ ADMIN: '系统管理员', OPERATOR: '运营', VIEWER: '只读用户' }[role] || role)).join('、') || '—')
const hour = Number(new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Shanghai', hour: 'numeric', hourCycle: 'h23' }).format(new Date()))
const greeting = hour < 11 ? '早上好' : hour < 18 ? '下午好' : '晚上好'
const today = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' }).format(new Date())
watch(() => route.path, async (path) => {
  if (path === '/users' || path === '/roles') {
    loading.value = true
    try { if (path === '/users') { users.value = await api<any[]>('/api/admin/users'); if (canManageUsers.value) { rolesLoading.value = true; roles.value = await api<any[]>('/api/admin/roles'); if (!roles.value.some(role => role.code === form.roleCode)) form.roleCode = roles.value[0]?.code || '' } } else roles.value = await api<any[]>('/api/admin/roles') }
    catch (error) { ElMessage.error(error instanceof Error ? error.message : '数据加载失败') }
    finally { loading.value = false; rolesLoading.value = false }
  }
}, { immediate: true })
async function createUser() {
  if (!form.username || !form.displayName || form.password.length < 8 || form.password.length > 100 || !roles.value.some(role => role.code === form.roleCode)) { ElMessage.warning('请完整填写信息，密码为 8 至 100 位并选择有效角色'); return }
  saving.value = true
  try { await api('/api/admin/users', { method: 'POST', body: JSON.stringify(form) }); dialog.value = false; users.value = await api<any[]>('/api/admin/users'); Object.assign(form, { username: '', displayName: '', password: '', roleCode: roles.value[0]?.code || '' }); ElMessage.success('用户已创建') }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '创建失败') }
  finally { saving.value = false }
}
function openReset(user: any) { selectedUser.value = user; newPassword.value = ''; resetDialog.value = true }
async function resetPassword() {
  if (newPassword.value.length < 8 || newPassword.value.length > 100) { ElMessage.warning('新密码长度须为 8 至 100 位'); return }
  saving.value = true
  try { await api(`/api/admin/users/${selectedUser.value.id}/reset-password`, { method: 'POST', body: JSON.stringify({ password: newPassword.value }) }); resetDialog.value = false; newPassword.value = ''; ElMessage.success('密码已重置') }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '重置失败') }
  finally { saving.value = false }
}
async function toggleUser(user: any) {
  const enabled = !user.enabled
  if (!enabled) { try { await ElMessageBox.confirm(`确定禁用用户“${user.username}”吗？禁用后该用户将无法继续使用系统。`, '确认禁用', { type: 'warning', confirmButtonText: '禁用', cancelButtonText: '取消' }) } catch { return } }
  try { await api(`/api/admin/users/${user.id}/status`, { method: 'POST', body: JSON.stringify({ enabled }) }); user.enabled = enabled; ElMessage.success(enabled ? '用户已启用' : '用户已禁用') }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '状态更新失败') }
}
</script>
