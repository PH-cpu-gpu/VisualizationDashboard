<template>
  <div class="layout-container">
    <div v-if="isLocalDev" class="sidebar">
      <div class="logo">
        <h2>可视化大屏</h2>
      </div>
      <div class="menu">
        <div class="menu-item" :class="{ active: $route.path === '/topology' }" @click="navigateTo('/topology')">
          <span class="menu-icon">🌐</span>
          <span class="menu-title">拓扑监控</span>
        </div>
        <div class="menu-item" :class="{ active: $route.path === '/tomcat' }" @click="navigateTo('/tomcat')">
          <span class="menu-icon">📊</span>
          <span class="menu-title">Tomcat 监控</span>
        </div>
      </div>
    </div>
    <div class="main-content" :class="{ 'full-width': !isLocalDev }">
      <div v-if="isLocalDev" class="header">
        <div class="breadcrumb">
          <span>当前位置：{{ breadcrumbTitle }}</span>
        </div>
        <div class="user-info">
          <span>欢迎，{{ $store.state.user.username }}</span>
        </div>
      </div>
      <div class="content">
        <router-view />
      </div>
    </div>
  </div>
</template>

<script>
export default {
  name: 'Layout',
  computed: {
    isLocalDev() {
      return process.env.NODE_ENV === 'development'
    },
    breadcrumbTitle() {
      const titles = {
        '/topology': '拓扑监控',
        '/instances': '实例详情',
        '/tomcat': 'Tomcat 监控'
      }
      return titles[this.$route.path] || '可视化大屏'
    }
  },
  methods: {
    navigateTo(path) {
      console.log('[菜单导航] 尝试跳转到:', path)
      if (!this.$router) {
        console.error('[菜单导航] $router 不存在')
        return
      }
      this.$router.push(path).catch(err => {
        if (err.name === 'NavigationDuplicated') {
          console.warn('[菜单导航] 重复导航:', path)
        } else {
          console.error('[菜单导航] 导航失败:', err)
        }
      })
    }
  }
}
</script>

<style lang="scss" scoped>
.layout-container {
  display: flex;
  height: 100%;
  .sidebar {
    width: 220px;
    background-color: #304156;
    color: #fff;
    .logo {
      height: 60px;
      display: flex;
      align-items: center;
      justify-content: center;
      border-bottom: 1px solid #1f2d3d;
      h2 {
        margin: 0;
        font-size: 18px;
        color: #fff;
      }
    }
    .menu {
      padding: 10px 0;
      .menu-item {
        padding: 15px 20px;
        cursor: pointer;
        transition: all 0.3s;
        display: flex;
        align-items: center;
        &:hover {
          background-color: #263445;
        }
        &.active {
          background-color: #1890ff;
        }
        .menu-icon {
          margin-right: 10px;
          font-size: 16px;
        }
        .menu-title {
          font-size: 14px;
        }
      }
    }
  }
  .main-content {
    flex: 1;
    display: flex;
    flex-direction: column;
    background-color: #f0f2f5;
    &.full-width {
      width: 100%;
    }
    .header {
      height: 60px;
      background-color: #fff;
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 0 20px;
      box-shadow: 0 1px 4px rgba(0, 21, 41, 0.08);
      .breadcrumb {
        font-size: 14px;
        color: #606266;
      }
      .user-info {
        font-size: 14px;
        color: #606266;
      }
    }
    .content {
      flex: 1;
      overflow: auto;
      background-color: #fff;
      position: relative;
    }
  }
}
</style>
