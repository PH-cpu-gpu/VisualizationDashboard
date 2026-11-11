import hCore from 'hui-core'

import WebWorker from './hui.worker.js'

// 最大长度不能超过 32 个字符
function generateSubscriberName() {
  const chars = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz'
  const maxLength = 24
  const uuid = []

  for (let i = 0; i < maxLength; i++) {
    uuid[i] = chars[0 | (Math.random() * chars.length)]
  }

  return uuid.join('')
}

class HSocket {
  /**
   *
   * @param {string} subscriber - 订阅者名字，要求全局唯一，持久化缓存，最大长度32
   * @param {string} url - 消息服务地址
   * @param {string} appData - 附加数据
   * @param {Boolean} freeMode - 自由模式，自由模式下，数据格式采用透传模式，不以操作员中心的配置为准
   */
  constructor(subscriber, url, appData, freeMode = false) {
    if (localStorage.hasOwnProperty(subscriber)) {
      this.subscriber = localStorage.getItem(subscriber)
    } else {
      const subscriberName = generateSubscriberName()
      localStorage.setItem(subscriber, subscriberName)
      this.subscriber = subscriberName
    }

    this.url = this.parseUrl(url)
    this.appData = appData
    this.freeMode = freeMode
    this.worker = new WebWorker()
    this.isSharedWorker = Boolean(this.worker.port)
    if (this.isSharedWorker) {
      this.worker.port.addEventListener('message', this.handleWorkerMessage)
      this.worker.port.start()
    } else {
      this.worker.addEventListener('message', this.handleWorkerMessage)
    }

    this.postMessage({ type: 'params', data: { freeMode: this.freeMode } })
  }

  makeParams(params = {}) {
    // assert params
    // ["messageType", "function", "topicName"].forEach((field) => {
    //   if (!params[field]) {
    //     throw `Param '$\{field\}' should not be undefined, null or empty string.`;
    //   }
    // });

    const { subscriber: subscriberName, appData } = this

    return {
      filter: {},
      replace: false,
      rebuild: false,
      returnFiled: '',
      bizCheck: {},
      subscriberName,
      appData,
      ...params,
    }
  }

  postMessage(msg) {
    if (this.worker) {
      this.isSharedWorker
        ? this.worker.port.postMessage(msg)
        : this.worker.postMessage(msg)
    }
  }

  connect() {
    this.postMessage({
      type: 'connect',
      data: {
        url: this.url,
        ioClientOptions: { reconnectionDelayMax: 120000 },
      },
    })
  }

  disconnect() {
    this.postMessage({ type: 'disconnect' })
  }

  addListenEvent(event) {
    this.postMessage({ type: 'add_listener', data: { event: event } })
  }

  destroy() {
    if (this.worker) {
      this.postMessage({ type: 'destroy' })
      this.worker.removeEventListener('message', this.handleWorkerMessage)
      this.worker = null
    }
  }

  subscribe(params = {}) {
    this.postMessage({
      type: 'subscribe',
      data: this.freeMode
        ? params
        : this.makeParams({
            /* 0是请求，1是应答 */
            messageType: 0,
            /* 620001订阅，620002取消订阅，620003推送 */
            function: '620001',
            ...params,
          }),
    })
  }

  unsubscribe(params = {}) {
    this.postMessage({
      type: 'unsubscribe',
      data: this.freeMode
        ? params
        : this.makeParams({
            /* 0是请求，1是应答 */
            messageType: 0,
            /* 620001订阅，620002取消订阅，620003推送 */
            function: '620002',
            ...params,
          }),
    })
  }

  handleWorkerMessage(e) {
    let { type, data } = e.data

    if (type === 'connect-success') {
      hCore.trigger('on-connect')
      return
    }

    if (type === 'connect-error') {
      hCore.trigger('on-connect-error')
      return
    }

    if (type === 'disconnect') {
      hCore.trigger('on-connect-closed')
      return
    }

    if (type === 'subscribe-ack') {
      data = data ? JSON.parse(data) : {}
      // todo: why ?
      if (data.appData === this.appData || !data.appData || !this.appData) {
        hCore.trigger('on-subscribe-ack', data)
      }
      return
    }

    if (type === 'unsubscribe-ack') {
      data = data ? JSON.parse(data) : {}
      // todo: why ?
      if (data.appData === this.appData || !data.appData || !this.appData) {
        hCore.trigger('on-cancel-subscribe-ack', data)
      }
      return
    }

    if (type === 'subscribe-recv') {
      if (data.appData === this.appData || !data.appData || !this.appData) {
        hCore.trigger('on-subscribe-recv', data)
      }
      return
    }

    // 发送自定义事件
    hCore.trigger(type, data)
  }

  parseUrl(url) {
    const hasProtocol = /^http:|https:|ws:|wss:/.test(url)
    if (hasProtocol) return url

    const { protocol, host } = window.location
    if (url.length > 2 && url.slice(0, 2) === '//') {
      return protocol + url
    }

    const hostUrl = host + '/' + url
    return protocol + '//' + hostUrl.replace('//', '/')
  }
}

export default HSocket
