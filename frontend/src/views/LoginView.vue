<template>
  <main class="login-page">
    <section class="login-card">
      <div class="brand-mark">L</div>
      <div class="eyebrow">LUE COMMERCE SYSTEM</div>
      <h1>欢迎回来</h1>
      <p class="login-subtitle">登录以进入企业运营后台</p>
      <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="submit">
        <el-form-item prop="username"><el-input v-model="form.username" placeholder="用户名" autocomplete="username"><template #prefix><el-icon><User /></el-icon></template></el-input></el-form-item>
        <el-form-item prop="password"><el-input v-model="form.password" type="password" placeholder="密码" show-password autocomplete="current-password"><template #prefix><el-icon><Lock /></el-icon></template></el-input></el-form-item>
        <p v-if="error" class="form-error">{{ error }}</p>
        <el-button class="login-button" type="primary" :loading="loading" @click="submit">登录后台 <span>→</span></el-button>
      </el-form>
      <div class="login-foot"><span class="secure-dot"></span>安全连接 · LUE ERP</div>
    </section>
    <aside class="login-art">
      <div class="art-orbit orbit-one"></div><div class="art-orbit orbit-two"></div>
      <div class="art-content"><div class="art-kicker">OPERATIONS, IN FOCUS</div><h2>让每一项经营<br>决策都有据可依。</h2><p>面向跨境电商团队的一体化运营工作台</p><div class="art-footer">LUE <span>·</span> COMMERCE ERP</div></div>
      <div class="art-card"><div class="art-card-icon">↗</div><div><strong>运营一览</strong><small>数据清晰，工作有序</small></div><div class="art-bars"><i></i><i></i><i></i><i></i><i></i></div></div>
    </aside>
  </main>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'
import { api, saveToken, type AuthUser } from '../api'

const formRef = ref<FormInstance>()
const form = reactive({ username: '', password: '' })
const rules: FormRules = { username: [{ required: true, message: '请输入用户名', trigger: 'blur' }], password: [{ required: true, message: '请输入密码', trigger: 'blur' }] }
const loading = ref(false)
const error = ref('')
const router = useRouter()
const route = useRoute()

async function submit() {
  if (!formRef.value || loading.value) return
  error.value = ''
  try {
    await formRef.value.validate()
    loading.value = true
    const result = await api<{ token: string; user: AuthUser }>('/api/auth/login', { method: 'POST', body: JSON.stringify(form) })
    saveToken(result.token)
    await router.replace(String(route.query.redirect || '/'))
  } catch (err) { error.value = err instanceof Error ? err.message : '登录失败，请重试' }
  finally { loading.value = false }
}
</script>
