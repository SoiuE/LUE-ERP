import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'vant/lib/index.css'
import { Button, Card, Cell, CellGroup, Dialog, Divider, Empty, Field, Grid, GridItem, Icon, Loading, NavBar, NoticeBar, Popup, PullRefresh, Search, Stepper, Tab, Tabbar, TabbarItem, Tabs, Tag, Uploader } from 'vant'
import App from './App.vue'
import router from './router'
import './styles.css'

const app=createApp(App)
app.use(ElementPlus).use(router)
;[Button,Card,Cell,CellGroup,Dialog,Divider,Empty,Field,Grid,GridItem,Icon,Loading,NavBar,NoticeBar,Popup,PullRefresh,Search,Stepper,Tab,Tabbar,TabbarItem,Tabs,Tag,Uploader].forEach(component=>app.use(component))
app.mount('#app')
