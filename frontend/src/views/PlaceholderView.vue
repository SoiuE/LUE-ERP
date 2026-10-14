<template>
  <section v-if="route.path === '/'" class="welcome-page">
    <div class="welcome-heading"><div><div class="eyebrow">LUE COMMERCE ERP <span> / </span> {{ title }}</div><h1>{{ greeting }}，{{ user?.displayName || '欢迎使用' }}</h1><p>欢迎回到 LUE ERP 管理后台。基础账户与权限已就绪。</p></div><div class="date-pill"><span>◷</span> {{ today }}</div></div>
    <div class="welcome-banner"><div class="banner-copy"><div class="banner-label">YOUR WORKSPACE</div><h2>运营工作台已准备就绪</h2><p>登录身份、角色和菜单权限均由系统账户统一管理。</p></div><div class="banner-art"><div class="banner-ring ring-a"></div><div class="banner-ring ring-b"></div><div class="banner-glyph">L</div></div></div>
    <div class="section-heading"><div><h3>账户概览</h3><p>当前登录账户的访问范围</p></div><span class="live-label"><i></i> 实时</span></div>
    <div class="overview-grid"><article class="overview-card"><div class="card-top"><span>当前角色</span><div class="metric-icon violet">♙</div></div><strong class="role-value">{{ roleNames }}</strong><small>由管理员分配的系统角色</small></article><article class="overview-card"><div class="card-top"><span>菜单权限</span><div class="metric-icon blue">▤</div></div><strong class="metric-number">{{ user?.permissions.filter(p => p.startsWith('menu.')).length || 0 }}</strong><small>可访问的后台菜单</small></article><article class="overview-card"><div class="card-top"><span>账户状态</span><div class="metric-icon green">✓</div></div><strong class="status-value">正常</strong><small>账户已启用，可正常使用</small></article></div>
    <div class="notice-row"><div class="notice-icon">✦</div><div><strong>基础权限框架已启用</strong><p>菜单按角色权限显示。后续业务模块将在对应开发阶段逐步加入。</p></div><span class="notice-tag">阶段 1</span></div>
  </section>
  <section v-else-if="route.path === '/users'" class="management-page">
    <div class="management-head"><div><div class="eyebrow">SYSTEM SETTINGS <span> / </span> USER ACCESS</div><h1>用户管理</h1><p>创建后台账户并为用户分配角色。</p></div><el-button type="primary" @click="dialog = true">＋ 新建用户</el-button></div>
    <div class="table-card"><el-table :data="users" v-loading="loading" stripe><el-table-column prop="displayName" label="用户" min-width="150"><template #default="scope"><div class="table-user"><span class="avatar">{{ scope.row.displayName?.slice(0,1) }}</span><div><strong>{{ scope.row.displayName }}</strong><small>{{ scope.row.username }}</small></div></div></template></el-table-column><el-table-column prop="roleName" label="角色"/><el-table-column label="状态"><template #default="scope"><el-tag type="success" effect="light" size="small">{{ scope.row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column><el-table-column prop="createdAt" label="创建时间"/></el-table></div>
    <el-dialog v-model="dialog" title="新建用户" width="440px"><el-form label-position="top"><el-form-item label="姓名"><el-input v-model="form.displayName" placeholder="用户显示名称"/></el-form-item><el-form-item label="用户名"><el-input v-model="form.username" placeholder="用于登录"/></el-form-item><el-form-item label="初始密码"><el-input v-model="form.password" type="password" show-password placeholder="至少 8 位"/></el-form-item><el-form-item label="角色"><el-select v-model="form.roleCode" style="width:100%"><el-option label="系统管理员" value="ADMIN"/><el-option label="运营" value="OPERATOR"/><el-option label="只读用户" value="VIEWER"/></el-select></el-form-item></el-form><template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="createUser">创建用户</el-button></template></el-dialog>
  </section>
  <section v-else-if="route.path === '/roles'" class="management-page"><div class="management-head"><div><div class="eyebrow">SYSTEM SETTINGS <span> / </span> ACCESS CONTROL</div><h1>角色与权限</h1><p>首版权限按菜单和按钮授予，不包含行级数据范围。</p></div></div><div class="role-grid"><article v-for="role in roles" :key="role.code" class="role-card"><div class="role-card-top"><div class="metric-icon violet">♙</div><el-tag effect="plain" size="small">{{ role.userCount }} 位用户</el-tag></div><h3>{{ role.name }}</h3><p>{{ role.code }}</p><div class="permission-chips"><el-tag v-for="permission in (role.permissions || '').split(',').filter(Boolean)" :key="permission" effect="light" size="small">{{ permission }}</el-tag></div></article></div></section>
  <section v-else class="management-page"><div class="management-head"><div><div class="eyebrow">SYSTEM SETTINGS <span> / </span> AUDIT TRAIL</div><h1>操作日志</h1><p>登录与退出事件已记录至 operation_logs，查询界面后续完善。</p></div></div><div class="notice-row"><div class="notice-icon">✓</div><div><strong>操作日志表已就绪</strong><p>记录用户、操作、对象、IP 地址、成功状态和发生时间。</p></div><span class="notice-tag">基础能力</span></div></section>
</template>
<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import type { AuthUser } from '../api'
import { api } from '../api'
import { ElMessage } from 'element-plus'
const props = defineProps<{ user?: AuthUser }>()
const route = useRoute()
const users = ref<any[]>([]); const roles = ref<any[]>([]); const loading = ref(false); const dialog = ref(false); const saving = ref(false)
const form = reactive({ username: '', displayName: '', password: '', roleCode: 'OPERATOR' })
const title = computed(() => String(route.meta.title || '工作台'))
const roleNames = computed(() => props.user?.roles.map(role => ({ ADMIN: '系统管理员', OPERATOR: '运营', VIEWER: '只读用户' }[role] || role)).join('、') || '—')
const hour = new Date().getHours()
const greeting = hour < 11 ? '早上好' : hour < 18 ? '下午好' : '晚上好'
const today = new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' }).format(new Date())
watch(() => route.path, async (path) => {
  if (path === '/users' || path === '/roles') {
    loading.value = true
    try { if (path === '/users') users.value = await api<any[]>('/api/admin/users'); else roles.value = await api<any[]>('/api/admin/roles') }
    catch (error) { ElMessage.error(error instanceof Error ? error.message : '数据加载失败') }
    finally { loading.value = false }
  }
}, { immediate: true })
async function createUser() {
  if (!form.username || !form.displayName || form.password.length < 8) { ElMessage.warning('请完整填写信息，密码至少 8 位'); return }
  saving.value = true
  try { await api('/api/admin/users', { method: 'POST', body: JSON.stringify(form) }); dialog.value = false; users.value = await api<any[]>('/api/admin/users'); Object.assign(form, { username: '', displayName: '', password: '', roleCode: 'OPERATOR' }); ElMessage.success('用户已创建') }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '创建失败') }
  finally { saving.value = false }
}
</script>
