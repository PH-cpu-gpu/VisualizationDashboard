import axios from 'axios'
import Vue from 'vue'

const defaultMessage = {
  error: () => {},
  success: () => {},
  warning: () => {},
  info: () => {}
}

let Message = defaultMessage
try {
  if (process.env.NODE_ENV !== 'test' && Vue.prototype.$hMessage) {
    Message = Vue.prototype.$hMessage
  }
} catch (e) {
  Message = defaultMessage
}

const getApiBaseUrl = () => {
  if (typeof window !== 'undefined' && window.LOCAL_CONFIG && window.LOCAL_CONFIG.API_HOME) {
    const apiHome = window.LOCAL_CONFIG.API_HOME
    return apiHome.endsWith('/') ? apiHome.slice(0, -1) : apiHome
  }
  const baseApi = process.env.VUE_APP_BASE_API || '/api'
  return baseApi.endsWith('/') ? baseApi.slice(0, -1) : baseApi
}

const getTimeout = () => {
  if (typeof window !== 'undefined' && window.LOCAL_CONFIG && window.LOCAL_CONFIG.TIMEOUT) {
    const timeout = window.LOCAL_CONFIG.TIMEOUT
    return timeout * 1000
  }
  const timeout = process.env.VUE_APP_TIMEOUT || 300
  return timeout * 1000
}

const service = axios.create({
  baseURL: getApiBaseUrl(),
  timeout: getTimeout()
})

service.interceptors.request.use(
  config => {
    console.log('[Axios Request]', config.method.toUpperCase(), config.url)
    return config
  },
  error => {
    console.error('请求错误:', error)
    return Promise.reject(error)
  }
)

service.interceptors.response.use(
  response => {
    const res = response.data
    // 后端 JsonResult.fail() 返回 success:false 但 code 为空，需单独识别，避免失败被静默当作成功
    if (res && res.success === false) {
      Message.error(res.msg || res.message || '请求失败')
      return Promise.reject(new Error(res.msg || res.message || '请求失败'))
    }
    // 统一映射后端 JsonResult 格式（errorCode / obj）为前端标准格式（code / data）
    const rawCode = res.code || res.errorCode
    // 将后端的 '0000' 统一标准化为前端的 '000000'
    const code = rawCode === '0000' ? '000000' : rawCode
    const data = res.data !== undefined ? res.data : res.obj
    if (code && code !== '000000') {
      Message.error(res.message || '请求失败')
      return Promise.reject(new Error(res.message || '请求失败'))
    }
    return { ...res, code: code || '000000', data }
  },
  error => {
    console.error('响应错误:', error)
    Message.error(error.message || '网络错误')
    return Promise.reject(error)
  }
)

export const rpc = {
  call(serviceName, params = {}) {
    const path = serviceName.startsWith('/') ? serviceName : '/' + serviceName
    return service.post(path, params).then(res => {
      return res
    })
  }
}

export default service
