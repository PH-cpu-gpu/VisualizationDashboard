<template>
  <div class="instance-page">
    <!-- 顶部栏 -->
    <div class="instance-header">
      <div class="instance-back" @click="goBack">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M19 12H5M12 19l-7-7 7-7"/>
        </svg>
        返回拓扑图
      </div>
      <div class="instance-header-title">{{ currentNodeName }} - 实例详情</div>
      <div class="instance-header-clock">{{ currentTime }}</div>
    </div>

    <div class="instance-body">
      <!-- 左侧：节点与实例选择 -->
      <div class="instance-sidebar">
        <div class="instance-select-group">
          <label>选择节点</label>
          <select class="instance-select" v-model="selectedNodeId" @change="onNodeChange">
            <option v-for="n in nodes" :key="n.id" :value="n.id">{{ n.nodeName }}</option>
          </select>
        </div>
        <div class="instance-select-group">
          <label>选择实例</label>
          <select class="instance-select" v-model="selectedIp" @change="loadInstanceMetrics">
            <option v-if="instanceIps.length === 0" value="">默认实例</option>
            <option v-for="ip in instanceIps" :key="ip" :value="ip">{{ ip }}</option>
          </select>
        </div>
        <div class="instance-hint">
          <span v-if="instanceIps.length > 0">共 {{ instanceIps.length }} 个实例</span>
          <span v-else>该节点暂无实例IP数据</span>
        </div>
        <!-- 同机多应用共享提示：Tomcat 指标为机器维度，无法归属到具体应用 -->
        <div v-if="sharedMachineTip" class="instance-shared-tip">{{ sharedMachineTip }}</div>
        <!-- 实例快捷列表 -->
        <div class="instance-list" v-if="instanceIps.length > 0">
          <div v-for="ip in instanceIps" :key="ip"
               class="instance-list-item"
               :class="{ active: selectedIp === ip }"
               @click="onSelectInstance(ip)">
            <span class="instance-status-dot"></span>
            <span class="instance-list-ip">{{ ip }}</span>
            <span v-if="instanceAlertCount(ip) > 0" class="instance-list-alert">{{ instanceAlertCount(ip) }}</span>
          </div>
        </div>
      </div>

      <!-- 右侧：实例指标 -->
      <div class="instance-main">
        <!-- 当前实例的活跃告警（告警记录携带 serverIp 时展示） -->
        <div v-if="instanceAlerts.length > 0" class="instance-alert-banner">
          <div v-for="a in instanceAlerts" :key="a.id" class="instance-alert-line" :class="a.alertLevel">
            <span class="ial-icon">⚠</span>
            <span class="ial-metric">{{ a.metricName }} {{ a.operator }} {{ a.thresholdValue }}</span>
            <span class="ial-value">{{ a.metricValue }}</span>
            <span class="ial-time">{{ (a.alertTime || '').slice(11) }} 触发</span>
          </div>
        </div>
        <div v-if="loading" class="instance-loading">加载中...</div>
        <div v-else-if="metrics.length === 0" class="instance-empty">
          暂无实例指标数据
          <div class="instance-empty-sub">请确认该实例是否已上报采集数据</div>
        </div>
        <template v-else>
          <div class="instance-main-title">
            <span class="instance-main-name">{{ selectedIp || '默认实例' }}</span>
            <span class="instance-main-count">指标数: {{ metrics.length }}</span>
          </div>
          <div class="instance-metric-grid">
            <div v-for="(m, idx) in metrics" :key="idx" class="instance-metric-card">
              <div class="imc-label">{{ m.metricName }}</div>
              <div class="imc-value" :class="metricValueClass(m.metricValue)">
                {{ m.metricValue }}<span class="imc-unit" v-if="m.metricUnit">{{ m.metricUnit }}</span>
              </div>
            </div>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script>
import service from '@/utils/request'

