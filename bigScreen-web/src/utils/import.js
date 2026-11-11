import Vue from 'vue'
import frameComponent from '@/components/index'
import HUI from 'h_ui'
import { registerGlobalComponents } from '@/entry/component'
import { rpc } from '@/utils/request'

Vue.use(HUI)
Vue.prototype.$rpc = rpc

Vue.config.productionTip = false

registerGlobalComponents(Vue, frameComponent)
