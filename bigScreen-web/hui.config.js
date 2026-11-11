const fs = require('fs-extra')
const { name, version } = require('./package.json')
const pathPrefix = "/apps/";

module.exports = {
    id: "6853c390-3e28-11f1-b1d0-fd942a643f77",
    type: "app",
    autoRouting: false,
    base: '/',
    publicPath: `${pathPrefix}${name}/`,
    runtimePublicPath: false,
    mode: 'hash',
    cssModules: false,
    plugins: [
        'hui-plugin-micro-app',
        'hui-plugin-sass',
        'hui-plugin-lint',
        'hui-plugin-requirejs',
        'hui-plugin-see',
        'hui-plugin-jsx',
        {
            plugin: "hui-plugin-wss",
            options: {
                isSharedWorker: false,
                ioClientOptions: {
                    reconnectionDelayMax: 1000 * 120
                }
            },
        }
    ],
    polyfill: {
        imports: ['whatwg-fetch', 'custom-event-polyfill'],
    },
    proxy: {
        '/daocloud-platform': {
            target: 'http://localhost:9090',
            changeOrigin: true,
            ws: true,
            secure: false,
            pathRewrite: {
                '^/daocloud-platform/bigScreen-server/v1': '/api'
            },
            onProxyReq: (proxyReq, req) => {
                console.log('[Proxy] 原始请求:', req.method, req.url)
                console.log('[Proxy] 目标地址:', proxyReq.path)
            },
            onProxyRes: (proxyRes, req) => {
                console.log('[Proxy] 响应状态:', proxyRes.statusCode, req.url)
            },
            onError: (err, req) => {
                console.error('[Proxy] 代理错误:', err.message, req.url)
            }
        }
    },
    see: {
        systemType: 'Daocloud-PlatForm',
        packageName: `${name}-web-${version}`,
        appType: `${name}-web`,
        variables: [
            {
                type: 'input',
                label: '服务GSV',
                tooltip: '',
                name: 'API_HOME',
                default: '/daocloud-platform/bigScreen-server/v1',
            },
            {
                type: 'input',
                label: '超时时长',
                tooltip: '',
                name: 'TIMEOUT',
                default: '300',
            }
        ]
    },
    vuex: true,
    filenameHashing: true
}