export default {
  name: 'InstancePage',
  data() {
    return {
      nodes: [],
      selectedNodeId: '',
      instanceIps: [],
      selectedIp: '',
      metrics: [],
      allActiveAlerts: [],
      loading: false,
      currentTime: '',
      _initialServerIp: '',
      _refreshTimer: null,
      _clockTimer: null
    }
  },
  computed: {
    currentNodeName() {
      const node = this.nodes.find(n => n.id === this.selectedNodeId)
      return node ? node.nodeName : '节点'
    },
    // 当前所选实例（无实例IP时为节点汇总维度）的活跃告警
    instanceAlerts() {
      if (!this.selectedNodeId) return []
      const currentIp = this.selectedIp || ''
      return (this.allActiveAlerts || []).filter(a =>
        a.targetType === 'node' &&
        a.targetId === this.selectedNodeId &&
        (a.serverIp || '') === currentIp
      )
    },
    // 同机多应用共享提示：节点描述含“同机”时展示，避免把机器级指标误读为应用级
    sharedMachineTip() {
      const node = this.nodes.find(n => n.id === this.selectedNodeId)
      const desc = node && node.description ? node.description : ''
      return desc.includes('同机') ? desc : ''
    }
  },
  async mounted() {
    this.updateClock()
    this._clockTimer = setInterval(this.updateClock, 1000)
    // 从告警记录跳转时携带的实例IP，优先选中
    this._initialServerIp = this.$route.query.serverIp || ''
    await this.loadNodes()
    await this.loadActiveAlerts()
    // 后端采集周期为2分钟，轮询周期与之对齐，与拓扑页保持一致
    this._refreshTimer = setInterval(() => {
      if (this.selectedNodeId) {
        this.loadInstanceMetrics()
        this.loadActiveAlerts()
      }
    }, 120000)
  },
  beforeDestroy() {
    if (this._refreshTimer) clearInterval(this._refreshTimer)
    if (this._clockTimer) clearInterval(this._clockTimer)
  },
  methods: {
    async loadNodes() {
      try {
        const res = await service.get('/monitor/topology/nodes')
        this.nodes = res.data || []
        const queryNodeId = this.$route.query.nodeId
        if (queryNodeId && this.nodes.some(n => n.id === queryNodeId)) {
          this.selectedNodeId = queryNodeId
        } else if (this.nodes.length > 0) {
          this.selectedNodeId = this.nodes[0].id
        }
        if (this.selectedNodeId) {
          await this.onNodeChange()
        }
      } catch (e) {
        this.nodes = []
      }
    },

    // 切换节点：刷新实例下拉与指标
    async onNodeChange() {
      this.selectedIp = ''
      this.instanceIps = []
      this.metrics = []
      await this.loadInstanceIps()
      await this.loadInstanceMetrics()
    },

    // 查询该节点下的实例 IP：实例 IP 是节点属性，直接从节点列表拆分；
    // 节点未配置 IP 时回落到指标表中已出现过的 server_ip
    async loadInstanceIps() {
      if (!this.selectedNodeId) return
      const node = this.nodes.find(n => n.id === this.selectedNodeId)
      let ips = []
      if (node && node.bindType !== 'LOG_FILTER' && node.instanceIps) {
        ips = node.instanceIps.split(',').map(s => s.trim()).filter(Boolean)
      }
      if (ips.length === 0) {
        try {
          const res = await service.get('/monitor/display/nodeMetrics/' + this.selectedNodeId + '/instances')
          ips = res.data || []
        } catch (e) {
          ips = []
        }
      }
      this.instanceIps = ips.filter((ip, idx) => ips.indexOf(ip) === idx)
      // 告警跳转指定的实例优先；否则默认第一个实例；无实例IP时不传 serverIp 查询汇总数据
      const initialIp = this._initialServerIp
      this._initialServerIp = ''
      this.selectedIp = initialIp && this.instanceIps.includes(initialIp)
        ? initialIp
        : (this.instanceIps.length > 0 ? this.instanceIps[0] : '')
    },

    // 查询指定实例的最新指标
    async loadInstanceMetrics() {
      if (!this.selectedNodeId) return
      this.loading = true
      try {
        const params = {}
        if (this.selectedIp) params.serverIp = this.selectedIp
        const res = await service.get('/monitor/display/nodeMetrics/' + this.selectedNodeId, { params })
        this.metrics = (res.data || []).filter(m => m.metricValue !== null && m.metricValue !== '')
      } catch (e) {
        this.metrics = []
      } finally {
        this.loading = false
      }
    },

    onSelectInstance(ip) {
      if (this.selectedIp === ip) return
      this.selectedIp = ip
      this.loadInstanceMetrics()
    },

    // 查询所有活跃告警，实例维度记录携带 serverIp
    async loadActiveAlerts() {
      try {
        const res = await service.get('/monitor/alert/active')
        this.allActiveAlerts = res.data || []
      } catch (e) {
        this.allActiveAlerts = []
      }
    },

    // 实例列表角标：该实例的活跃告警数
    instanceAlertCount(ip) {
      return (this.allActiveAlerts || []).filter(a =>
        a.targetType === 'node' && a.targetId === this.selectedNodeId && a.serverIp === ip
      ).length
    },

    // 数值型指标按阈值着色，文本型指标保持默认色
    metricValueClass(value) {
      if (value === null || value === undefined) return ''
      const str = String(value)
      if (!/^-?\d+(\.\d+)?%?$/.test(str)) return ''
      const num = parseFloat(str)
      if (str.includes('%')) {
        if (num > 80) return 'danger'
        if (num > 60) return 'warn'
      }
      return ''
    },

    goBack() {
      this.$router.push({ name: 'TopologyPage' }).catch(() => {})
    },

    updateClock() {
      const now = new Date()
      const pad = n => String(n).padStart(2, '0')
      this.currentTime = pad(now.getHours()) + ':' + pad(now.getMinutes()) + ':' + pad(now.getSeconds())
    }
  }
}
</script>

