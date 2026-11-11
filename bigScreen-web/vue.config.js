const path = require('path')

module.exports = {
  publicPath: '/',
  outputDir: 'dist',
  lintOnSave: false,
  productionSourceMap: false,
  devServer: {
    port: 8080,
    proxy: {
      '/daocloud-platform': {
        target: 'http://localhost:9090',
        changeOrigin: true,
        pathRewrite: {
          '^/daocloud-platform/bigScreen-server/v1': '/api'
        }
      }
    }
  },
  configureWebpack: {
    resolve: {
      alias: {
        '@': path.resolve(__dirname, 'src'),
        'hcreator$': path.resolve(__dirname, 'node_modules/hcreator/dist/hclib-replace.4ddd7e74.umd.js')
      }
    }
  },
  css: {
    loaderOptions: {
      scss: {
        prependData: `
          @import "@/styles/variables.scss";
          @import "@/styles/mixins.scss";
        `
      }
    }
  }
}
