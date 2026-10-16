<template>
  <div class="mobile-shell">
    <van-nav-bar title="LUE 仓库作业" left-text="工作台" left-arrow @click-left="goHome"><template #right><button class="mobile-logout" @click="logout">{{ user?.displayName || user?.username }} · 退出</button></template></van-nav-bar>
    <main class="mobile-content"><router-view /></main>
    <van-tabbar :model-value="activeTab" safe-area-inset-bottom>
      <van-tabbar-item name="/mobile" icon="wap-home-o" @click="navigate('/mobile')">工作台</van-tabbar-item>
      <van-tabbar-item name="/mobile/goods-tasks" icon="orders-o" @click="navigate('/mobile/goods-tasks?task=receipt')">收货</van-tabbar-item>
      <van-tabbar-item v-if="user?.permissions.includes('menu.fulfillment')" name="/mobile/fulfillment" icon="logistics" @click="navigate('/mobile/fulfillment')">履约</van-tabbar-item>
    </van-tabbar>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, provide, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showConfirmDialog } from 'vant'
import { api, clearToken, type AuthUser } from '../../api'

const route=useRoute(),router=useRouter(),user=ref<AuthUser>()
provide('mobileAuthUser',user)
const activeTab=computed(()=>route.path==='/mobile'||route.path==='/mobile/'?'/mobile':route.path.startsWith('/mobile/goods-tasks')?'/mobile/goods-tasks':route.path.startsWith('/mobile/fulfillment')?'/mobile/fulfillment':'/mobile')
onMounted(async()=>{try{user.value=await api<AuthUser>('/api/auth/me');if(!user.value.permissions.includes('menu.mobile'))await router.replace('/login')}catch{await router.replace('/login')}})
function navigate(path:string){if(route.fullPath!==path)router.push(path)}
function goHome(){if(route.path!=='/mobile')router.push('/mobile')}
async function logout(){try{await showConfirmDialog({title:'退出登录',message:'确认结束当前仓库操作会话？'})}catch{return}try{await api('/api/auth/logout',{method:'POST'})}catch{}clearToken();await router.replace('/login')}
</script>

<style>
.mobile-shell{min-height:100svh;min-height:100dvh;background:#f5f7fa;padding-bottom:calc(62px + env(safe-area-inset-bottom));max-width:640px;margin:0 auto;overscroll-behavior-y:contain}.mobile-shell .van-nav-bar{position:sticky;top:0;z-index:10}.mobile-logout{border:0;background:transparent;font-size:12px;color:#747b88;max-width:150px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.mobile-content{padding:14px 14px 24px;scroll-padding-bottom:calc(92px + env(safe-area-inset-bottom))}.mobile-page-title{margin:4px 2px 14px}.mobile-page-title h1{font-size:22px;margin:0 0 5px;color:#202637}.mobile-page-title p{font-size:13px;margin:0;color:#8c93a1}.mobile-section{margin-bottom:14px}.mobile-card{border-radius:12px;margin-bottom:12px;overflow:hidden}.mobile-action-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.mobile-action{background:#fff;border:0;border-radius:12px;padding:17px 14px;text-align:left;min-height:82px;box-shadow:0 2px 12px #2026370a;color:#293044}.mobile-action strong,.mobile-action span{display:block}.mobile-action strong{font-size:15px}.mobile-action span{font-size:12px;color:#9097a5;margin-top:6px}.mobile-form-actions{display:grid;grid-template-columns:1fr;gap:10px;margin-top:15px}.mobile-content>.mobile-form-actions{position:sticky;bottom:calc(58px + env(safe-area-inset-bottom));z-index:5;padding:8px 0;background:#f5f7faeF}.mobile-form-actions .van-button{height:48px;border-radius:9px}.mobile-field-note{font-size:12px;color:#8f96a4;line-height:1.6;padding:8px 4px}.mobile-qty-row{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:12px 2px;border-bottom:1px solid #f0f1f4}.mobile-qty-row:last-child{border-bottom:0}.mobile-qty-row strong,.mobile-qty-row small{display:block}.mobile-qty-row strong{font-size:14px}.mobile-qty-row small{font-size:12px;color:#9399a6;margin-top:4px}.mobile-result-card{background:#fff;border-radius:12px;padding:14px;margin:10px 0}.mobile-sku-title{font-weight:650;color:#2c3242}.mobile-sku-meta{font-size:12px;color:#9097a5;margin-top:5px}.mobile-camera-video{width:100%;max-height:64vh;background:#111}.mobile-empty-space{padding:22px 0}
/* iOS Safari zooms the page when a focused form control uses text smaller than 16px. */
.mobile-shell .van-field__control,.mobile-shell .van-search__field,.mobile-shell input,.mobile-shell textarea,.mobile-shell select{font-size:16px!important;}
.mobile-content .mobile-form-actions{position:sticky;bottom:calc(58px + env(safe-area-inset-bottom));z-index:5;padding:8px 0;background:#f5f7faef}
</style>