<style scoped>
.instance-page {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
  background-color: #0a0e1a;
  overflow: hidden;
  position: fixed;
  top: 0;
  left: 0;
  width: 100vw;
  height: 100vh;
  font-family: "Microsoft YaHei", "PingFang SC", sans-serif;
  color: #e0e6ed;
  display: flex;
  flex-direction: column;
  z-index: 100;
}

/* 顶部栏 */
.instance-header {
  display: flex;
  align-items: center;
  padding: 16px 24px;
  border-bottom: 1px solid rgba(77, 171, 247, 0.15);
  gap: 16px;
  flex-shrink: 0;
  background: rgba(10, 14, 30, 0.6);
}
.instance-back {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  font-size: 13px;
  border-radius: 4px;
  cursor: pointer;
  color: rgba(224, 230, 237, 0.7);
  background: rgba(77, 171, 247, 0.08);
  border: 1px solid rgba(77, 171, 247, 0.2);
  transition: all 0.2s;
}
.instance-back:hover {
  color: #4dabf7;
  border-color: #4dabf7;
}
.instance-header-title {
  font-size: 18px;
  font-weight: 600;
  color: #4dabf7;
  text-shadow: 0 0 16px rgba(77, 171, 247, 0.4);
}
.instance-header-clock {
  margin-left: auto;
  font-family: 'Consolas', monospace;
  font-size: 13px;
  color: rgba(77, 171, 247, 0.6);
}

/* 主体 */
.instance-body {
  flex: 1;
  display: flex;
  overflow: hidden;
  background-image:
    linear-gradient(rgba(77, 171, 247, 0.06) 1px, transparent 1px),
    linear-gradient(90deg, rgba(77, 171, 247, 0.06) 1px, transparent 1px);
  background-size: 50px 50px;
}

/* 左侧栏 */
.instance-sidebar {
  width: 280px;
  border-right: 1px solid rgba(77, 171, 247, 0.12);
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  flex-shrink: 0;
  overflow-y: auto;
}
.instance-select-group label {
  display: block;
  font-size: 12px;
  color: rgba(224, 230, 237, 0.5);
  margin-bottom: 6px;
}
.instance-select {
  width: 100%;
  padding: 8px 12px;
  font-size: 13px;
  border-radius: 4px;
  background: rgba(10, 14, 30, 0.9);
  border: 1px solid rgba(77, 171, 247, 0.3);
  color: #e0e6ed;
  outline: none;
  cursor: pointer;
}
.instance-select:focus {
  border-color: #4dabf7;
  box-shadow: 0 0 8px rgba(77, 171, 247, 0.2);
}
.instance-select option {
  background: #0a0e1a;
  color: #e0e6ed;
}
.instance-hint {
  font-size: 12px;
  color: rgba(224, 230, 237, 0.4);
}

/* 同机多应用共享提示（Tomcat 指标为机器维度） */
.instance-shared-tip {
  margin-top: 10px;
  padding: 8px 10px;
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: #ffd43b;
  background: rgba(255, 212, 59, 0.08);
  border: 1px solid rgba(255, 212, 59, 0.3);
}

