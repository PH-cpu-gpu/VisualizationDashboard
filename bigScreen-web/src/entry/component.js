export function registerGlobalComponents (vueObj, components) {
  let install = (Vue, opts = {}) => {
    Object.keys(components).forEach(key => {
      if (key.indexOf('$') >= 0) {
        Vue.prototype[key] = components[key]
      } else {
        Vue.component(key, components[key])
      }
    })
  }
  if (typeof window !== 'undefined' && window.Vue) {
    install(window.Vue)
  }
  vueObj.use(Object.assign({}, components, {install}))
}