/* 实例快捷列表 */
.instance-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.instance-list-item {
  padding: 10px 12px;
  border-radius: 6px;
  cursor: pointer;
  border: 1px solid rgba(77, 171, 247, 0.1);
  transition: all 0.2s;
  display: flex;
  align-items: center;
  gap: 10px;
}
.instance-list-item:hover {
  border-color: rgba(77, 171, 247, 0.4);
  background: rgba(77, 171, 247, 0.06);
}
.instance-list-item.active {
  border-color: #4dabf7;
  background: rgba(77, 171, 247, 0.1);
}
.instance-status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  background: #51cf66;
  box-shadow: 0 0 6px rgba(81, 207, 102, 0.5);
}
.instance-list-ip {
  font-size: 13px;
  font-family: 'Consolas', monospace;
  color: #e0e6ed;
}
.instance-list-alert {
  margin-left: auto;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 8px;
  background: rgba(255, 60, 60, 0.18);
  color: #ff6b6b;
  font-size: 10px;
  font-weight: bold;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* 实例活跃告警横幅 */
.instance-alert-banner {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 18px;
}
.instance-alert-line {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  font-size: 12px;
  border-radius: 6px;
  border-left: 3px solid transparent;
}
.instance-alert-line.critical {
  background: rgba(255, 60, 60, 0.08);
  border-left-color: #ff3c3c;
  color: #ff9b9b;
}
.instance-alert-line.warning {
  background: rgba(255, 200, 0, 0.08);
  border-left-color: #ffc800;
  color: #ffd75e;
}
.instance-alert-line .ial-icon {
  font-size: 13px;
}
.instance-alert-line .ial-metric {
  color: rgba(224, 230, 237, 0.78);
}
.instance-alert-line .ial-value {
  font-family: 'Consolas', monospace;
  font-weight: bold;
}
.instance-alert-line .ial-time {
  margin-left: auto;
  font-size: 10px;
  color: rgba(224, 230, 237, 0.35);
}

/* 右侧内容 */
.instance-main {
  flex: 1;
  padding: 24px;
  overflow-y: auto;
}
.instance-loading,
.instance-empty {
  color: #adb5bd;
  text-align: center;
  padding: 60px 0;
  font-size: 13px;
}
.instance-empty-sub {
  margin-top: 8px;
  font-size: 12px;
  color: rgba(224, 230, 237, 0.3);
}
.instance-main-title {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
}
.instance-main-name {
  font-size: 20px;
  font-weight: 600;
  color: #e0e6ed;
  font-family: 'Consolas', monospace;
}
.instance-main-count {
  font-size: 12px;
  color: rgba(224, 230, 237, 0.4);
  padding: 3px 10px;
  border-radius: 10px;
  background: rgba(77, 171, 247, 0.1);
  border: 1px solid rgba(77, 171, 247, 0.2);
}

/* 指标卡片 */
.instance-metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
}
.instance-metric-card {
  background: rgba(77, 171, 247, 0.04);
  border: 1px solid rgba(77, 171, 247, 0.1);
  border-radius: 8px;
  padding: 14px 16px;
  transition: all 0.2s;
}
.instance-metric-card:hover {
  border-color: rgba(77, 171, 247, 0.4);
  background: rgba(77, 171, 247, 0.08);
}
.imc-label {
  font-size: 11px;
  color: rgba(224, 230, 237, 0.5);
  margin-bottom: 6px;
}
.imc-value {
  font-size: 22px;
  font-weight: 700;
  font-family: 'Consolas', monospace;
  color: #4dabf7;
  word-break: break-all;
}
.imc-value.warn {
  color: #ffc107;
}
.imc-value.danger {
  color: #ff6b6b;
}
.imc-unit {
  font-size: 11px;
  color: rgba(224, 230, 237, 0.4);
  margin-left: 4px;
  font-weight: 400;
}

/* 滚动条 */
.instance-page ::-webkit-scrollbar {
  width: 4px;
}
.instance-page ::-webkit-scrollbar-track {
  background: transparent;
}
.instance-page ::-webkit-scrollbar-thumb {
  background: rgba(77, 171, 247, 0.3);
  border-radius: 2px;
}
</style>
