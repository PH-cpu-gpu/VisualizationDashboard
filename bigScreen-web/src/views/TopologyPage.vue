<template>
  <div class="topology-page">
    <!-- 大屏标题栏：顶部悬浮，不拦截鼠标事件，避免影响画布拖拽 -->
    <header class="screen-header">
      <div class="screen-clock">
        <span class="screen-clock-date">{{ clockDate }}</span>
        <span class="screen-clock-time">{{ clockTime }}</span>
      </div>
      <h1 class="screen-title">基础设施监控大屏</h1>
      <div class="screen-refresh-hint">
        <span class="screen-refresh-dot"></span>
        <span>数据每 2 分钟刷新</span>
      </div>
    </header>

    <!-- 右键菜单挂在容器上：#world 是 pointer-events:none，空白处的事件到不了它 -->
    <div id="canvas-container" @contextmenu.prevent.stop="onBlankContextMenu($event)">
      <div id="world">
        <svg id="svg-layer" :width="worldWidth" :height="worldHeight">
          <defs>
            <filter id="glow">
              <feGaussianBlur stdDeviation="3" result="coloredBlur"/>
              <feMerge>
                <feMergeNode in="coloredBlur"/>
                <feMergeNode in="SourceGraphic"/>
              </feMerge>
            </filter>
            <pattern id="grid-small" width="50" height="50" patternUnits="userSpaceOnUse">
              <path d="M 50 0 L 0 0 0 50" fill="none" stroke="rgba(77, 171, 247, 0.12)" stroke-width="0.5"/>
            </pattern>
            <pattern id="grid-large" width="250" height="250" patternUnits="userSpaceOnUse">
              <path d="M 250 0 L 0 0 0 250" fill="none" stroke="rgba(77, 171, 247, 0.28)" stroke-width="1"/>
            </pattern>
          </defs>

          <!-- 网格背景 -->
          <rect :width="worldWidth" :height="worldHeight" fill="url(#grid-small)" />
          <rect :width="worldWidth" :height="worldHeight" fill="url(#grid-large)" />

          <!-- 可见连线（折叠后只渲染两端都可见的链路） -->
          <path v-for="link in displayLinks" :key="'pv-' + link.id"
                :id="'path-' + link.id + 'v'"
                class="link-path"
                :class="getLinkAlertClass(link.id)"
                :d="link.pathD" />

          <!-- 热区连线（透明，用于鼠标悬浮） -->
          <path v-for="link in displayLinks" :key="'ph-' + link.id"
                :id="'path-' + link.id"
                class="link-hit"
                :data-link="link.linkKey"
                :d="link.pathD"
                @click.stop="openDetailPanel('link', link)"
                @contextmenu.stop.prevent="onLinkContextMenu($event, link)" />

          <!-- 流动粒子（按链路ID引用路径，避免折叠后下标错位导致粒子跑错线） -->
          <template v-for="(link, idx) in displayLinks">
            <circle :key="'fd1-' + link.id" r="3" class="flow-dot">
              <animateMotion :dur="(2 + (idx % 3) * 0.5) + 's'" repeatCount="indefinite" :begin="(idx * 0.15) + 's'">
                <mpath :href="'#path-' + link.id + 'v'"/>
              </animateMotion>
            </circle>
            <circle :key="'fd2-' + link.id" r="2.5" class="flow-dot" opacity="0.6">
              <animateMotion :dur="(3 + (idx % 3) * 0.3) + 's'" repeatCount="indefinite" :begin="(0.8 + idx * 0.15) + 's'">
                <mpath :href="'#path-' + link.id + 'v'"/>
              </animateMotion>
            </circle>
          </template>
        </svg>

        <!-- 连线创建指示线（编辑模式） -->
        <svg v-if="linkCreate.active" class="link-drag-overlay" :width="worldWidth" :height="worldHeight">
          <line :x1="linkCreate.startX" :y1="linkCreate.startY" :x2="linkCreate.curX" :y2="linkCreate.curY"
                stroke="#4dabf7" stroke-width="2" stroke-dasharray="6,4" />
        </svg>

        <div v-for="node in layoutNodes" :key="node.id"
             class="node"
             :class="[nodeBaseClass(node.styleClass, node.nodeType), 'node-' + node.styleClass, node.nodeType === 'circle' ? nodeColorClass(node.styleClass) : '', getNodeAlertClass(node.id, 'node'), getHiddenAlertLevel(node.id) ? 'node-alert-' + getHiddenAlertLevel(node.id) : '', node.collapsed ? 'node-collapsed' : '', editMode ? 'node-draggable' : '', nodeDrag.active && nodeDrag.nodeId === node.id ? 'node-dragging' : '']"
             :data-node="'node_' + node.id"
             :data-id="node.id"
             :style="{ left: node.layoutX + 'px', top: node.layoutY + 'px', transform: 'translate(-50%, -50%)' }"
             @mousedown.stop="onNodeMouseDown($event, node)"
             @click.stop="onNodeClick($event, node)"
             @contextmenu.stop.prevent="onNodeContextMenu($event, node)">
          <span class="node-name">{{ node.nodeName }}</span>
          <!-- 节点属性：实例 IP，一个 IP 一行展示；日志过滤节点（1.0 分站）的 IP 是 nginx 转发地址，不展示 -->
          <div v-if="nodeShowsIp(node)" class="node-ip-list" :title="nodeIpTitle(node)">
            <span v-for="(ip, i) in nodeIpDisplay(node)" :key="i" class="node-ip">{{ ip }}</span>
          </div>
          <div class="node-metrics-inline" v-if="getNodeKeyMetrics(node).length > 0">
            <div v-for="(m, mi) in getNodeKeyMetrics(node)" :key="mi" class="node-metric-row">
              <span class="node-metric-label">{{ m.label }}</span>
              <span class="node-metric-value">{{ m.value }}</span>
            </div>
          </div>
          <!-- 折叠开关：仅对有下游节点的节点展示 -->
          <div v-if="node.childCount > 0"
               class="node-collapse-btn"
               :class="{ 'is-collapsed': node.collapsed }"
               :title="node.collapsed ? '展开下游 ' + node.hiddenCount + ' 个节点' : '收起下游 ' + node.childCount + ' 个节点'"
               @click.stop="toggleCollapse(node)">{{ node.collapsed ? '+' : '−' }}</div>
          <!-- 收起后的隐藏节点数量角标（含隐藏子树的告警着色） -->
          <div v-if="node.collapsed && node.hiddenCount > 0"
               class="node-collapse-badge"
               :class="getHiddenAlertLevel(node.id) ? 'badge-' + getHiddenAlertLevel(node.id) : ''">{{ node.hiddenCount }}</div>
        </div>
      </div>
    </div>

    <!-- 层级折叠快捷栏（默认全展开） -->
    <div class="collapse-toolbar">
      <button :class="{ active: isAllExpanded }" title="展开全部节点" @click="expandAllNodes">展开全部</button>
      <button :class="{ active: editMode }" title="编辑模式下可增删改节点与连线" @click="toggleEditMode">{{ editMode ? '退出编辑' : '编辑拓扑' }}</button>
    </div>

    <div class="axis-labels x-labels">
      <span v-for="l in xAxisLabels" :key="'x'+l.value" :style="{ left: l.pos + 'px' }">{{ l.value }}</span>
    </div>
    <div class="axis-labels y-labels">
      <span v-for="l in yAxisLabels" :key="'y'+l.value" :style="{ top: l.pos + 'px' }">{{ l.value }}</span>
    </div>
    <!-- 右键菜单（编辑模式下提供节点/连线增删） -->
    <div v-if="contextMenu.show" class="context-menu" :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }" @click.stop>
      <template v-if="contextMenu.type === 'blank'">
        <div v-if="editMode" class="context-menu-item" @click="onMenuAddNode">在此处添加节点</div>
        <div v-else class="context-menu-item context-menu-disabled">开启编辑模式后可添加节点</div>
      </template>
      <template v-if="contextMenu.type === 'node'">
        <div v-if="contextMenuTarget && contextMenuTarget.childCount > 0" class="context-menu-item" @click="onMenuToggleCollapse">
          {{ contextMenuTarget.collapsed ? '展开下游节点（' + contextMenuTarget.hiddenCount + '）' : '收起下游节点（' + contextMenuTarget.childCount + '）' }}
        </div>
        <div v-if="editMode" class="context-menu-item" @click="onMenuEditNode">编辑节点</div>
        <div v-if="editMode" class="context-menu-item" @click="onMenuCreateLink">从此节点创建连线</div>
        <div v-if="editMode && contextMenuTarget && contextMenuTarget.childCount > 0" class="context-menu-item" @click="onMenuArrangeChildren">均匀排列子节点</div>
        <div class="context-menu-item" @click="onMenuAlertConfig">配置告警</div>
        <div v-if="editMode" class="context-menu-item context-menu-danger" @click="onMenuDeleteNode">删除节点</div>
      </template>
      <template v-if="contextMenu.type === 'link'">
        <div class="context-menu-item" @click="onMenuAlertConfig">配置告警</div>
        <div v-if="editMode" class="context-menu-item context-menu-danger" @click="onMenuDeleteLink">删除连线</div>
      </template>
    </div>

    <!-- 右滑详情面板 -->
    <div class="detail-panel" :class="{ 'detail-panel-open': detailPanel.show }">
      <div class="detail-header">
        <div class="detail-title">{{ detailPanel.title }}</div>
        <div class="detail-close" @click="closeDetailPanel">&times;</div>
      </div>
      <div class="detail-toolbar">
        <div class="detail-time-switch">
          <span :class="['time-btn', detailPanel.hours === 1 ? 'active' : '']" @click="onDetailHoursChange(1)">近1小时</span>
          <span :class="['time-btn', detailPanel.hours === 24 ? 'active' : '']" @click="onDetailHoursChange(24)">近24小时</span>
        </div>
        <span v-if="detailPanel.type === 'node'" class="instance-btn" @click="goToInstancePage">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2"/><path d="M8 21h8M12 17v4"/></svg>
          查看具体实例
        </span>
      </div>
      <div class="detail-body">
        <div v-if="detailPanel.loading" class="detail-loading">加载中...</div>
        <div v-else-if="detailChartKeys.length === 0 && detailPanel.textMetrics.length === 0 && detailPanel.distributionMetrics.length === 0" class="detail-empty">暂无数据</div>
        <!-- 文本型指标卡片 -->
        <div v-if="detailPanel.textMetrics.length > 0" class="detail-text-metrics">
          <div v-for="tm in detailPanel.textMetrics" :key="tm.name" class="detail-text-item">
            <div class="detail-text-name">{{ tm.name }}</div>
            <div class="detail-text-value">{{ tm.value }}</div>
          </div>
        </div>
        <!-- 分布型指标图表 -->
        <div v-if="detailPanel.distributionMetrics.length > 0" class="detail-distribution-metrics">
          <div v-for="(dm, idx) in detailPanel.distributionMetrics" :key="dm.name" class="detail-distribution-item">
            <div class="detail-chart-title">{{ dm.name }}</div>
            <div :id="'detailDistChart_' + idx" class="detail-chart"></div>
          </div>
        </div>
        <!-- 数值型指标趋势图 -->
        <div v-for="(key, idx) in detailChartKeys" :key="key" class="detail-chart-block">
          <div class="detail-chart-title">{{ key }}</div>
          <div :id="'detailChart_' + idx" class="detail-chart"></div>
        </div>
      </div>
    </div>

    <!-- 趋势图弹窗 -->
    <div v-if="trendChart.show" class="trend-overlay" @click.self="closeTrendChart">
      <div class="trend-panel">
        <div class="trend-header">
          <div class="trend-title">{{ trendChart.title }}</div>
          <div class="trend-close" @click="closeTrendChart">&times;</div>
        </div>
        <div class="trend-toolbar">
          <div class="trend-tool-group">
            <label>日期</label>
            <input type="date" v-model="trendChart.targetDate" @change="onTargetDateChange" />
          </div>
          <div class="trend-tool-group">
            <label>对比</label>
            <select v-model="trendChart.compareMode" @change="onCompareModeChange">
              <option value="none">不对比</option>
              <option value="yesterday">昨天</option>
              <option value="lastweek">上周今天</option>
              <option value="custom">自定义</option>
            </select>
          </div>
          <div v-if="trendChart.compareMode === 'custom'" class="trend-tool-group">
            <label>对比日期</label>
            <input type="date" v-model="trendChart.compareDate" @change="onCompareDateChange" />
          </div>
        </div>
        <div class="trend-canvas-wrap">
          <canvas v-show="!trendChart.textMode" id="trendCanvas" ref="trendCanvas"></canvas>
          <!-- 文本型指标（TOP访问IP、终端分布等）：以双日对照列表呈现，支持对比 -->
          <div v-if="trendChart.textMode" class="trend-text-list">
            <div class="trend-text-head">
              <span class="col-time">时刻</span>
              <span class="col-val">{{ trendChart.targetDate }}（目标）</span>
              <span v-if="trendChart.compareData.length" class="col-val col-compare">{{ trendChart.compareDate }}（对比{{ trendChart.compareFallback ? '·自动回溯' : '' }}）</span>
            </div>
            <div v-for="(row, idx) in trendChart.textRows" :key="idx" class="trend-text-row">
              <span class="col-time">{{ row.time }}</span>
              <span class="col-val">{{ row.target || '-' }}</span>
              <span v-if="trendChart.compareData.length" class="col-val col-compare" :class="{ 'val-diff': row.changed }">{{ row.compare || '-' }}</span>
            </div>
            <div v-if="!trendChart.textRows.length" class="trend-text-none">暂无数据</div>
          </div>
          <div v-if="trendChart.loading" class="trend-loading">加载中...</div>
          <div v-if="trendHover.show" class="trend-hover-tip" :style="{ left: trendHover.x + 'px', top: trendHover.y + 'px' }">
            <div class="trend-hover-time">{{ trendHover.time }}</div>
            <div v-if="trendHover.targetValue !== ''" class="trend-hover-row">
              <span class="trend-hover-dot" style="background:#4dabf7;"></span>
              <span>目标: {{ trendHover.targetValue }}</span>
            </div>
            <div v-if="trendHover.compareValue !== ''" class="trend-hover-row">
              <span class="trend-hover-dot" style="background:#adb5bd;"></span>
              <span>对比: {{ trendHover.compareValue }}</span>
            </div>
          </div>
        </div>
        <div v-if="!trendChart.textMode" class="trend-legend">
          <div class="legend-item">
            <span class="legend-line" style="background:#4dabf7;"></span>
            <span>{{ trendChart.targetDate }}（目标）</span>
          </div>
          <div v-if="trendChart.compareMode !== 'none' && trendChart.compareDate && trendChart.compareData.length" class="legend-item">
            <span class="legend-line" style="background:#adb5bd;"></span>
            <span>{{ trendChart.compareDate }}（对比{{ trendChart.compareFallback ? '·自动回溯' : '' }}）</span>
          </div>
        </div>
        <div v-if="!trendChart.textMode && trendChart.compareMode !== 'none' && trendChart.compareDate && trendChart.compareData.length" class="trend-stats">
          <div class="stat-item" v-if="trendChart.avgChange !== null">
            <span class="stat-label">均值变化</span>
            <span :class="['stat-value', getTrendChangeClass(trendChart.avgChange)]">
              {{ getTrendChangeText(trendChart.avgChange) }}
            </span>
          </div>
          <div class="stat-item" v-if="trendChart.latestChange !== null">
            <span class="stat-label">最新值变化</span>
            <span :class="['stat-value', getTrendChangeClass(trendChart.latestChange)]">
              {{ getTrendChangeText(trendChart.latestChange) }}
            </span>
          </div>
        </div>
      </div>
    </div>

    <!-- 节点编辑弹窗已下线（拓扑为固定终态，节点由 _sql/init_topology.sql 维护） -->

    <div id="coord-panel">X: {{ mouseWorldX }} | Y: {{ mouseWorldY }}</div>

    <!-- 告警面板（右侧悬浮） -->
    <div class="alert-float-panel" :class="{ 'alert-float-open': alertPanel.expanded }">
      <div class="locate-btn" @click="locateAllNodes" title="定位到所有节点">
        <span>⊕</span>
      </div>
      <div class="alert-float-toggle" @click="toggleAlertPanel" :title="alertPanel.expanded ? '收起告警' : '展开告警'">
        <span class="alert-float-icon">⚠</span>
        <span class="alert-float-count" v-if="activeAlerts.length > 0">{{ activeAlerts.length }}</span>
      </div>
      <div class="alert-float-body" v-show="alertPanel.expanded">
        <div class="alert-float-title">
          <span>⚠ 告警监控</span>
          <span class="alert-float-title-actions">
            <span class="alert-float-notify-btn" @click.stop="openNotifySettings" title="通知渠道设置（企业微信群机器人）">⚙</span>
            <span class="alert-float-close" @click.stop="closeAlertPanel">✕</span>
          </span>
        </div>
        <!-- Tab 切换 -->
        <div class="alert-float-tabs">
          <span :class="['alert-tab', alertPanel.tab === 'active' ? 'tab-active' : '']" @click="switchAlertTab('active')">活跃告警 ({{ activeAlerts.length }})</span>
          <span :class="['alert-tab', alertPanel.tab === 'history' ? 'tab-active' : '']" @click="switchAlertTab('history')">历史记录</span>
        </div>
        <!-- 活跃告警列表 -->
        <div class="alert-float-list" v-show="alertPanel.tab === 'active'">
          <div v-if="activeAlerts.length === 0" class="alert-float-empty">
            <span class="alert-float-ok-icon">✓</span>
            <span>系统运行正常</span>
            <span class="alert-float-empty-time">{{ currentTime }}</span>
          </div>
          <div v-for="alert in activeAlerts" :key="alert.id" class="alert-float-item" :class="'alert-float-' + alert.alertLevel">
            <div class="alert-float-item-main" @click="locateAlertTarget(alert)">
              <div class="alert-float-item-left">
                <span class="alert-float-level-tag" :class="'tag-' + alert.alertLevel">{{ alert.alertLevel === 'critical' ? '严重' : '警告' }}</span>
                <span class="alert-float-target">{{ alert.targetName || alert.targetId }}</span>
                <span v-if="alert.targetType === 'link'" class="alert-float-scope scope-link">链路</span>
                <span v-else-if="alert.serverIp" class="alert-float-scope scope-instance" :title="'实例 ' + alert.serverIp">实例 {{ alert.serverIp }}</span>
                <span v-else class="alert-float-scope scope-aggregate">节点汇总</span>
              </div>
              <div class="alert-float-item-detail">
                <span class="alert-float-metric">{{ alert.metricName }} {{ alert.operator }} {{ alert.thresholdValue }}</span>
                <span class="alert-float-value">{{ alert.metricValue }}</span>
              </div>
              <div class="alert-float-item-time">{{ alert.alertTime }}</div>
            </div>
          </div>
        </div>
        <!-- 历史记录列表 -->
        <div class="alert-float-list" v-show="alertPanel.tab === 'history'">
          <div v-if="alertHistory.length === 0" class="alert-float-empty">
            <span class="alert-float-ok-icon"></span>
            <span>暂无告警记录</span>
          </div>
          <div v-for="alert in alertHistory" :key="alert.id" class="alert-float-item alert-float-history" @click="locateAlertTarget(alert)">
            <div class="alert-float-item-left">
              <span class="alert-float-level-tag" :class="'tag-' + alert.alertLevel">{{ alert.alertLevel === 'critical' ? '严重' : '警告' }}</span>
              <span class="alert-float-target">{{ alert.targetName || alert.targetId }}</span>
              <span v-if="alert.targetType === 'link'" class="alert-float-scope scope-link">链路</span>
              <span v-else-if="alert.serverIp" class="alert-float-scope scope-instance" :title="'实例 ' + alert.serverIp">实例 {{ alert.serverIp }}</span>
              <span v-else class="alert-float-scope scope-aggregate">节点汇总</span>
              <span class="alert-float-status-tag" :class="'status-' + alert.status">{{ alert.status === 'active' ? '活跃' : '已恢复' }}</span>
            </div>
            <div class="alert-float-item-detail">
              <span class="alert-float-metric">{{ alert.metricName }} {{ alert.operator }} {{ alert.thresholdValue }}</span>
              <span class="alert-float-value">{{ alert.metricValue }}</span>
            </div>
            <div class="alert-float-item-time">{{ alert.alertTime }}<span v-if="alert.resolveTime" class="alert-resolve-time"> → {{ alert.resolveTime }}</span></div>
          </div>
        </div>
      </div>
    </div>

    <!-- 告警规则配置弹窗 -->
    <div v-if="alertForm.show" class="node-form-overlay" @click.self="closeAlertForm">
      <div class="node-form-panel alert-config-panel">
        <div class="node-form-title">
          <span class="alert-config-icon">⚙</span>
          告警规则配置 - {{ alertForm.targetName }}
        </div>
        <div class="node-form-body">
          <!-- 已有规则列表 -->
          <div class="alert-config-section">
            <div class="alert-config-section-title">已有规则</div>
            <div v-if="alertForm.existingRules.length > 0" class="alert-rules-list">
              <div v-for="rule in alertForm.existingRules" :key="rule.id" class="alert-rule-item" :class="{ 'rule-editing': alertForm.editId === rule.id }">
                <span class="rule-metric">{{ rule.metricName }} {{ rule.operator }} {{ rule.thresholdValue }}</span>
                <span v-if="rule.triggerPeriods > 1" class="rule-period">连续{{ rule.triggerPeriods }}周期</span>
                <span class="alert-rule-level" :class="'level-' + rule.alertLevel">{{ rule.alertLevel === 'critical' ? '严重' : '警告' }}</span>
                <span class="alert-rule-status" :class="rule.enabled === 1 ? 'enabled' : 'disabled'">{{ rule.enabled === 1 ? '启用' : '禁用' }}</span>
                <span class="alert-rule-action" @click="editAlertRule(rule)">编辑</span>
                <span class="alert-rule-action danger" @click="deleteAlertRule(rule)">删除</span>
              </div>
            </div>
            <div v-else class="alert-rules-empty">暂无告警规则，请在下方添加</div>
          </div>
          <!-- 新增/编辑规则表单 -->
          <div class="alert-config-section">
            <div class="alert-config-section-title">{{ alertForm.editId ? '编辑规则' : '添加新规则' }}</div>
            <div class="alert-rule-form">
              <div class="form-row">
                <label>指标名称</label>
                <select v-model="alertForm.metricName">
                  <option value="">请选择指标</option>
                  <option v-for="m in alertForm.availableMetrics" :key="m" :value="m">{{ m }}</option>
                </select>
              </div>
              <div class="form-row-inline">
                <div class="form-row form-row-half">
                  <label>运算符</label>
                  <select v-model="alertForm.operator">
                    <option value=">">&gt;</option>
                    <option value=">=">&gt;=</option>
                    <option value="<">&lt;</option>
                    <option value="<=">&lt;=</option>
                    <option value="==">==</option>
                    <option value="!=">!=</option>
                  </select>
                </div>
                <div class="form-row form-row-half">
                  <label>阈值</label>
                  <input v-model="alertForm.thresholdValue" type="text" placeholder="如: 80" />
                </div>
              </div>
              <div class="form-row-inline">
                <div class="form-row form-row-half">
                  <label>连续超阈周期</label>
                  <input v-model.number="alertForm.triggerPeriods" type="number" min="1" max="720" placeholder="1" />
                </div>
                <div class="form-row form-row-half">
                  <label>连续正常周期</label>
                  <input v-model.number="alertForm.recoverPeriods" type="number" min="1" max="720" placeholder="1" />
                </div>
              </div>
              <div class="form-row-inline">
                <div class="form-row form-row-half">
                  <label>告警级别</label>
                  <select v-model="alertForm.alertLevel">
                    <option value="warning"> Warning（黄色闪烁）</option>
                    <option value="critical">🔴 Critical（红色闪烁）</option>
                  </select>
                </div>
                <div class="form-row form-row-half">
                  <label>状态</label>
                  <select v-model="alertForm.enabled">
                    <option :value="1">启用</option>
                    <option :value="0">禁用</option>
                  </select>
                </div>
              </div>
              <div class="alert-config-tip">提示：规则按目标生效，节点指标会同时校验节点汇总值与各实例值，触发时按实例分别生成告警记录。1 个采集周期约 2 分钟：连续超阈周期填 3 表示连续约 6 分钟超阈才告警，连续正常周期填 3 表示连续约 6 分钟正常才自动恢复（填 1 为单周期即生效）。</div>
            </div>
          </div>
        </div>
        <div class="node-form-footer">
          <button class="btn-cancel" @click="closeAlertForm">关闭</button>
          <button class="btn-confirm" @click="saveAlertRule">{{ alertForm.editId ? '更新规则' : '添加规则' }}</button>
        </div>
      </div>
    </div>

    <!-- 通知渠道设置弹窗（企业微信群机器人，全局统一渠道） -->
    <div v-if="notifyForm.show" class="node-form-overlay" @click.self="closeNotifySettings">
      <div class="node-form-panel alert-config-panel">
        <div class="node-form-title">
          <span class="alert-config-icon">⚙</span>
          通知渠道设置 - 企业微信群机器人
        </div>
        <div class="node-form-body">
          <!-- 已配置渠道 -->
          <div class="alert-config-section">
            <div class="alert-config-section-title">已配置渠道（全局生效）</div>
            <div v-if="notifyForm.channels.length > 0" class="alert-rules-list">
              <div v-for="ch in notifyForm.channels" :key="ch.id" class="alert-rule-item" :class="{ 'rule-editing': notifyForm.editId === ch.id }">
                <span class="notify-channel-name" :title="ch.channelName">{{ ch.channelName }}</span>
                <span class="alert-rule-status" :class="ch.enabled === 1 ? 'enabled' : 'disabled'">{{ ch.enabled === 1 ? '启用' : '禁用' }}</span>
                <span class="notify-channel-url" :title="ch.webhookUrlMask">{{ ch.webhookUrlMask }}</span>
                <span class="alert-rule-action" @click="testNotifyChannel(ch)">{{ notifyForm.testingId === ch.id ? '发送中...' : '测试' }}</span>
                <span class="alert-rule-action" @click="editNotifyChannel(ch)">编辑</span>
                <span class="alert-rule-action danger" @click="deleteNotifyChannel(ch)">删除</span>
              </div>
            </div>
            <div v-else class="alert-rules-empty">暂无通知渠道，告警不会推送到企业微信</div>
          </div>
          <!-- 新增/编辑渠道表单 -->
          <div class="alert-config-section">
            <div class="alert-config-section-title">{{ notifyForm.editId ? '编辑渠道' : '添加渠道' }}</div>
            <div class="alert-rule-form">
              <div class="form-row">
                <label>渠道名称</label>
                <input v-model="notifyForm.channelName" maxlength="100" placeholder="如：运维告警群" />
              </div>
              <div class="form-row">
                <label>Webhook 地址</label>
                <input v-model="notifyForm.webhookUrl" maxlength="1000" :placeholder="notifyForm.editId ? '留空表示不修改原地址' : 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=...'" />
              </div>
              <div class="form-row">
                <label>状态</label>
                <select v-model="notifyForm.enabled">
                  <option :value="1">启用</option>
                  <option :value="0">禁用</option>
                </select>
              </div>
            </div>
          </div>
        </div>
        <div class="node-form-footer">
          <button class="btn-cancel" @click="closeNotifySettings">关闭</button>
          <button class="btn-confirm" @click="saveNotifyChannel">{{ notifyForm.editId ? '更新渠道' : '添加渠道' }}</button>
        </div>
      </div>
    </div>
    <!-- 节点编辑弹窗（编辑模式；含实例绑定管理） -->
    <div v-if="nodeForm.show" class="node-form-overlay" @click.self="closeNodeForm">
      <div class="node-form-panel node-edit-panel">
        <div class="node-form-title">{{ nodeForm.isNew ? '新增节点' : '编辑节点' }}</div>
        <div class="node-form-body">
          <div class="form-row">
            <label>节点名称</label>
            <input v-model="nodeForm.nodeName" maxlength="100" placeholder="如：运营管理平台" />
          </div>
          <div class="form-row">
            <label>节点描述</label>
            <input v-model="nodeForm.description" maxlength="500" placeholder="选填，展示在提示框中" />
          </div>
          <div class="form-row-inline">
            <div class="form-row form-row-half">
              <label>节点样式</label>
              <select v-model="nodeForm.styleClass" :disabled="!nodeForm.isNew" @change="onStyleClassChange">
                <option value="client">客户端（圆形）</option>
                <option value="nginx">nginx</option>
                <option value="app">应用服务</option>
              </select>
            </div>
            <div class="form-row form-row-half">
              <label>坐标（可拖动修改）</label>
              <input :value="nodeForm.posX + ', ' + nodeForm.posY" readonly />
            </div>
          </div>

          <!-- 客户端为全量日志口径，不按节点实例 IP 过滤，不展示绑定配置 -->
          <div v-if="nodeForm.styleClass !== 'client'" class="binding-section">
            <div class="binding-title">
              <span>{{ nodeForm.bindType === 'LOG_FILTER' ? '匹配 IP（REMOTE_ADDR）' : '实例 IP' }}</span>
              <span class="binding-add" @click="addBindingRow">+ 添加</span>
            </div>
            <div class="binding-row-inline">
              <label>绑定方式</label>
              <select v-model="nodeForm.bindType" @change="onBindTypeChange">
                <option value="IP">实例 IP</option>
                <option value="LOG_FILTER">日志过滤</option>
              </select>
              <template v-if="nodeForm.bindType === 'LOG_FILTER'">
                <label>请求前缀</label>
                <input v-model="nodeForm.requestPrefix" placeholder="/pposgjdf" />
              </template>
            </div>
            <div v-if="nodeForm.bindings.length === 0" class="binding-empty">暂无 IP，该节点不会产生指标数据</div>
            <div v-for="(row, idx) in nodeForm.bindings" :key="idx" class="binding-row">
              <input v-model="row.instanceIp" class="binding-ip" :placeholder="nodeForm.bindType === 'LOG_FILTER' ? 'REMOTE_ADDR，如 172.16.27.120' : '如 192.168.1.10'" />
              <span class="binding-remove" @click="removeBindingRow(idx)">删除</span>
            </div>
          </div>
        </div>
        <div class="node-form-footer">
          <button class="btn-cancel" @click="closeNodeForm">取消</button>
          <button class="btn-confirm" @click="saveNode">{{ nodeForm.isNew ? '创建' : '保存' }}</button>
        </div>
      </div>
    </div>

    <div class="tooltip" id="tooltip">
      <div class="tooltip-title" id="tooltip-title">{{ currentTooltip.title || '--' }}</div>
      <div id="tooltip-content">
        <div
          v-for="(item, idx) in currentTooltip.items"
          :key="idx"
          class="tooltip-row"
          :class="{ 'tooltip-row-clickable': item.real !== false }"
          @click="item.real !== false ? onMetricClick(item) : null"
        >
          <span class="tooltip-label">{{ item.label }}：</span>
          <span :class="['tooltip-value', item.real === false ? 'simulated' : '']">{{ item.value }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import service from '@/utils/request'

// 折叠状态持久化 key：无记录时默认全展开
const COLLAPSE_STORAGE_KEY = 'topology_collapsed_nodes_v1'

// 节点样式选项：与后端 ALLOWED_STYLE_CLASS、computeLinkPath 尺寸表保持一致
const STYLE_CLASS_OPTIONS = ['client', 'nginx', 'app']

export default {
  name: 'TopologyPage',
  data() {
    return {
      _hideTimer: null,
      topologyNodes: [],
      // 折叠状态：key 为节点ID；空对象 = 全展开（默认）
      collapsedIds: {},
      // 世界画布尺寸与中心：新增 1.0 分站后叶子节点由 10 个变为 12 个，
      // 纵向跨度约 2250px，画布高度相应从 2000 扩到 2400，中心下移到 1200
      worldWidth: 3000,
      worldHeight: 2400,
      worldCenterX: 1500,
      worldCenterY: 1200,
      viewScale: 1,
      viewPanX: 0,
      viewPanY: 0,
      isDragging: false,
      dragStartX: 0,
      dragStartY: 0,
      panStartX: 0,
      panStartY: 0,
      minScale: 0.2,
      maxScale: 5,
      _userInteracted: false,
      // ===== 拓扑编辑（自由布局：节点坐标以数据库为准）=====
      editMode: false,
      nodeDrag: { active: false, nodeId: '', startX: 0, startY: 0, originX: 0, originY: 0, moved: false, dirty: false },
      nodeForm: {
        show: false,
        isNew: true,
        id: '',
        nodeName: '',
        description: '',
        styleClass: 'app',
        nodeType: 'rect',
        posX: 0,
        posY: 0,
        bindType: 'IP',
        requestPrefix: '',
        bindings: []
      },
      linkCreate: { active: false, sourceId: '', startX: 0, startY: 0, curX: 0, curY: 0 },
      mouseWorldX: 0,
      mouseWorldY: 0,
      gridXLabels: [0, 250, 500, 750, 1000, 1250, 1500, 1750, 2000, 2250, 2500, 2750, 3000],
      gridYLabels: [0, 250, 500, 750, 1000, 1250, 1500, 1750, 2000, 2250],
      topologyLinks: [],
      // 右键菜单
      contextMenu: {
        show: false,
        x: 0,
        y: 0,
        type: ''
      },
      contextMenuTarget: null,
      currentTooltip: {
        title: '',
        items: []
      },
      currentTooltipDataId: '',
      _lastNodeMetrics: [],
      // 趋势图弹窗
      trendChart: {
        show: false,
        title: '',
        targetDate: '',
        compareDate: '',
        compareMode: 'none',
        targetData: [],
        compareData: [],
        compareFallback: false,
        textMode: false,
        textRows: [],
        loading: false,
        type: '',
        dataId: '',
        metricName: '',
        metricUnit: '',
        avgChange: null,
        latestChange: null
      },
      // Canvas 趋势图悬浮提示
      trendHover: {
        show: false,
        x: 0,
        y: 0,
        time: '',
        targetValue: '',
        compareValue: ''
      },
      // 详情面板（右滑出）
      detailPanel: {
        show: false,
        title: '',
        type: '',
        id: '',
        hours: 1,
        loading: false,
        trendData: {},
        textMetrics: [],
        distributionMetrics: []
      },
      // 告警相关
      activeAlerts: [],
      alertHistory: [],
      currentTime: '',
      _alertPollTimer: null,
      _clockTimer: null,
      alertPanel: {
        expanded: false,
        tab: 'active'
      },
      alertForm: {
        show: false,
        targetType: '',
        targetId: '',
        targetName: '',
        editId: '',
        metricName: '',
        operator: '>',
        thresholdValue: '',
        triggerPeriods: 1,
        recoverPeriods: 1,
        alertLevel: 'warning',
        enabled: 1,
        existingRules: [],
        availableMetrics: []
      },
      // 通知渠道配置（企业微信群机器人，全局生效）
      notifyForm: {
        show: false,
        channels: [],
        editId: '',
        channelName: '',
        webhookUrl: '',
        enabled: 1,
        testingId: ''
      },
      tooltipData: {
        node_client: {
          title: '客户端',
          items: [
            { label: '总IP数', value: '', real: true },
            { label: 'IP活跃数', value: '', real: true },
            { label: 'TOP访问IP', value: '', real: true },
            { label: '并发连接数', value: '', real: true },
            { label: '终端分布', value: '', real: true },
            { label: 'UA类型', value: '', real: true }
          ]
        },
        node_nginx: {
          title: 'Nginx',
          // 只保留 Nginx 访问日志真实采集的 14 项指标；
          // 早期 PPOS 时代的模拟指标（CPU/内存/磁盘/工作进程数等）已删除，避免展示空行
          items: [
            { label: '每秒请求数(QPS)', value: '', real: true },
            { label: '请求错误率', value: '', real: true },
            { label: '平均响应时间', value: '', real: true },
            { label: 'P95请求耗时', value: '', real: true },
            { label: 'P99请求耗时', value: '', real: true },
            { label: '慢请求占比', value: '', real: true },
            { label: '5xx错误率', value: '', real: true },
            { label: '4xx错误率', value: '', real: true },
            { label: '上游响应耗时', value: '', real: true },
            { label: 'Nginx自身处理耗时', value: '', real: true },
            { label: '上游耗时占比', value: '', real: true },
            { label: '上游错误率', value: '', real: true },
            { label: '上游状态码分布', value: '', real: true },
            { label: 'Nginx进程存活', value: '', real: true }
          ]
        },
        node_app: {
          title: '应用服务',
          // 与 T_MT_NODE_METRIC_DEF 的 7 个 Tomcat 指标定义保持一致（1.0 分站会剔除 TOP 与进程存活）
          items: [
            { label: 'QPS', value: '', real: true },
            { label: '请求失败率', value: '', real: true },
            { label: '5xx错误率', value: '', real: true },
            { label: '接口请求量TOP', value: '', real: true },
            { label: '请求错误率', value: '', real: true },
            { label: '4xx错误率', value: '', real: true },
            { label: '进程存活', value: '', real: true }
          ]
        },
        node_ppos10: {
          title: 'PPOS 1.0',
          items: [
            { label: '进程存活状态', value: '', real: false },
            { label: 'CPU 使用率', value: '', real: false },
            { label: '可用内存量', value: '', real: false },
            { label: '磁盘空间使用率', value: '', real: false },
            { label: '当前活跃连接数', value: '', real: false },
            { label: '任务队列状态', value: '', real: false },
            { label: '服务实例个数', value: '', real: false },
            { label: '运行时长', value: '', real: false },
            { label: '重启次数', value: '', real: false },
            { label: '接口实时 QPS', value: '', real: true },
            { label: '接口成功率', value: '', real: true },
            { label: '接口失败次数', value: '', real: true }
          ]
        },
        node_ppos30: {
          title: 'PPOS 3.0',
          items: [
            { label: '进程存活状态', value: '', real: false },
            { label: 'CPU 使用率', value: '', real: false },
            { label: '可用内存量', value: '', real: false },
            { label: '磁盘空间使用率', value: '', real: false },
            { label: '当前活跃连接数', value: '', real: false },
            { label: '任务队列状态', value: '', real: false },
            { label: '服务实例个数', value: '', real: false },
            { label: '运行时长', value: '', real: false },
            { label: '重启次数', value: '', real: false },
            { label: '接口实时 QPS', value: '', real: true },
            { label: '接口成功率', value: '', real: true },
            { label: '接口失败次数', value: '', real: true }
          ]
        },
        node_daoyue10: {
          title: '道悦1.0分站',
          items: [
            { label: '进程存活状态', value: '', real: false },
            { label: '服务实例个数', value: '', real: false },
            { label: '运行时长', value: '', real: false },
            { label: '重启次数', value: '', real: false },
            { label: 'CPU使用率', value: '', real: false },
            { label: '系统平均负载', value: '', real: false },
            { label: '内存使用率', value: '', real: false },
            { label: '可用内存量', value: '', real: false },
            { label: '磁盘空间使用率', value: '', real: false },
            { label: '磁盘I/O', value: '', real: false },
            { label: '当前活跃连接数', value: '', real: false },
            { label: '已打开文件句柄数', value: '', real: false },
            { label: '任务队列状态', value: '', real: false }
          ]
        },
        node_daoyue10gz: {
          title: '道悦1.0估值分站',
          items: [
            { label: '进程存活状态', value: '', real: false },
            { label: '服务实例个数', value: '', real: false },
            { label: '运行时长', value: '', real: false },
            { label: '重启次数', value: '', real: false },
            { label: 'CPU使用率', value: '', real: false },
            { label: '系统平均负载', value: '', real: false },
            { label: '内存使用率', value: '', real: false },
            { label: '可用内存量', value: '', real: false },
            { label: '磁盘空间使用率', value: '', real: false },
            { label: '磁盘I/O', value: '', real: false },
            { label: '当前活跃连接数', value: '', real: false },
            { label: '已打开文件句柄数', value: '', real: false },
            { label: '任务队列状态', value: '', real: false }
          ]
        },
        node_daoyue30wj: {
          title: '道悦3.0外框架微前端',
          items: [
            { label: '进程存活状态', value: '', real: false },
            { label: '服务实例个数', value: '', real: false },
            { label: '运行时长', value: '', real: false },
            { label: '重启次数', value: '', real: false },
            { label: 'CPU使用率', value: '', real: false },
            { label: '系统平均负载', value: '', real: false },
            { label: '内存使用率', value: '', real: false },
            { label: '可用内存量', value: '', real: false },
            { label: '磁盘空间使用率', value: '', real: false },
            { label: '磁盘I/O', value: '', real: false },
            { label: '当前活跃连接数', value: '', real: false },
            { label: '已打开文件句柄数', value: '', real: false },
            { label: '任务队列状态', value: '', real: false }
          ]
        },
        node_daoyue30ws: {
          title: '道悦3.0微服务',
          items: [
            { label: '进程存活状态', value: '', real: false },
            { label: '服务实例个数', value: '', real: false },
            { label: '运行时长', value: '', real: false },
            { label: '重启次数', value: '', real: false },
            { label: 'CPU使用率', value: '', real: false },
            { label: '系统平均负载', value: '', real: false },
            { label: '内存使用率', value: '', real: false },
            { label: '可用内存量', value: '', real: false },
            { label: '磁盘空间使用率', value: '', real: false },
            { label: '磁盘I/O', value: '', real: false },
            { label: '当前活跃连接数', value: '', real: false },
            { label: '已打开文件句柄数', value: '', real: false },
            { label: '任务队列状态', value: '', real: false }
          ]
        },
        // 链路 tooltip 指标模板：仅作模板源，实际 tooltip 的 key 为 link_<链路ID>
        // 客户端 -> nginx 三条链路已接入 nginx 访问日志指标，共用本模板的指标项；
        // nginx -> 后端链路暂无指标定义，走"暂无指标数据"空态兜底
        tpl_link_client: {
          items: [
            { label: 'TOP请求路径', value: '', real: true },
            { label: '请求总数', value: '', real: true },
            { label: '请求失败次数', value: '', real: true },
            { label: '请求平均耗时', value: '', real: true },
            { label: '请求成功率', value: '', real: true }
          ]
        }
      }
    }
  },
  computed: {
    clockDate() {
      return this.currentTime.slice(0, 10)
    },
    clockTime() {
      return this.currentTime.slice(11)
    },
    detailChartKeys() {
      return Object.keys(this.detailPanel.trendData || {})
    },
    isAllExpanded() {
      return Object.keys(this.collapsedIds).length === 0
    },
    // ========== 拓扑折叠：父子关系与层深 ==========
    // 层级由链路推导（入度为 0 为根），不依赖 T_MT_NODE_INFO 里的层级字段；
    // 子节点按原始 posY 排序，保证展开态纵向顺序与初始化脚本一致
    graphIndex() {
      const childrenById = {}
      const parentById = {}
      const inDegree = {}
      const posYById = {}
      this.topologyNodes.forEach(n => {
        childrenById[n.id] = []
        inDegree[n.id] = 0
        posYById[n.id] = n.posY || 0
      })
      this.topologyLinks.forEach(link => {
        if (!childrenById[link.sourceNodeId] || !childrenById[link.targetNodeId]) return
        childrenById[link.sourceNodeId].push(link.targetNodeId)
        parentById[link.targetNodeId] = link.sourceNodeId
        inDegree[link.targetNodeId]++
      })
      Object.keys(childrenById).forEach(id => {
        childrenById[id].sort((a, b) => (posYById[a] - posYById[b]) || String(a).localeCompare(String(b)))
      })
      const depthById = {}
      let frontier = this.topologyNodes.filter(n => inDegree[n.id] === 0).map(n => n.id)
      let depth = 0
      while (frontier.length > 0 && depth < 20) {
        const next = []
        frontier.forEach(id => {
          if (depthById[id] !== undefined) return
          depthById[id] = depth
          childrenById[id].forEach(childId => next.push(childId))
        })
        frontier = next
        depth++
      }
      this.topologyNodes.forEach(n => {
        if (depthById[n.id] === undefined) depthById[n.id] = 0
      })
      return {
        childrenById,
        parentById,
        depthById,
        rootIds: this.topologyNodes.filter(n => inDegree[n.id] === 0).map(n => n.id)
      }
    },
    // 可见节点：没有任何祖先被收起的节点
    visibleNodeIds() {
      const visible = {}
      const childrenById = this.graphIndex.childrenById
      const walk = id => {
        if (visible[id]) return
        visible[id] = true
        if (this.collapsedIds[id]) return
        childrenById[id].forEach(walk)
      }
      this.graphIndex.rootIds.forEach(walk)
      return visible
    },
    // 自由布局：节点坐标以数据库为准（拖动结果直接落库），折叠只隐藏子树，不做重排
    layoutNodes() {
      const childrenById = this.graphIndex.childrenById
      const depthById = this.graphIndex.depthById
      const visible = this.visibleNodeIds
      const countDescendants = id => {
        let count = 0
        const walk = nid => childrenById[nid].forEach(childId => { count++; walk(childId) })
        walk(id)
        return count
      }
      return this.topologyNodes
        .filter(n => visible[n.id])
        .map(n => ({
          ...n,
          layoutX: n.posX || 0,
          layoutY: n.posY || 0,
          depth: depthById[n.id] || 0,
          childCount: childrenById[n.id].length,
          hiddenCount: this.collapsedIds[n.id] ? countDescendants(n.id) : 0,
          collapsed: !!this.collapsedIds[n.id]
        }))
    },
    // 可见链路：两端都可见才渲染，路径按当前布局坐标实时重算
    displayLinks() {
      const nodeMap = {}
      this.layoutNodes.forEach(n => { nodeMap[n.id] = n })
      return this.topologyLinks
        .filter(link => nodeMap[link.sourceNodeId] && nodeMap[link.targetNodeId])
        .map(link => {
          const source = nodeMap[link.sourceNodeId]
          const target = nodeMap[link.targetNodeId]
          const pathD = this.computeLinkPath(
            { ...source, posX: source.layoutX, posY: source.layoutY },
            { ...target, posX: target.layoutX, posY: target.layoutY }
          )
          return { ...link, pathD }
        })
    },
    xAxisLabels() {
      const cw = window.innerWidth
      const scale = this.viewScale
      const worldLeft = this.worldCenterX + (0 - cw / 2 - this.viewPanX) / scale
      const worldRight = this.worldCenterX + (cw - cw / 2 - this.viewPanX) / scale
      const step = 250
      const start = Math.floor(worldLeft / step) * step
      const end = Math.ceil(worldRight / step) * step
      const labels = []
      for (let x = start; x <= end; x += step) {
        const pos = cw / 2 + this.viewPanX + (x - this.worldCenterX) * scale
        if (pos >= -30 && pos <= cw + 30) {
          labels.push({ value: x, pos })
        }
      }
      return labels
    },
    yAxisLabels() {
      const ch = window.innerHeight
      const scale = this.viewScale
      const worldTop = this.worldCenterY + (0 - ch / 2 - this.viewPanY) / scale
      const worldBottom = this.worldCenterY + (ch - ch / 2 - this.viewPanY) / scale
      const step = 250
      const start = Math.floor(worldTop / step) * step
      const end = Math.ceil(worldBottom / step) * step
      const labels = []
      for (let y = start; y <= end; y += step) {
        const pos = ch / 2 + this.viewPanY + (y - this.worldCenterY) * scale
        if (pos >= -20 && pos <= ch + 20) {
          labels.push({ value: y, pos })
        }
      }
      return labels
    }
  },
  mounted() {
    // 非响应式属性：标记最近一次拖动结束时间，用于抑制拖动后误触 click
    this._lastDragEndAt = 0
    this.loadCollapseState()
    this.loadTopology()
    this.loadMetrics()
    this.bindEvents()
    this.initZoomPan()
    this.startAlertPolling()
    this.startMetricsPolling()
    this.startClock()
    this.$nextTick(() => {
      this.resizeLayout()
      setTimeout(() => this.resizeLayout(), 100)
    })
    window.addEventListener('resize', this.resizeLayout)
  },
  beforeDestroy() {
    window.removeEventListener('resize', this.resizeLayout)
    this.unbindEvents()
    this.unbindZoomPan()
    this.stopAlertPolling()
    this.stopMetricsPolling()
    this.stopClock()
    // 清理 ECharts 实例
    if (this._detailCharts) {
      Object.values(this._detailCharts).forEach(c => c.dispose())
      this._detailCharts = null
    }
    if (this._distCharts) {
      Object.values(this._distCharts).forEach(c => c.dispose())
      this._distCharts = null
    }
  },
  methods: {
    getNodeKeyMetrics(node) {
      const key = 'node_' + node.id
      const data = this.tooltipData[key]
      if (!data || !data.items) return []
      // 卡片上已展示 IP 属性时少放一行指标，避免 160x160 卡片溢出
      const limit = this.nodeShowsIp(node) ? 2 : 3
      return data.items
        .filter(it => it.value && it.value !== '--' && it.value !== '')
        .slice(0, limit)
        .map(it => {
          let label = it.label || ''
          if (label.includes('（')) label = label.split('（')[0]
          return { label, value: it.value }
        })
    },
    // 卡片上展示的 IP：每个 IP 单独一行，超过 2 个时用 +N 收尾
    nodeIpList(node) {
      return (node.instanceIps || '').split(',').map(s => s.trim()).filter(Boolean)
    },
    // 日志过滤节点（1.0 分站）的 IP 是 REMOTE_ADDR（nginx 转发地址），不在卡片上展示
    nodeShowsIp(node) {
      return node.bindType !== 'LOG_FILTER' && this.nodeIpList(node).length > 0
    },
    nodeIpDisplay(node) {
      const ips = this.nodeIpList(node)
      if (ips.length <= 2) return ips
      return ips.slice(0, 2).concat('+' + (ips.length - 2))
    },
    // 悬停提示：区分实例 IP 与日志过滤（带请求前缀）
    nodeIpTitle(node) {
      const ips = this.nodeIpList(node)
      if (ips.length === 0) return ''
      const mode = node.bindType === 'LOG_FILTER'
        ? ('日志过滤 ' + (node.requestPrefix || ''))
        : '实例 IP'
      return mode + '：' + ips.join(', ')
    },
    nodeBaseClass(styleClass, nodeType) {
      if (nodeType === 'circle') return 'node-circle'
      const map = { client: 'node-client', nginx: 'node-nginx', ppos: 'node-ppos', ppos30: 'node-ppos' }
      return map[styleClass] || ''
    },
    nodeColorClass(styleClass) {
      return 'node-circle-blue'
    },
    computeLinkPath(source, target) {
      // 节点实际渲染尺寸固定 160x160（见样式 .node / .node-app / .node-nginx 等），
      // 尺寸表必须与样式保持一致：缺失 styleClass 会落到默认值，端点算进节点内部导致连线插入节点
      const nodeSizes = {
        app: { w: 160, h: 160 },
        client: { w: 160, h: 160 },
        nginx: { w: 160, h: 160 },
        ppos: { w: 160, h: 160 },
        ppos30: { w: 160, h: 160 },
        circle: { w: 160, h: 160 },
        daoyue10: { w: 160, h: 160 },
        daoyue10gz: { w: 160, h: 160 },
        daoyue30wj: { w: 160, h: 160 },
        daoyue30ws: { w: 160, h: 160 }
      }
      const defaultSize = { w: 160, h: 160 }
      const sx = source.posX || 0
      const sy = source.posY || 0
      const tx = target.posX || 0
      const ty = target.posY || 0
      const dx = tx - sx
      const dy = ty - sy
      const dist = Math.sqrt(dx * dx + dy * dy)
      if (dist < 1) return `M ${sx} ${sy}`
      const nx = dx / dist
      const ny = dy / dist
      const sSize = nodeSizes[source.styleClass] || defaultSize
      const tSize = nodeSizes[target.styleClass] || defaultSize

      const sIsCircle = source.nodeType === 'circle'
      const tIsCircle = target.nodeType === 'circle'

      const sSizeReal = sIsCircle ? nodeSizes.circle : sSize
      const tSizeReal = tIsCircle ? nodeSizes.circle : tSize

      let startX, startY, endX, endY
      if (sIsCircle) {
        const r = sSizeReal.w / 2
        startX = sx + nx * r
        startY = sy + ny * r
      } else {
        const sTx = Math.abs(nx) < 0.001 ? Infinity : (sSizeReal.w / 2) / Math.abs(nx)
        const sTy = Math.abs(ny) < 0.001 ? Infinity : (sSizeReal.h / 2) / Math.abs(ny)
        const sT = Math.min(sTx, sTy)
        startX = sx + nx * sT
        startY = sy + ny * sT
      }

      if (tIsCircle) {
        const r = tSizeReal.w / 2
        endX = tx - nx * r
        endY = ty - ny * r
      } else {
        const tTx = Math.abs(nx) < 0.001 ? Infinity : (tSizeReal.w / 2) / Math.abs(nx)
        const tTy = Math.abs(ny) < 0.001 ? Infinity : (tSizeReal.h / 2) / Math.abs(ny)
        const tT = Math.min(tTx, tTy)
        endX = tx - nx * tT
        endY = ty - ny * tT
      }

      const mx = (startX + endX) / 2
      const my = (startY + endY) / 2
      return `M ${Math.round(startX)} ${Math.round(startY)} Q ${Math.round(mx)} ${Math.round(my)} ${Math.round(endX)} ${Math.round(endY)}`
    },
    async loadTopology() {
      try {
        const [nodeRes, linkRes] = await Promise.all([
          service.get('/monitor/topology/nodes'),
          service.get('/monitor/topology/links')
        ])
        // 过滤 Tomcat 兜底节点：仅承载未映射实例的数据，不参与拓扑图渲染与“定位所有节点”
        const nodes = (nodeRes.data || [])
          .filter(n => n.styleClass !== 'tomcat')
          .map(n => ({
            ...n,
            styleClass: n.styleClass || 'default'
          }))
        this.topologyNodes = nodes
        const nodeMap = {}
        nodes.forEach(n => { nodeMap[n.id] = n })
        const links = (linkRes.data || []).map(link => {
          const source = nodeMap[link.sourceNodeId]
          const target = nodeMap[link.targetNodeId]
          const pathD = source && target ? this.computeLinkPath(source, target) : ''
          const sName = source ? source.nodeName : ''
          const tName = target ? target.nodeName : ''
          // 链路一律按链路ID唯一标识：客户端 -> 3 组 nginx、nginx -> 多个后端 若按节点名
          // 或 styleClass 组合，会共用同一个 key 导致 tooltip 标题与指标互相覆盖
          const linkKey = 'link_' + link.id
          return {
            ...link,
            pathD,
            linkKey,
            sourceNodeName: sName,
            targetNodeName: tName,
            sourceStyleClass: source ? source.styleClass : ''
          }
        })
        this.topologyLinks = links

        // 清理已不存在的节点的折叠状态（拓扑变更后 localStorage 可能残留旧ID）
        const validNodeIds = {}
        nodes.forEach(n => { validNodeIds[n.id] = true })
        Object.keys(this.collapsedIds).forEach(id => {
          if (!validNodeIds[id]) this.$delete(this.collapsedIds, id)
        })

        // 每个节点独立 tooltip（按节点ID），指标项沿用同类型（styleClass）的模板：
        // 应用节点 styleClass 均为 app，若按 styleClass 区分会导致多节点共用同一份数据互相覆盖
        this.topologyNodes.forEach(node => {
          const key = 'node_' + node.id
          if (!this.tooltipData[key]) {
            const templateKey = 'node_' + (node.styleClass === 'ppos' ? 'ppos10' : node.styleClass)
            const template = this.tooltipData[templateKey]
            let items = template && template.items
              ? template.items.map(it => ({ ...it }))
              : [{ label: '暂无指标数据', value: '--', real: false }]
            // 日志过滤型节点（1.0 分站）只有 4 项指标定义：QPS / 请求失败率 / 5xx错误率 / 4xx错误率，
            // TOP、进程存活、请求错误率（与失败率同口径）都不采集，从应用节点模板中剔除
            if (node.bindType === 'LOG_FILTER') {
              const logFilterSkip = ['接口请求量TOP', '进程存活', '请求错误率']
              items = items.filter(it => logFilterSkip.indexOf(it.label) === -1)
            }
            this.$set(this.tooltipData, key, { title: node.nodeName, items })
          }
        })
        this.topologyLinks.forEach(link => {
          if (link.linkKey && !this.tooltipData[link.linkKey]) {
            const title = link.linkName || (link.sourceNodeName && link.targetNodeName ? `${link.sourceNodeName} → ${link.targetNodeName}` : '链路')
            this.$set(this.tooltipData, link.linkKey, {
              title: title,
              items: this.buildLinkTooltipItems(link)
            })
          }
        })

        this.$nextTick(() => {
          this.unbindEvents()
          this.bindEvents()
        })
        // 拓扑先于指标加载完成时，重放一次已拉取的指标（避免 tooltip 注册晚于指标更新）
        if (this._lastNodeMetrics && this._lastNodeMetrics.length > 0) {
          this.updateNodeMetrics(this._lastNodeMetrics)
        }
        if (this._lastLinkMetrics && this._lastLinkMetrics.length > 0) {
          this.updateLinkMetrics(this._lastLinkMetrics)
        }
      } catch (e) {
        console.error('加载拓扑数据失败:', e)
      }
    },
    // ========== 拓扑编辑：节点拖动（自由布局）==========
    toggleEditMode() {
      if (!this.editMode) {
        this.editMode = true
      } else {
        this.editMode = false
        this.cancelLinkCreate()
        this.nodeDrag = { active: false, nodeId: '', startX: 0, startY: 0, originX: 0, originY: 0, moved: false, dirty: false }
      }
    },
    clampValue(v, min, max) {
      return Math.min(max, Math.max(min, v))
    },
    // 拖动只在编辑模式下生效：节点坐标直接采用库中值
    onNodeMouseDown(e, node) {
      if (!this.editMode) return
      if (this.linkCreate.active || e.button !== 0) return
      this.nodeDrag = {
        active: true,
        nodeId: node.id,
        startX: e.clientX,
        startY: e.clientY,
        originX: node.posX || 0,
        originY: node.posY || 0,
        moved: false,
        dirty: false
      }
      this._userInteracted = true
    },
    onNodeDragMove(e) {
      const drag = this.nodeDrag
      const dxScreen = e.clientX - drag.startX
      const dyScreen = e.clientY - drag.startY
      // 位移小于阈值视为点击，避免点节点开详情时误拖
      if (!drag.moved && Math.abs(dxScreen) < 4 && Math.abs(dyScreen) < 4) return
      drag.moved = true
      const node = this.topologyNodes.find(n => n.id === drag.nodeId)
      if (!node) return
      // 屏幕位移换算到世界坐标，并限制在画布内，避免把节点拖出可见区域
      node.posX = this.clampValue(Math.round(drag.originX + dxScreen / this.viewScale), 80, this.worldWidth - 80)
      node.posY = this.clampValue(Math.round(drag.originY + dyScreen / this.viewScale), 80, this.worldHeight - 80)
      drag.dirty = true
    },
    async onNodeDragEnd() {
      const drag = this.nodeDrag
      if (!drag.active) return
      this.nodeDrag = { active: false, nodeId: '', startX: 0, startY: 0, originX: 0, originY: 0, moved: false, dirty: false }
      if (drag.moved) this._lastDragEndAt = Date.now()
      if (!drag.dirty) return
      const node = this.topologyNodes.find(n => n.id === drag.nodeId)
      if (!node) return
      await this.saveNodeLayout([{ id: node.id, posX: node.posX, posY: node.posY }], drag)
    },
    async saveNodeLayout(list, drag) {
      try {
        await service.put('/monitor/topology/nodes/layout', list)
      } catch (e) {
        // 保存失败回滚到拖动前坐标，避免界面与库不一致
        if (drag && drag.nodeId) {
          const node = this.topologyNodes.find(n => n.id === drag.nodeId)
          if (node) {
            node.posX = drag.originX
            node.posY = drag.originY
          }
        }
        alert('保存布局失败: ' + (e.message || '未知错误'))
      }
    },
    onNodeClick(e, node) {
      // 刚结束拖动时不触发点击，避免拖完弹详情面板
      if (Date.now() - this._lastDragEndAt < 250) return
      if (this.linkCreate.active) {
        if (node.id === this.linkCreate.sourceId) {
          this.cancelLinkCreate()
          return
        }
        this.createLink(this.linkCreate.sourceId, node.id)
        return
      }
      this.openDetailPanel('node', node)
    },

    // ========== 拓扑编辑：节点/连线增删改 ==========
    onBlankContextMenu(e) {
      if (this.linkCreate.active) {
        this.cancelLinkCreate()
        return
      }
      const container = document.getElementById('canvas-container')
      if (!container) return
      const rect = container.getBoundingClientRect()
      const worldX = Math.round(this.worldCenterX + (e.clientX - rect.left - container.clientWidth / 2 - this.viewPanX) / this.viewScale)
      const worldY = Math.round(this.worldCenterY + (e.clientY - rect.top - container.clientHeight / 2 - this.viewPanY) / this.viewScale)
      this.showContextMenu(e.clientX, e.clientY, 'blank', { worldX, worldY })
    },
    onMenuAddNode() {
      const target = this.contextMenuTarget || {}
      this.hideContextMenu()
      this.openNodeForm('new', {
        posX: this.clampValue(target.worldX || 300, 80, this.worldWidth - 80),
        posY: this.clampValue(target.worldY || 300, 80, this.worldHeight - 80)
      })
    },
    onMenuEditNode() {
      const target = this.contextMenuTarget
      this.hideContextMenu()
      if (!target) return
      this.openNodeForm('edit', target)
    },
    async onMenuDeleteNode() {
      const target = this.contextMenuTarget
      this.hideContextMenu()
      if (!target) return
      try {
        const res = await service.get('/monitor/topology/nodes/' + target.id + '/impact')
        const impact = res.data || {}
        const msg = '确认删除节点「' + target.nodeName + '」？\n'
          + '将同时删除 ' + (impact.linkCount || 0) + ' 条关联链路、'
          + (impact.metricRowCount || 0) + ' 条指标数据、'
          + (impact.instanceCount || 0) + ' 个实例 IP 及对应告警规则。'
        if (!confirm(msg)) return
        await service.delete('/monitor/topology/nodes/' + target.id)
        if (this.collapsedIds[target.id]) this.$delete(this.collapsedIds, target.id)
        this.persistCollapseState()
        await this.loadTopology()
      } catch (e) {
        alert('删除节点失败: ' + (e.message || '未知错误'))
      }
    },
    async onMenuDeleteLink() {
      const target = this.contextMenuTarget
      this.hideContextMenu()
      if (!target) return
      const name = target.linkName || ((target.sourceNodeName || '') + ' -> ' + (target.targetNodeName || ''))
      if (!confirm('确认删除连线「' + name + '」？')) return
      try {
        await service.delete('/monitor/topology/links/' + target.id)
        await this.loadTopology()
      } catch (e) {
        alert('删除连线失败: ' + (e.message || '未知错误'))
      }
    },
    // 创建连线：选中起点后移动鼠标画虚线，再点击目标节点完成（比拖拽更不容易误触）
    onMenuCreateLink() {
      const target = this.contextMenuTarget
      this.hideContextMenu()
      if (!target) return
      const node = this.topologyNodes.find(n => n.id === target.id)
      if (!node) return
      this.linkCreate = {
        active: true,
        sourceId: node.id,
        startX: node.posX || 0,
        startY: node.posY || 0,
        curX: node.posX || 0,
        curY: node.posY || 0
      }
      this._userInteracted = true
    },
    cancelLinkCreate() {
      this.linkCreate.active = false
      this.linkCreate.sourceId = ''
    },
    async createLink(sourceId, targetId) {
      const source = this.topologyNodes.find(n => n.id === sourceId)
      const target = this.topologyNodes.find(n => n.id === targetId)
      const linkName = (source ? source.nodeName : '节点') + ' -> ' + (target ? target.nodeName : '节点')
      this.cancelLinkCreate()
      try {
        await service.post('/monitor/topology/links', {
          sourceNodeId: sourceId,
          targetNodeId: targetId,
          linkName: linkName
        })
        await this.loadTopology()
        // 创建成功后不再弹窗打断操作：连线会立即出现在图上，新链路无指标可从 tooltip 的“暂无指标数据”看出
      } catch (e) {
        alert('创建连线失败: ' + (e.message || '未知错误'))
      }
    },
    // 自由布局下把直接子节点在右侧均匀排列
    async onMenuArrangeChildren() {
      const parent = this.contextMenuTarget
      this.hideContextMenu()
      if (!parent) return
      const children = this.graphIndex.childrenById[parent.id] || []
      if (children.length === 0) return
      const nodeMap = {}
      this.topologyNodes.forEach(n => { nodeMap[n.id] = n })
      const p = nodeMap[parent.id]
      if (!p) return
      const gap = 190
      const startY = (p.posY || 0) - (children.length - 1) * gap / 2
      const updates = []
      children.forEach((cid, idx) => {
        const child = nodeMap[cid]
        if (!child) return
        child.posX = this.clampValue(Math.round((p.posX || 0) + 400), 80, this.worldWidth - 80)
        child.posY = this.clampValue(Math.round(startY + idx * gap), 80, this.worldHeight - 80)
        updates.push({ id: cid, posX: child.posX, posY: child.posY })
      })
      if (updates.length > 0) await this.saveNodeLayout(updates)
    },

    // ========== 节点表单（实例 IP 是节点属性） ==========
    openNodeForm(mode, node) {
      const isNew = mode === 'new'
      const source = node || {}
      const bindType = isNew ? 'IP' : (source.bindType || 'IP')
      const ips = isNew
        ? []
        : (source.instanceIps || '').split(',').map(s => s.trim()).filter(Boolean)
      this.nodeForm = {
        show: true,
        isNew: isNew,
        id: isNew ? '' : (source.id || ''),
        nodeName: isNew ? '' : (source.nodeName || ''),
        description: isNew ? '' : (source.description || ''),
        styleClass: isNew ? 'app' : (source.styleClass || 'app'),
        nodeType: isNew ? 'rect' : (source.nodeType || 'rect'),
        posX: source.posX || 0,
        posY: source.posY || 0,
        bindType: bindType,
        requestPrefix: isNew ? '' : (source.requestPrefix || ''),
        bindings: ips.map(ip => ({ instanceIp: ip }))
      }
    },
    closeNodeForm() {
      this.nodeForm.show = false
    },
    addBindingRow() {
      this.nodeForm.bindings.push({ instanceIp: '' })
    },
    removeBindingRow(idx) {
      this.nodeForm.bindings.splice(idx, 1)
    },
    onBindTypeChange() {
      if (this.nodeForm.bindType !== 'LOG_FILTER') this.nodeForm.requestPrefix = ''
    },
    /** 客户端节点不参与 IP 过滤：切换到客户端时清空已填的绑定，避免脏数据随提交带走 */
    onStyleClassChange() {
      if (this.nodeForm.styleClass !== 'client') return
      this.nodeForm.bindings = []
      this.nodeForm.bindType = 'IP'
      this.nodeForm.requestPrefix = ''
    },
    async saveNode() {
      const f = this.nodeForm
      if (!f.nodeName || !f.nodeName.trim()) {
        alert('请输入节点名称')
        return
      }
      if (STYLE_CLASS_OPTIONS.indexOf(f.styleClass) === -1) {
        alert('节点样式不合法')
        return
      }
      const isClient = f.styleClass === 'client'
      const ips = isClient ? [] : f.bindings.map(r => (r.instanceIp || '').trim()).filter(Boolean)
      const prefix = (f.requestPrefix || '').trim()
      if (!isClient && f.bindType === 'LOG_FILTER') {
        if (ips.length === 0) {
          alert('日志过滤绑定至少需要一个匹配 IP（REMOTE_ADDR）')
          return
        }
        if (!prefix.startsWith('/')) {
          alert('请求前缀必须以 / 开头，例如 /pposgjdf')
          return
        }
      }
      const payload = {
        nodeName: f.nodeName.trim(),
        description: f.description,
        styleClass: f.styleClass,
        nodeType: f.styleClass === 'client' ? 'circle' : 'rect',
        posX: f.posX,
        posY: f.posY,
        bindType: isClient ? 'IP' : f.bindType,
        instanceIps: isClient ? '' : ips.join(','),
        requestPrefix: !isClient && f.bindType === 'LOG_FILTER' ? prefix : ''
      }
      try {
        if (f.isNew) {
          await service.post('/monitor/topology/nodes', payload)
        } else {
          await service.put('/monitor/topology/nodes/' + f.id, payload)
        }
        this.closeNodeForm()
        await this.loadTopology()
      } catch (e) {
        alert('保存节点失败: ' + (e.message || '未知错误'))
      }
    },

    // ========== 拓扑折叠：交互与状态 ==========
    hasChildren(nodeId) {
      return (this.graphIndex.childrenById[nodeId] || []).length > 0
    },
    toggleCollapse(node) {
      if (!this.hasChildren(node.id)) return
      if (this.collapsedIds[node.id]) this.$delete(this.collapsedIds, node.id)
      else this.$set(this.collapsedIds, node.id, true)
      this.afterCollapseChange()
    },
    onMenuToggleCollapse() {
      const target = this.contextMenuTarget
      this.hideContextMenu()
      if (!target) return
      this.toggleCollapse(target)
    },
    // 全部展开：清空折叠状态
    expandAllNodes() {
      Object.keys(this.collapsedIds).forEach(id => this.$delete(this.collapsedIds, id))
      this.afterCollapseChange()
    },
    afterCollapseChange() {
      this.persistCollapseState()
      // 可见集变化会重建部分节点 DOM，tooltip 依赖的 mouseenter 监听需重新绑定
      this.$nextTick(() => {
        this.unbindEvents()
        this.bindEvents()
      })
    },
    persistCollapseState() {
      try {
        const ids = Object.keys(this.collapsedIds)
        if (ids.length === 0) localStorage.removeItem(COLLAPSE_STORAGE_KEY)
        else localStorage.setItem(COLLAPSE_STORAGE_KEY, JSON.stringify(ids))
      } catch (e) {
        // localStorage 不可用时忽略，折叠功能本身不受影响
      }
    },
    loadCollapseState() {
      try {
        const raw = localStorage.getItem(COLLAPSE_STORAGE_KEY)
        if (!raw) return
        const ids = JSON.parse(raw)
        if (Array.isArray(ids)) ids.forEach(id => this.$set(this.collapsedIds, id, true))
      } catch (e) {
        // 解析失败按默认全展开处理
      }
    },
    // 展开指定节点的所有祖先（告警定位到被收起的节点时使用）
    ensureVisible(nodeId) {
      const parentById = this.graphIndex.parentById
      let changed = false
      let current = parentById[nodeId]
      let guard = 0
      while (current && guard < 50) {
        if (this.collapsedIds[current]) {
          this.$delete(this.collapsedIds, current)
          changed = true
        }
        current = parentById[current]
        guard++
      }
      if (!changed) return Promise.resolve()
      this.afterCollapseChange()
      return this.$nextTick()
    },
    // 被收起子树里的告警等级：用于角标配色与父节点闪烁，避免“收起后看不到告警”
    getHiddenAlertLevel(nodeId) {
      if (!this.collapsedIds[nodeId] || !this.activeAlerts || this.activeAlerts.length === 0) return ''
      const childrenById = this.graphIndex.childrenById
      const hiddenNodes = {}
      const walk = id => childrenById[id].forEach(childId => { hiddenNodes[childId] = true; walk(childId) })
      walk(nodeId)
      let level = ''
      for (const alert of this.activeAlerts) {
        let hit = false
        if (alert.targetType === 'node') {
          hit = !!hiddenNodes[alert.targetId]
        } else if (alert.targetType === 'link') {
          const link = this.topologyLinks.find(l => l.id === alert.targetId)
          hit = !!link && (!!hiddenNodes[link.sourceNodeId] || !!hiddenNodes[link.targetNodeId])
        }
        if (!hit) continue
        if (alert.alertLevel === 'critical') return 'critical'
        level = 'warning'
      }
      return level
    },

    async loadMetrics() {
      try {
        const [nodeRes, linkRes] = await Promise.all([
          service.get('/monitor/display/nodeMetrics'),
          service.get('/monitor/display/linkMetrics')
        ])
        this.updateNodeMetrics(nodeRes.data || [])
        this.updateLinkMetrics(linkRes.data || [])
      } catch (e) {
        console.error('加载监控指标失败:', e)
      }
    },
    updateNodeMetrics(list) {
      this._lastNodeMetrics = list || []
      const map = {}
      list.forEach(item => {
        // 节点指标一律按 nodeId 定位：应用节点 styleClass 均为 app，按 styleClass 会相互覆盖
        const key = item.nodeId ? 'node_' + item.nodeId : null
        if (key) {
          if (!map[key]) map[key] = {}
          map[key][item.metricName] = {
            value: item.metricValue || '--',
            unit: item.metricUnit || '',
            sortOrder: item.sortOrder
          }
        }
      })
      for (const key in this.tooltipData) {
        if (key.startsWith('node_') && map[key]) {
          this.tooltipData[key].items.forEach(it => {
            // 用原始标签匹配，避免 label 追加单位后后续轮询匹配不到
            const baseLabel = it._originalLabel || it.label
            const data = map[key][baseLabel]
            if (data !== undefined) {
              it.sortOrder = data.sortOrder
              if (!it._originalLabel) it._originalLabel = it.label
              const unit = data.unit ? `（${data.unit}）` : ''
              it.label = baseLabel + unit
              it.value = data.value
            }
          })
          this.tooltipData[key].items.sort((a, b) => (a.sortOrder || 9999) - (b.sortOrder || 9999))
        }
      }
    },
    // 链路 tooltip 指标项：客户端出口链路复用 nginx 访问日志指标模板，
    // 其余链路（nginx -> 后端）暂无指标定义，展示空态占位
    buildLinkTooltipItems(link) {
      const template = link.sourceStyleClass === 'client' ? this.tooltipData.tpl_link_client : null
      if (template && template.items) {
        return template.items.map(it => ({ ...it }))
      }
      return [{ label: '暂无指标数据', value: '--', real: false }]
    },
    updateLinkMetrics(list) {
      this._lastLinkMetrics = list || []
      const map = {}
      list.forEach(item => {
        // 链路指标一律按 linkId 定位：客户端 -> 3 组 nginx 若按节点名合并，指标会互相覆盖
        if (!item.linkId) return
        const key = 'link_' + item.linkId
        if (!map[key]) map[key] = {}
        map[key][item.metricName] = {
          value: item.metricValue || '--',
          unit: item.metricUnit || '',
          sortOrder: item.sortOrder
        }
      })
      for (const key in this.tooltipData) {
        if (key.startsWith('link_') && map[key]) {
          this.tooltipData[key].items.forEach(it => {
            const baseLabel = it._originalLabel || it.label
            let data = map[key][baseLabel]
            // 精确匹配失败时，尝试模糊匹配兜底
            if (data === undefined) {
              const matchedKey = Object.keys(map[key]).find(k => k.includes(baseLabel) || baseLabel.includes(k))
              if (matchedKey) data = map[key][matchedKey]
            }
            if (data !== undefined) {
              it.sortOrder = data.sortOrder
              if (!it._originalLabel) it._originalLabel = it.label
              const unit = data.unit ? `（${data.unit}）` : ''
              it.label = baseLabel + unit
              it.value = data.value
            }
          })
          this.tooltipData[key].items.sort((a, b) => (a.sortOrder || 9999) - (b.sortOrder || 9999))
        }
      }
    },
    bindEvents() {
      const nodes = document.querySelectorAll('.node[data-node]')
      const links = document.querySelectorAll('.link-hit[data-link]')
      const tooltip = document.getElementById('tooltip')

      this._nodeHandlers = []
      this._linkHandlers = []

      nodes.forEach(node => {
        const dataId = node.getAttribute('data-node')
        const enterFn = e => this.renderTooltip(dataId, e, node)
        const moveFn = e => this.positionTooltip(e, node)
        const leaveFn = () => this.scheduleHideTooltip()
        node.addEventListener('mouseenter', enterFn)
        node.addEventListener('mousemove', moveFn)
        node.addEventListener('mouseleave', leaveFn)
        this._nodeHandlers.push({ el: node, enterFn, moveFn, leaveFn })
      })

      links.forEach(link => {
        const dataId = link.getAttribute('data-link')
        const enterFn = e => this.renderTooltip(dataId, e, link)
        const moveFn = e => this.positionTooltip(e, link)
        const leaveFn = () => this.scheduleHideTooltip()
        link.addEventListener('mouseenter', enterFn)
        link.addEventListener('mousemove', moveFn)
        link.addEventListener('mouseleave', leaveFn)
        this._linkHandlers.push({ el: link, enterFn, moveFn, leaveFn })
      })

      if (tooltip) {
        this._tooltipEnterFn = () => this.cancelHideTooltip()
        this._tooltipLeaveFn = () => this.hideTooltip()
        tooltip.addEventListener('mouseenter', this._tooltipEnterFn)
        tooltip.addEventListener('mouseleave', this._tooltipLeaveFn)
      }
    },
    unbindEvents() {
      if (this._nodeHandlers) {
        this._nodeHandlers.forEach(({ el, enterFn, moveFn, leaveFn }) => {
          el.removeEventListener('mouseenter', enterFn)
          el.removeEventListener('mousemove', moveFn)
          el.removeEventListener('mouseleave', leaveFn)
        })
      }
      if (this._linkHandlers) {
        this._linkHandlers.forEach(({ el, enterFn, moveFn, leaveFn }) => {
          el.removeEventListener('mouseenter', enterFn)
          el.removeEventListener('mousemove', moveFn)
          el.removeEventListener('mouseleave', leaveFn)
        })
      }
      const tooltip = document.getElementById('tooltip')
      if (tooltip && this._tooltipEnterFn) {
        tooltip.removeEventListener('mouseenter', this._tooltipEnterFn)
        tooltip.removeEventListener('mouseleave', this._tooltipLeaveFn)
      }
    },
    scheduleHideTooltip() {
      this._hideTimer = setTimeout(() => {
        this.hideTooltip()
      }, 200)
    },
    cancelHideTooltip() {
      if (this._hideTimer) {
        clearTimeout(this._hideTimer)
        this._hideTimer = null
      }
    },
    renderTooltip(dataId, e, targetEl) {
      let data = this.tooltipData[dataId]
      if (!data) {
        data = this.buildDefaultTooltip(dataId)
        if (!data) return
      }
      this.currentTooltipDataId = dataId
      const tooltip = document.getElementById('tooltip')
      this.currentTooltip = {
        title: data.title,
        items: data.items
      }
      this.$nextTick(() => {
        tooltip.classList.add('show')
        tooltip.style.opacity = '1'
        this.positionTooltip(e, targetEl)
      })
    },
    buildDefaultTooltip(dataId) {
      if (dataId.startsWith('node_')) {
        const node = this.topologyNodes.find(n => ('node_' + n.id) === dataId)
        return {
          title: node ? node.nodeName : dataId,
          items: [{ label: '暂无指标数据', value: '--', real: false }]
        }
      }
      if (dataId.startsWith('link_')) {
        const link = this.topologyLinks.find(l => l.linkKey === dataId)
        const title = link ? (link.linkName || `${link.sourceNodeName || ''} → ${link.targetNodeName || ''}`) : dataId
        return {
          title: title,
          items: [{ label: '暂无指标数据', value: '--', real: false }]
        }
      }
      return null
    },
    hideTooltip() {
      if (this._hideTimer) {
        clearTimeout(this._hideTimer)
        this._hideTimer = null
      }
      const tooltip = document.getElementById('tooltip')
      tooltip.classList.remove('show')
      tooltip.style.opacity = '0'
      tooltip.classList.remove('arrow-top', 'arrow-bottom')
    },
    positionTooltip(e, targetEl) {
      const tooltip = document.getElementById('tooltip')
      const vw = window.innerWidth
      const vh = window.innerHeight
      const margin = 12
      const arrowHeight = 8
      const gap = 10
      const ttRect = tooltip.getBoundingClientRect()
      let x, y, arrowPos
      let arrowClass = 'arrow-top'

      if (targetEl) {
        const rect = targetEl.getBoundingClientRect()
        const centerX = rect.left + rect.width / 2

        y = rect.bottom + gap + arrowHeight
        arrowClass = 'arrow-top'

        if (y + ttRect.height > vh - margin) {
          y = rect.top - gap - arrowHeight - ttRect.height
          arrowClass = 'arrow-bottom'
        }

        x = centerX - ttRect.width / 2
        arrowPos = centerX - x

        if (x < margin) {
          arrowPos = centerX - margin
          x = margin
        }
        if (x + ttRect.width > vw - margin) {
          x = vw - margin - ttRect.width
          arrowPos = centerX - x
        }

        arrowPos = Math.max(20, Math.min(arrowPos, ttRect.width - 20))
      } else {
        x = e.clientX + 20
        y = e.clientY + 20

        if (x + ttRect.width > vw - margin) x = e.clientX - ttRect.width - 20
        if (x < margin) x = margin
        if (y + ttRect.height > vh - margin) y = e.clientY - ttRect.height - 20
        if (y < margin) y = margin

        arrowPos = e.clientX - x
        arrowPos = Math.max(20, Math.min(arrowPos, ttRect.width - 20))
        arrowClass = e.clientY > vh / 2 ? 'arrow-bottom' : 'arrow-top'
      }

      tooltip.classList.remove('arrow-top', 'arrow-bottom')
      tooltip.classList.add(arrowClass)
      tooltip.style.setProperty('--arrow-left', arrowPos + 'px')
      tooltip.style.left = x + 'px'
      tooltip.style.top = y + 'px'
    },
    applyTransform() {
      const world = document.getElementById('world')
      if (!world) return
      // #world 以画布中心为定位基准：先平移 -50% 居中，再叠加用户平移与缩放
      world.style.transform = `translate(calc(-50% + ${this.viewPanX}px), calc(-50% + ${this.viewPanY}px)) scale(${this.viewScale})`
    },
    initZoomPan() {
      const container = document.getElementById('canvas-container')
      if (!container) return
      this._onWheel = this.onWheel.bind(this)
      this._onMouseDown = this.onMouseDown.bind(this)
      this._onMouseMove = this.onMouseMove.bind(this)
      this._onMouseUp = this.onMouseUp.bind(this)
      container.addEventListener('wheel', this._onWheel, { passive: false })
      container.addEventListener('mousedown', this._onMouseDown)
      document.addEventListener('mousemove', this._onMouseMove)
      document.addEventListener('mouseup', this._onMouseUp)
    },
    unbindZoomPan() {
      const container = document.getElementById('canvas-container')
      if (container && this._onWheel) container.removeEventListener('wheel', this._onWheel)
      if (container && this._onMouseDown) container.removeEventListener('mousedown', this._onMouseDown)
      if (this._onMouseMove) document.removeEventListener('mousemove', this._onMouseMove)
      if (this._onMouseUp) document.removeEventListener('mouseup', this._onMouseUp)
    },
    onWheel(e) {
      e.preventDefault()
      this._userInteracted = true
      const container = document.getElementById('canvas-container')
      if (!container) return
      const rect = container.getBoundingClientRect()
      const mx = e.clientX - rect.left
      const my = e.clientY - rect.top
      const oldScale = this.viewScale
      const factor = e.deltaY < 0 ? 1.1 : 0.9
      let newScale = oldScale * factor
      newScale = Math.max(this.minScale, Math.min(this.maxScale, newScale))
      const f = newScale / oldScale
      // 以鼠标位置为锚点缩放，同步补偿平移量，避免画面围绕画布中心跳动
      this.viewPanX += (mx - container.clientWidth / 2 - this.viewPanX) * (1 - f)
      this.viewPanY += (my - container.clientHeight / 2 - this.viewPanY) * (1 - f)
      this.viewScale = newScale
      this.applyTransform()
    },
    onMouseDown(e) {
      if (e.target.closest('.node') || e.target.closest('.link-hit') || e.target.closest('.tooltip')) return
      this.isDragging = true
      this.dragStartX = e.clientX
      this.dragStartY = e.clientY
      this.panStartX = this.viewPanX
      this.panStartY = this.viewPanY
      const container = document.getElementById('canvas-container')
      if (container) container.style.cursor = 'grabbing'
    },
    // 屏幕坐标转世界坐标；右键菜单与连线创建使用同一换算公式，修改时需三处同步
    updateMouseCoord(e) {
      const container = document.getElementById('canvas-container')
      if (!container) return
      const rect = container.getBoundingClientRect()
      const sx = e.clientX - rect.left
      const sy = e.clientY - rect.top
      this.mouseWorldX = Math.round(this.worldCenterX + (sx - container.clientWidth / 2 - this.viewPanX) / this.viewScale)
      this.mouseWorldY = Math.round(this.worldCenterY + (sy - container.clientHeight / 2 - this.viewPanY) / this.viewScale)
    },
    onMouseMove(e) {
      this.updateMouseCoord(e)
      // 连线创建：实时更新虚线终点（世界坐标）
      if (this.linkCreate.active) {
        const container = document.getElementById('canvas-container')
        if (container) {
          const rect = container.getBoundingClientRect()
          this.linkCreate.curX = Math.round(this.worldCenterX + (e.clientX - rect.left - container.clientWidth / 2 - this.viewPanX) / this.viewScale)
          this.linkCreate.curY = Math.round(this.worldCenterY + (e.clientY - rect.top - container.clientHeight / 2 - this.viewPanY) / this.viewScale)
        }
      }
      // 节点拖动优先于画布平移
      if (this.nodeDrag.active) {
        this.onNodeDragMove(e)
        return
      }
      if (!this.isDragging) return
      this._userInteracted = true
      const dx = e.clientX - this.dragStartX
      const dy = e.clientY - this.dragStartY
      this.viewPanX = this.panStartX + dx
      this.viewPanY = this.panStartY + dy
      this.applyTransform()
    },
    onMouseUp(e) {
      if (this.nodeDrag.active) {
        this.onNodeDragEnd()
        return
      }
      if (!this.isDragging) return
      this.isDragging = false
      const container = document.getElementById('canvas-container')
      if (container) container.style.cursor = 'grab'
    },
    resizeLayout() {
      const container = document.getElementById('canvas-container')
      if (!container) return
      // 用户手动缩放/平移后不再自动适配，避免窗口尺寸变化覆盖当前视角
      if (!this._userInteracted) {
        this.viewScale = Math.min(container.clientWidth / this.worldWidth, container.clientHeight / this.worldHeight)
        this.viewPanX = 0
        this.viewPanY = 0
        this.applyTransform()
      }
    },

    // 右键菜单
    onNodeContextMenu(e, node) {
      e.preventDefault()
      this.showContextMenu(e.clientX, e.clientY, 'node', node)
    },
    onLinkContextMenu(e, link) {
      e.preventDefault()
      this.showContextMenu(e.clientX, e.clientY, 'link', link)
    },
    showContextMenu(x, y, type, target) {
      this.hideContextMenu()
      this.$nextTick(() => {
        this.contextMenu = { show: true, x, y, type }
        this.contextMenuTarget = target
        this.$nextTick(() => {
          this._outsideClickFn = (e) => {
            if (!e.target.closest('.context-menu')) {
              this.hideContextMenu()
            }
          }
          document.addEventListener('click', this._outsideClickFn)
        })
      })
    },
    hideContextMenu() {
      this.contextMenu.show = false
      if (this._outsideClickFn) {
        document.removeEventListener('click', this._outsideClickFn)
        this._outsideClickFn = null
      }
    },

    // ========== 趋势图相关方法 ==========

    onMetricClick(item) {
      const dataId = this.currentTooltipDataId
      if (!dataId) return
      let metricName = item._originalLabel || item.label
      if (metricName.includes('（')) metricName = metricName.split('（')[0]
      const metricUnit = item.unit || ''
      this.hideTooltip()
      this.openTrendChart(dataId.startsWith('node_') ? 'node' : 'link', dataId, metricName, metricUnit)
    },

    openTrendChart(type, dataId, metricName, metricUnit) {
      const today = new Date()
      const dateStr = this.formatDate(today)
      this.trendChart = {
        show: true,
        title: (this.tooltipData[dataId] ? this.tooltipData[dataId].title : '指标趋势') + ' - ' + metricName,
        targetDate: dateStr,
        compareDate: '',
        compareMode: 'none',
        targetData: [],
        compareData: [],
        compareFallback: false,
        textMode: false,
        textRows: [],
        loading: false,
        type,
        dataId,
        metricName,
        metricUnit,
        avgChange: null,
        latestChange: null
      }
      this.$nextTick(() => {
        this.loadTrendData()
      })
    },

    closeTrendChart() {
      this.trendChart.show = false
      this.trendHover.show = false
      const canvas = this.$refs.trendCanvas
      if (canvas) {
        canvas._hoverBound = false
        canvas.removeEventListener('mousemove', this.onTrendCanvasMouseMove)
        canvas.removeEventListener('mouseleave', this.onTrendCanvasMouseLeave)
      }
    },

    onTargetDateChange() {
      this.trendChart.compareData = []
      if (this.trendChart.compareMode === 'yesterday') {
        this.trendChart.compareDate = this.formatDate(this.shiftDate(this.trendChart.targetDate, -1))
      } else if (this.trendChart.compareMode === 'lastweek') {
        this.trendChart.compareDate = this.formatDate(this.shiftDate(this.trendChart.targetDate, -7))
      }
      this.loadTrendData()
    },

    onCompareModeChange() {
      this.trendChart.compareData = []
      if (this.trendChart.compareMode === 'yesterday') {
        this.trendChart.compareDate = this.formatDate(this.shiftDate(this.trendChart.targetDate, -1))
      } else if (this.trendChart.compareMode === 'lastweek') {
        this.trendChart.compareDate = this.formatDate(this.shiftDate(this.trendChart.targetDate, -7))
      } else if (this.trendChart.compareMode === 'custom') {
        if (!this.trendChart.compareDate) {
          this.trendChart.compareDate = this.formatDate(this.shiftDate(this.trendChart.targetDate, -1))
        }
      } else {
        this.trendChart.compareDate = ''
      }
      this.loadTrendData()
    },

    onCompareDateChange() {
      this.loadTrendData()
    },

    async loadTrendData() {
      const tc = this.trendChart
      if (!tc.targetDate || !tc.metricName) return
      tc.loading = true
      try {
        let id = ''
        if (tc.type === 'node') {
          id = this.resolveNodeId(tc.dataId)
        } else {
          id = this.resolveLinkId(tc.dataId)
        }
        if (!id) {
          tc.loading = false
          return
        }
        const params = { metricName: tc.metricName, date: tc.targetDate }
        if (tc.type === 'node') params.nodeId = id
        else params.linkId = id
        const url = tc.type === 'node' ? '/monitor/history/node' : '/monitor/history/link'
        const res = await service.get(url, { params })
        tc.targetData = (res.data || []).map(d => ({
          value: parseFloat(d.metricValue) || 0,
          rawValue: d.metricValue,
          time: d.sourceTime
        }))
        if (tc.compareMode !== 'none' && tc.compareDate) {
          tc.compareFallback = false
          const compareParams = { metricName: tc.metricName, date: tc.compareDate }
          if (tc.type === 'node') compareParams.nodeId = id
          else compareParams.linkId = id
          const compareRes = await service.get(url, { params: compareParams })
          let compareList = compareRes.data || []
          // 对比日无数据时（周末/节假日未采集），自动向前逐日查找最近有数据的一天，最多回溯30天
          if (!compareList.length && tc.compareMode !== 'custom') {
            let cursor = this.parseLocalDate(tc.compareDate)
            for (let i = 0; i < 30 && !compareList.length; i++) {
              cursor.setDate(cursor.getDate() - 1)
              const fallbackDate = this.formatDate(cursor)
              const fallbackRes = await service.get(url, { params: { ...compareParams, date: fallbackDate } })
              compareList = fallbackRes.data || []
              if (compareList.length) {
                tc.compareDate = fallbackDate
                tc.compareFallback = true
              }
            }
          }
          tc.compareData = compareList.map(d => ({
            value: parseFloat(d.metricValue) || 0,
            rawValue: d.metricValue,
            time: d.sourceTime
          }))
        } else {
          tc.compareData = []
        }
        // 文本型指标（值含非数字，如TOP访问IP、终端分布）：改用双日对照列表，避免强画为0值直线
        const isNumericSeries = arr => arr.some(d => /^-?\d+(\.\d+)?$/.test(String(d.rawValue).trim()))
        tc.textMode = tc.targetData.length ? !isNumericSeries(tc.targetData)
          : (tc.compareData.length ? !isNumericSeries(tc.compareData) : false)
        if (tc.textMode) {
          tc.avgChange = null
          tc.latestChange = null
          this.buildTrendTextRows()
        } else {
          this.computeAvgAndChange()
        }
        this.$nextTick(() => {
          if (!tc.textMode) this.drawTrendChart()
        })
      } catch (e) {
        console.error('加载趋势数据失败:', e)
      } finally {
        tc.loading = false
      }
    },

    resolveNodeId(dataId) {
      // dataId 格式：node_<32位节点ID>
      return dataId && dataId.indexOf('node_') === 0 ? dataId.substring('node_'.length) : null
    },

    resolveLinkId(dataId) {
      const link = this.topologyLinks.find(l => l.linkKey === dataId)
      return link ? link.id : null
    },

    formatDate(date) {
      const d = new Date(date)
      const y = d.getFullYear()
      const m = String(d.getMonth() + 1).padStart(2, '0')
      const day = String(d.getDate()).padStart(2, '0')
      return `${y}-${m}-${day}`
    },

    // 按本地时区解析 yyyy-MM-dd 日期字符串（避免 new Date('yyyy-MM-dd') 按UTC解析导致日期偏移一天）
    parseLocalDate(dateStr) {
      const parts = String(dateStr).slice(0, 10).split('-')
      return new Date(parseInt(parts[0], 10), parseInt(parts[1], 10) - 1, parseInt(parts[2], 10))
    },

    // 在目标日期基础上偏移指定天数，返回本地日期对象
    shiftDate(dateStr, days) {
      const d = this.parseLocalDate(dateStr)
      d.setDate(d.getDate() + days)
      return d
    },

    // 时间字符串(含 HH:MM) -> 当日分钟数，用于两日曲线按时刻对齐；解析失败返回 null
    timeToMinutes(timeStr) {
      const hm = timeStr ? String(timeStr).substring(11, 16) : ''
      const parts = hm.split(':')
      if (parts.length !== 2) return null
      const m = parseInt(parts[0], 10) * 60 + parseInt(parts[1], 10)
      return isNaN(m) ? null : m
    },

    // 当日分钟数 -> HH:MM 显示
    minutesToLabel(m) {
      const h = String(Math.floor(m / 60)).padStart(2, '0')
      const mm = String(Math.round(m % 60)).padStart(2, '0')
      return h + ':' + mm
    },

    // 文本型指标：构建双日对照行（时刻 ±3分钟就近匹配），对比值不同的行高亮标记
    buildTrendTextRows() {
      const tc = this.trendChart
      const rows = tc.targetData.map(d => {
        const timeStr = d.time ? d.time.substring(11, 16) : ''
        const m = this.timeToMinutes(d.time)
        let compare = ''
        let bestDiff = Infinity
        tc.compareData.forEach(c => {
          const cm = this.timeToMinutes(c.time)
          if (cm === null || m === null) return
          const diff = Math.abs(cm - m)
          if (diff < bestDiff) { bestDiff = diff; compare = c.rawValue }
        })
        if (bestDiff > 3) compare = ''
        return { time: timeStr, target: d.rawValue, compare, changed: compare !== '' && String(compare) !== String(d.rawValue) }
      })
      // 补充对比日独有的时刻行（目标日无该时刻数据），避免对比信息丢失
      if (tc.compareData.length) {
        const targetMinutes = tc.targetData.map(d => this.timeToMinutes(d.time))
        tc.compareData.forEach(c => {
          const cm = this.timeToMinutes(c.time)
          if (cm === null) return
          const hasNear = targetMinutes.some(tm => tm !== null && Math.abs(tm - cm) <= 3)
          if (!hasNear) {
            rows.push({ time: c.time ? c.time.substring(11, 16) : '', target: '', compare: c.rawValue, changed: true })
          }
        })
        rows.sort((a, b) => (a.time < b.time ? -1 : a.time > b.time ? 1 : 0))
      }
      tc.textRows = rows
    },

    computeAvgAndChange() {
      const tc = this.trendChart
      if (!tc.targetData.length) {
        tc.avgChange = null
        tc.latestChange = null
        return
      }
      const targetLatest = tc.targetData[tc.targetData.length - 1].value
      if (tc.compareData && tc.compareData.length) {
        // 均值变化：只在两日时刻区间的交集内取平均，
        // 避免拿目标日的白天高值段去比对比日含深夜低值的全天平均导致涨幅虚高
        const toMs = arr => arr.map(d => this.timeToMinutes(d.time)).filter(m => m !== null)
        const tMs = toMs(tc.targetData)
        const cMs = toMs(tc.compareData)
        let tAvgArr = tc.targetData
        let cAvgArr = tc.compareData
        if (tMs.length && cMs.length) {
          const lo = Math.max(Math.min(...tMs), Math.min(...cMs))
          const hi = Math.min(Math.max(...tMs), Math.max(...cMs))
          if (hi >= lo) {
            const inRange = arr => arr.filter(d => {
              const m = this.timeToMinutes(d.time)
              return m !== null && m >= lo && m <= hi
            })
            const tF = inRange(tc.targetData)
            const cF = inRange(tc.compareData)
            if (tF.length && cF.length) { tAvgArr = tF; cAvgArr = cF }
          }
        }
        const targetAvg = tAvgArr.reduce((s, d) => s + d.value, 0) / tAvgArr.length
        const compareAvg = cAvgArr.reduce((s, d) => s + d.value, 0) / cAvgArr.length
        // 最新值变化：用目标日最新点与对比日同时刻（±5分钟）值比较，
        // 避免拿目标日下午的值去对比对比日深夜的值导致结论失真
        const targetLatestM = this.timeToMinutes(tc.targetData[tc.targetData.length - 1].time)
        let compareLatest = null
        if (targetLatestM !== null) {
          let bestDiff = Infinity
          tc.compareData.forEach(d => {
            const m = this.timeToMinutes(d.time)
            if (m === null) return
            const diff = Math.abs(m - targetLatestM)
            if (diff < bestDiff) { bestDiff = diff; compareLatest = d.value }
          })
          if (bestDiff > 5) compareLatest = null
        }
        if (compareLatest === null) compareLatest = tc.compareData[tc.compareData.length - 1].value
        tc.avgChange = compareAvg === 0 ? 0 : ((targetAvg - compareAvg) / compareAvg) * 100
        tc.latestChange = compareLatest === 0 ? 0 : ((targetLatest - compareLatest) / compareLatest) * 100
      } else {
        tc.avgChange = null
        tc.latestChange = null
      }
    },

    getTrendChangeClass(val) {
      if (val >= 0.001) return 'up'
      if (val <= -0.001) return 'down'
      return ''
    },

    getTrendChangeText(val) {
      if (val === null || val === undefined || Math.abs(val) < 0.001) return '0.0%'
      const arrow = val > 0 ? '\u2191' : '\u2193'
      return arrow + Math.abs(val).toFixed(1) + '%'
    },

    // ========== 右滑详情面板方法 ==========

    openDetailPanel(type, item) {
      this.hideTooltip()
      const title = type === 'node' ? item.nodeName : (item.linkName || '链路')
      this.detailPanel = {
        show: true,
        title,
        type,
        id: item.id,
        hours: 1,
        loading: false,
        trendData: {},
        textMetrics: [],
        distributionMetrics: []
      }
      this.loadDetailTrend()
    },

    closeDetailPanel() {
      this.detailPanel.show = false
      if (this._detailCharts) {
        Object.values(this._detailCharts).forEach(chart => chart.dispose())
        this._detailCharts = null
      }
      if (this._distCharts) {
        Object.values(this._distCharts).forEach(chart => chart.dispose())
        this._distCharts = null
      }
    },

    // 跳转到实例详情页面（携带当前节点ID）
    goToInstancePage() {
      const nodeId = this.detailPanel.id
      if (!nodeId) return
      this.$router.push({ name: 'InstancePage', query: { nodeId } }).catch(() => {})
    },

    async onDetailHoursChange(hours) {
      if (this.detailPanel.hours === hours) return
      this.detailPanel.hours = hours
      await this.loadDetailTrend()
    },

    async loadDetailTrend() {
      const dp = this.detailPanel
      if (!dp.id) return
      dp.loading = true
      try {
        const url = dp.type === 'node' ? '/monitor/trend/node' : '/monitor/trend/link'
        const paramKey = dp.type === 'node' ? 'nodeId' : 'linkId'
        const res = await service.get(url, { params: { [paramKey]: dp.id, hours: dp.hours } })
        const list = res.data || []
        const grouped = {}
        list.forEach(d => {
          const name = d.metricName || '未知指标'
          if (!grouped[name]) grouped[name] = []
          grouped[name].push({
            time: d.sourceTime ? d.sourceTime.substring(11, 16) : '',
            fullTime: d.sourceTime,
            value: parseFloat(d.metricValue) || 0,
            rawValue: d.metricValue
          })
        })
        // 区分数值型指标（画折线图）、分布型指标（饼图/柱状图）和文本型指标（显示文本卡片）
        const numericGrouped = {}
        const textMetrics = []
        const distributionMetrics = []
        const distributionMetricNames = ['TOP访问IP', '终端分布', 'UA类型', 'TOP请求路径', '上游状态码分布']
        Object.keys(grouped).forEach(name => {
          const arr = grouped[name]
          if (distributionMetricNames.includes(name)) {
            const latest = arr[arr.length - 1]
            if (latest) {
              const parsed = this.parseDistributionValue(name, latest.rawValue)
              if (parsed && parsed.length > 0) {
                distributionMetrics.push({ name, data: parsed })
              } else {
                textMetrics.push({ name, value: latest.rawValue || '' })
              }
            }
          } else {
            const isNumeric = arr.some(d => /^-?\d+(\.\d+)?$/.test(String(d.rawValue).trim()))
            if (isNumeric) {
              numericGrouped[name] = arr
            } else {
              const latest = arr[arr.length - 1]
              textMetrics.push({ name, value: latest ? String(latest.rawValue) : '' })
            }
          }
        })
        // 展示全部数值型指标（如 nginx 有 13 个数值指标），不再限制最多 10 个图表，面板内滚动查看
        dp.trendData = numericGrouped
        dp.textMetrics = textMetrics
        dp.distributionMetrics = distributionMetrics
        this.$nextTick(() => {
          this.renderDetailCharts()
          this.renderDistributionCharts()
        })
      } catch (e) {
        console.error('加载趋势数据失败:', e)
      } finally {
        dp.loading = false
      }
    },

    renderDetailCharts() {
      if (!window.echarts) return
      if (!this._detailCharts) this._detailCharts = {}
      const data = this.detailPanel.trendData
      Object.keys(data).forEach((metricName, idx) => {
        const safeId = 'detailChart_' + idx
        const dom = document.getElementById(safeId)
        if (!dom) return
        if (this._detailCharts[safeId]) {
          this._detailCharts[safeId].dispose()
        }
        const chart = window.echarts.init(dom, null, { renderer: 'canvas' })
        const seriesData = data[metricName].map(d => d.value)
        const xData = data[metricName].map(d => d.time)
        const hasData = seriesData.length > 0
        const allZero = hasData && seriesData.every(v => v === 0)
        const option = {
          backgroundColor: 'transparent',
          grid: { top: 30, right: 20, bottom: 30, left: 50 },
          tooltip: { trigger: 'axis', backgroundColor: 'rgba(10,25,60,0.95)', borderColor: '#4dabf7', textStyle: { color: '#fff' } },
          xAxis: { type: 'category', data: xData, axisLine: { lineStyle: { color: 'rgba(77,171,247,0.3)' } }, axisLabel: { color: '#adb5bd', fontSize: 10 } },
          yAxis: { type: 'value', splitLine: { lineStyle: { color: 'rgba(77,171,247,0.1)' } }, axisLine: { lineStyle: { color: 'rgba(77,171,247,0.3)' } }, axisLabel: { color: '#adb5bd', fontSize: 10 } },
          series: []
        }
        if (!hasData) {
          option.graphic = {
            type: 'text',
            left: 'center',
            top: 'middle',
            style: { text: '暂无数据', fill: '#adb5bd', fontSize: 12 }
          }
        } else {
          if (allZero) {
            option.yAxis.min = 0
            option.yAxis.max = 1
          }
          option.series = [{
            data: seriesData,
            type: 'line',
            smooth: true,
            symbol: 'circle',
            symbolSize: 4,
            lineStyle: { color: '#4dabf7', width: 2 },
            itemStyle: { color: '#4dabf7' },
            areaStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: 'rgba(77,171,247,0.3)' }, { offset: 1, color: 'rgba(77,171,247,0.02)' }] } }
          }]
        }
        chart.setOption(option)
        this._detailCharts[safeId] = chart
      })
    },

    parseDistributionValue(metricName, rawValue) {
      if (!rawValue) return null
      if (metricName === 'TOP访问IP' || metricName === 'TOP请求路径') {
        const items = rawValue.split(' | ').map(item => {
          const m = item.match(/^(.+)\((\d+)次\)$/)
          return m ? { name: m[1], value: parseInt(m[2]) } : null
        }).filter(Boolean)
        if (items.length > 0) return items
      } else {
        const items = rawValue.split(' | ').map(item => {
          const m = item.match(/^(.+)\(([\d.]+)%\)$/)
          if (m) {
            return { name: m[1], value: parseFloat(m[2]) }
          }
          return null
        }).filter(Boolean)
        if (items.length > 0) return items
      }
      return null
    },

    renderDistributionCharts() {
      if (!window.echarts) return
      if (!this._distCharts) this._distCharts = {}
      const metrics = this.detailPanel.distributionMetrics || []
      metrics.forEach((dm, idx) => {
        const chartId = 'detailDistChart_' + idx
        const dom = document.getElementById(chartId)
        if (!dom) return
        if (this._distCharts[chartId]) {
          this._distCharts[chartId].dispose()
        }
        const chart = window.echarts.init(dom, null, { renderer: 'canvas' })
        if (dm.name === 'TOP访问IP' || dm.name === 'TOP请求路径') {
          chart.setOption({
            backgroundColor: 'transparent',
            tooltip: {
              trigger: 'axis',
              axisPointer: { type: 'shadow' },
              backgroundColor: 'rgba(10, 30, 60, 0.9)',
              borderColor: '#1a5276',
              textStyle: { color: '#e0e0e0' }
            },
            grid: { left: 10, right: 30, top: 10, bottom: 20, containLabel: true },
            xAxis: {
              type: 'value',
              axisLabel: { color: '#8899aa', fontSize: 10 },
              splitLine: { lineStyle: { color: 'rgba(255,255,255,0.05)' } }
            },
            yAxis: {
              type: 'category',
              data: dm.data.map(d => d.name),
              axisLabel: { color: '#8899aa', fontSize: 10 },
              splitLine: { show: false }
            },
            series: [{
              type: 'bar',
              data: dm.data.map(d => d.value),
              itemStyle: {
                color: new window.echarts.graphic.LinearGradient(0, 0, 1, 0, [
                  { offset: 0, color: '#1a6fa8' },
                  { offset: 1, color: '#29b6f6' }
                ])
              },
              barWidth: '60%',
              label: {
                show: true,
                position: 'right',
                color: '#aabbcc',
                fontSize: 10
              }
            }]
          })
        } else {
          chart.setOption({
            backgroundColor: 'transparent',
            tooltip: {
              trigger: 'item',
              backgroundColor: 'rgba(10, 30, 60, 0.9)',
              borderColor: '#1a5276',
              textStyle: { color: '#e0e0e0' },
              formatter: '{b}: {c}% ({d}%)'
            },
            legend: {
              orient: 'vertical',
              right: 5,
              top: 'center',
              textStyle: { color: '#8899aa', fontSize: 10 },
              itemWidth: 10,
              itemHeight: 10
            },
            series: [{
              type: 'pie',
              radius: ['35%', '65%'],
              center: ['40%', '50%'],
              avoidLabelOverlap: true,
              itemStyle: {
                borderColor: '#0a1628',
                borderWidth: 2
              },
              label: {
                show: true,
                color: '#aabbcc',
                fontSize: 10,
                formatter: '{b}\n{d}%'
              },
              labelLine: {
                lineStyle: { color: 'rgba(255,255,255,0.2)' }
              },
              data: dm.data
            }]
          })
        }
        this._distCharts[chartId] = chart
      })
    },

    drawTrendChart() {
      const canvas = this.$refs.trendCanvas
      if (!canvas) return
      const wrap = canvas.parentElement
      const w = wrap.clientWidth
      const h = 400
      canvas.width = w
      canvas.height = h
      const ctx = canvas.getContext('2d')
      ctx.clearRect(0, 0, w, h)

      const tc = this.trendChart
      const targetData = tc.targetData || []
      const compareData = tc.compareData || []
      if (!targetData.length && !compareData.length) {
        ctx.fillStyle = '#adb5bd'
        ctx.font = '14px "Microsoft YaHei"'
        ctx.textAlign = 'center'
        ctx.fillText('暂无数据', w / 2, h / 2)
        return
      }

      const margin = { top: 30, right: 30, bottom: 50, left: 60 }
      const chartW = w - margin.left - margin.right
      const chartH = h - margin.top - margin.bottom

      // 收集所有数值计算范围
      let allValues = []
      targetData.forEach(d => allValues.push(d.value))
      compareData.forEach(d => allValues.push(d.value))
      if (allValues.length === 0) return
      let minVal = Math.min(...allValues)
      let maxVal = Math.max(...allValues)
      if (minVal === maxVal) { minVal = minVal * 0.9; maxVal = maxVal * 1.1 || 1 }
      const valRange = maxVal - minVal

      // 辅助函数：坐标转换
      // 对比模式：X轴按“一天中的时刻”对齐（同一X=同一时刻），两条曲线才具备可比性；单日模式按索引均匀平铺
      const compareAlign = compareData.length > 0
      let timeMin = 0
      let timeMax = 0
      if (compareAlign) {
        const ms = []
        targetData.concat(compareData).forEach(d => {
          const m = this.timeToMinutes(d.time)
          if (m !== null) ms.push(m)
        })
        timeMin = Math.min(...ms)
        timeMax = Math.max(...ms)
        if (timeMin === timeMax) { timeMin = Math.max(0, timeMin - 30); timeMax = Math.min(1439, timeMax + 30) }
      }
      const toX = (index, total) => margin.left + (index / Math.max(total - 1, 1)) * chartW
      const toY = (val) => margin.top + chartH - ((val - minVal) / valRange) * chartH
      const pointX = (d, i, total) => {
        if (!compareAlign) return toX(i, total)
        const m = this.timeToMinutes(d.time)
        const mm = m === null ? timeMin : m
        return margin.left + ((mm - timeMin) / (timeMax - timeMin)) * chartW
      }

      // 绘制网格
      ctx.strokeStyle = 'rgba(77, 171, 247, 0.15)'
      ctx.lineWidth = 1
      const gridCount = 5
      for (let i = 0; i <= gridCount; i++) {
        const y = margin.top + (chartH / gridCount) * i
        ctx.beginPath()
        ctx.moveTo(margin.left, y)
        ctx.lineTo(margin.left + chartW, y)
        ctx.stroke()
        const val = maxVal - (valRange / gridCount) * i
        ctx.fillStyle = '#adb5bd'
        ctx.font = '11px "Microsoft YaHei"'
        ctx.textAlign = 'right'
        ctx.fillText(val.toFixed(1), margin.left - 8, y + 4)
      }

      // 绘制X轴时间标签
      if (compareAlign) {
        // 对比模式：按时刻域均匀取6个刻度，与数据点时刻对齐
        ctx.fillStyle = '#adb5bd'
        ctx.font = '11px "Microsoft YaHei"'
        ctx.textAlign = 'center'
        const ticks = 6
        for (let i = 0; i < ticks; i++) {
          const m = timeMin + ((timeMax - timeMin) / (ticks - 1)) * i
          const x = margin.left + (i / (ticks - 1)) * chartW
          ctx.fillText(this.minutesToLabel(m), x, h - margin.bottom + 18)
        }
      } else {
        const drawXLabels = (data, color) => {
          if (!data.length) return
          const step = Math.max(1, Math.floor(data.length / 6))
          ctx.fillStyle = color || '#adb5bd'
          ctx.font = '11px "Microsoft YaHei"'
          ctx.textAlign = 'center'
          for (let i = 0; i < data.length; i += step) {
            const x = toX(i, data.length)
            const timeStr = data[i].time ? data[i].time.substring(11, 16) : ''
            ctx.fillText(timeStr, x, h - margin.bottom + 18)
          }
        }
        drawXLabels(targetData, '#4dabf7')
      }

      // 绘制折线
      const drawLine = (data, color, isDashed) => {
        if (!data.length) return
        ctx.strokeStyle = color
        ctx.lineWidth = 2
        if (isDashed) {
          ctx.setLineDash([6, 4])
        } else {
          ctx.setLineDash([])
        }
        ctx.beginPath()
        data.forEach((d, i) => {
          const x = pointX(d, i, data.length)
          const y = toY(d.value)
          if (i === 0) ctx.moveTo(x, y)
          else ctx.lineTo(x, y)
        })
        ctx.stroke()
        ctx.setLineDash([])

        // 数据点
        ctx.fillStyle = color
        data.forEach((d, i) => {
          const x = pointX(d, i, data.length)
          const y = toY(d.value)
          ctx.beginPath()
          ctx.arc(x, y, 3, 0, Math.PI * 2)
          ctx.fill()
        })
      }

      if (compareData.length) {
        drawLine(compareData, '#adb5bd', true)
      } else if (tc.compareMode !== 'none' && tc.compareDate) {
        // 对比模式下对比日无数据时给出明确提示，避免用户误以为对比未生效
        ctx.fillStyle = '#adb5bd'
        ctx.font = '12px "Microsoft YaHei"'
        ctx.textAlign = 'center'
        ctx.fillText('对比日（' + tc.compareDate + '）暂无数据', w / 2, margin.top - 10)
      }
      drawLine(targetData, '#4dabf7', false)

      // 绘制坐标轴
      ctx.strokeStyle = 'rgba(77, 171, 247, 0.3)'
      ctx.lineWidth = 1
      ctx.beginPath()
      ctx.moveTo(margin.left, margin.top)
      ctx.lineTo(margin.left, margin.top + chartH)
      ctx.lineTo(margin.left + chartW, margin.top + chartH)
      ctx.stroke()

      // 绑定鼠标悬浮事件（只绑定一次）
      if (!canvas._hoverBound) {
        canvas._hoverBound = true
        canvas.addEventListener('mousemove', this.onTrendCanvasMouseMove)
        canvas.addEventListener('mouseleave', this.onTrendCanvasMouseLeave)
      }
    },

    onTrendCanvasMouseMove(e) {
      const canvas = this.$refs.trendCanvas
      if (!canvas) return
      const rect = canvas.getBoundingClientRect()
      const mouseX = e.clientX - rect.left
      const mouseY = e.clientY - rect.top

      const tc = this.trendChart
      const targetData = tc.targetData || []
      const compareData = tc.compareData || []
      if (!targetData.length && !compareData.length) return

      const margin = { top: 30, right: 30, bottom: 50, left: 60 }
      const chartW = canvas.width - margin.left - margin.right
      const chartH = canvas.height - margin.top - margin.bottom

      // 只在绘图区域内响应
      if (mouseX < margin.left || mouseX > margin.left + chartW ||
          mouseY < margin.top || mouseY > margin.top + chartH) {
        this.trendHover.show = false
        return
      }

      // 对比模式下X轴按时刻对齐，点位置计算需与绘制逻辑保持一致
      const compareAlign = compareData.length > 0
      let timeMin = 0
      let timeMax = 0
      if (compareAlign) {
        const ms = []
        targetData.concat(compareData).forEach(d => {
          const m = this.timeToMinutes(d.time)
          if (m !== null) ms.push(m)
        })
        timeMin = Math.min(...ms)
        timeMax = Math.max(...ms)
        if (timeMin === timeMax) { timeMin = Math.max(0, timeMin - 30); timeMax = Math.min(1439, timeMax + 30) }
      }
      const pointXOf = (d, i, total) => {
        if (!compareAlign) return margin.left + (i / Math.max(total - 1, 1)) * chartW
        const m = this.timeToMinutes(d.time)
        const mm = m === null ? timeMin : m
        return margin.left + ((mm - timeMin) / (timeMax - timeMin)) * chartW
      }

      // 找到目标日最近的数据点
      let closestTargetIdx = -1
      let closestTargetDist = Infinity
      targetData.forEach((d, i) => {
        const dist = Math.abs(pointXOf(d, i, targetData.length) - mouseX)
        if (dist < closestTargetDist) {
          closestTargetDist = dist
          closestTargetIdx = i
        }
      })

      // 找到对比日最近的数据点
      let closestCompareIdx = -1
      let closestCompareDist = Infinity
      compareData.forEach((d, i) => {
        const dist = Math.abs(pointXOf(d, i, compareData.length) - mouseX)
        if (dist < closestCompareDist) {
          closestCompareDist = dist
          closestCompareIdx = i
        }
      })

      // 选择距离更近的系列作为锚点
      let idx = -1
      let dataSource = null
      if (closestTargetIdx >= 0 && closestCompareIdx >= 0) {
        if (closestTargetDist <= closestCompareDist) {
          idx = closestTargetIdx
          dataSource = targetData
        } else {
          idx = closestCompareIdx
          dataSource = compareData
        }
      } else if (closestTargetIdx >= 0) {
        idx = closestTargetIdx
        dataSource = targetData
      } else if (closestCompareIdx >= 0) {
        idx = closestCompareIdx
        dataSource = compareData
      }

      if (idx < 0 || !dataSource) return

      // 阈值判断：只当鼠标足够靠近锚定系列的数据点才显示（以锚定系列的数据点数量计算间距）
      const pointSpacing = chartW / Math.max(dataSource.length - 1, 1)
      const threshold = Math.max(pointSpacing * 0.6, 20)
      const anchorDist = dataSource === targetData ? closestTargetDist : closestCompareDist
      if (anchorDist > threshold) {
        this.trendHover.show = false
        return
      }

      // 获取时间（取锚定系列的该索引时间点）
      const anchorPoint = dataSource[idx]
      const timeStr = anchorPoint.time ? anchorPoint.time.substring(11, 16) : ''
      const anchorM = this.timeToMinutes(anchorPoint.time)

      // 同一时刻取值：在两条系列中各找时刻最接近的点（±3分钟内）
      // 两日采集时刻很难精确到同一分钟，精确匹配 HH:MM 会导致取值缺失或错位
      const nearestByTime = (data) => {
        if (anchorM === null) return null
        let best = null
        let bestDiff = Infinity
        data.forEach(d => {
          const m = this.timeToMinutes(d.time)
          if (m === null) return
          const diff = Math.abs(m - anchorM)
          if (diff < bestDiff) { bestDiff = diff; best = d }
        })
        return bestDiff <= 3 ? best : null
      }
      const targetPoint = nearestByTime(targetData)
      const comparePoint = nearestByTime(compareData)
      const targetValue = targetPoint ? targetPoint.value : null
      const compareValue = comparePoint ? comparePoint.value : null

      this.trendHover = {
        show: true,
        x: mouseX + 14,
        y: mouseY - 50,
        time: timeStr,
        targetValue: targetValue !== null ? targetValue.toFixed(2) : '',
        compareValue: compareValue !== null ? compareValue.toFixed(2) : ''
      }

      // 边界检测：靠近右侧时提示框显示在左侧，靠近顶部时显示在下方
      const tipWidth = 160
      if (mouseX + 14 + tipWidth > canvas.width) {
        this.trendHover.x = mouseX - tipWidth - 10
      }
      if (mouseY - 50 < 0) {
        this.trendHover.y = mouseY + 16
      }
    },

    onTrendCanvasMouseLeave() {
      this.trendHover.show = false
    },

    // ========== 告警相关方法 ==========

    toggleAlertPanel() {
      this.alertPanel.expanded = !this.alertPanel.expanded
    },

    closeAlertPanel() {
      this.alertPanel.expanded = false
    },

    async loadActiveAlerts() {
      try {
        const res = await service.get('/monitor/alert/active')
        // 告警面板默认收起，仅靠右上角角标提示数量，不自动弹出
        this.activeAlerts = res.data || []
      } catch (e) {
        // 静默失败
      }
    },

    startAlertPolling() {
      this.loadActiveAlerts()
      // 后端采集周期为2分钟，告警也只能随采集产生，轮询同步为2分钟避免空转
      this._alertPollTimer = setInterval(() => {
        this.loadActiveAlerts()
      }, 120000)
    },

    stopAlertPolling() {
      if (this._alertPollTimer) {
        clearInterval(this._alertPollTimer)
        this._alertPollTimer = null
      }
    },

    startMetricsPolling() {
      // 后端采集周期为2分钟，轮询周期与之对齐：30秒轮询会有3/4的请求拿到未变化的数据
      this._metricsPollTimer = setInterval(() => {
        this.loadMetrics()
      }, 120000)
    },

    stopMetricsPolling() {
      if (this._metricsPollTimer) {
        clearInterval(this._metricsPollTimer)
        this._metricsPollTimer = null
      }
    },

    async loadAlertHistory() {
      try {
        const res = await service.get('/monitor/alert/history', { params: { limit: 50 } })
        this.alertHistory = res.data || []
      } catch (e) {
        // 静默失败
      }
    },

    switchAlertTab(tab) {
      this.alertPanel.tab = tab
      if (tab === 'history' && this.alertHistory.length === 0) {
        this.loadAlertHistory()
      }
    },

    updateCurrentTime() {
      const now = new Date()
      const pad = n => String(n).padStart(2, '0')
      this.currentTime = `${now.getFullYear()}-${pad(now.getMonth()+1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`
    },

    startClock() {
      this.updateCurrentTime()
      this._clockTimer = setInterval(() => this.updateCurrentTime(), 1000)
    },

    stopClock() {
      if (this._clockTimer) {
        clearInterval(this._clockTimer)
        this._clockTimer = null
      }
    },

    getNodeAlertClass(nodeId, type) {
      if (!this.activeAlerts || this.activeAlerts.length === 0) return ''
      let maxLevel = ''
      for (const alert of this.activeAlerts) {
        if (alert.targetType === 'node' && alert.targetId === nodeId) {
          if (alert.alertLevel === 'critical') return 'node-alert-critical'
          maxLevel = 'warning'
        }
      }
      return maxLevel === 'warning' ? 'node-alert-warning' : ''
    },

    getLinkAlertClass(linkId) {
      if (!this.activeAlerts || this.activeAlerts.length === 0) return ''
      let maxLevel = ''
      for (const alert of this.activeAlerts) {
        if (alert.targetType === 'link' && alert.targetId === linkId) {
          if (alert.alertLevel === 'critical') return 'link-alert-critical'
          maxLevel = 'warning'
        }
      }
      return maxLevel === 'warning' ? 'link-alert-warning' : ''
    },

    async locateAlertTarget(alert) {
      // 实例维度的告警直接进入实例详情页，并选中触发告警的实例
      if (alert.targetType === 'node' && alert.serverIp) {
        this.$router.push({ name: 'InstancePage', query: { nodeId: alert.targetId, serverIp: alert.serverIp } }).catch(() => {})
        return
      }
      // 找到对应节点/连线并平移视图到该位置；目标被收起时先自动展开祖先
      if (alert.targetType === 'node') {
        await this.ensureVisible(alert.targetId)
        const node = this.layoutNodes.find(n => n.id === alert.targetId)
        if (node) {
          const container = document.getElementById('canvas-container')
          if (container) {
            this.viewPanX = -(node.layoutX - this.worldCenterX) * this.viewScale
            this.viewPanY = -(node.layoutY - this.worldCenterY) * this.viewScale
            this.applyTransform()
          }
        }
      } else if (alert.targetType === 'link') {
        const link = this.topologyLinks.find(l => l.id === alert.targetId)
        if (link) {
          await this.ensureVisible(link.sourceNodeId)
          const source = this.layoutNodes.find(n => n.id === link.sourceNodeId)
          const target = this.layoutNodes.find(n => n.id === link.targetNodeId)
          if (source && target) {
            const cx = (source.layoutX + target.layoutX) / 2
            const cy = (source.layoutY + target.layoutY) / 2
            this.viewPanX = -(cx - this.worldCenterX) * this.viewScale
            this.viewPanY = -(cy - this.worldCenterY) * this.viewScale
            this.applyTransform()
          }
        }
      }
    },

    locateAllNodes() {
      const visibleNodes = this.layoutNodes
      console.log('locateAllNodes called, nodes count:', visibleNodes.length)
      if (visibleNodes.length === 0) return
      let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity
      visibleNodes.forEach(n => {
        const x = n.layoutX
        const y = n.layoutY
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (y < minY) minY = y
        if (y > maxY) maxY = y
      })
      console.log('Node bounds:', { minX, maxX, minY, maxY })
      const container = document.getElementById('canvas-container')
      if (!container) return
      const cw = container.clientWidth
      const ch = container.clientHeight
      console.log('Container size:', { cw, ch })
      const padding = 100
      const worldW = maxX - minX + padding * 2
      const worldH = maxY - minY + padding * 2
      const scale = Math.min(cw / worldW, ch / worldH, 1.2)
      this.viewScale = Math.max(this.minScale, Math.min(this.maxScale, scale))
      const cx = (minX + maxX) / 2
      const cy = (minY + maxY) / 2
      // 世界坐标系中心为 (worldCenterX, worldCenterY)，与 locateAlertTarget 公式一致
      this.viewPanX = -(cx - this.worldCenterX) * this.viewScale
      this.viewPanY = -(cy - this.worldCenterY) * this.viewScale
      console.log('New view:', { scale: this.viewScale, panX: this.viewPanX, panY: this.viewPanY })
      this.applyTransform()
    },

    async onMenuAlertConfig() {
      this.hideContextMenu()
      const target = this.contextMenuTarget
      if (!target) return
      const targetType = this.contextMenu.type === 'node' ? 'node' : 'link'
      const targetId = target.id
      const targetName = target.nodeName || target.linkName || (target.sourceNodeName && target.targetNodeName ? `${target.sourceNodeName}→${target.targetNodeName}` : target.id)
      // 获取该目标的可用指标
      let availableMetrics = []
      if (targetType === 'node') {
        const res = await service.get('/monitor/display/nodeMetrics/' + targetId)
        availableMetrics = (res.data || []).map(m => m.metricName).filter((v, i, a) => a.indexOf(v) === i)
      } else {
        const res = await service.get('/monitor/display/linkMetrics/' + targetId)
        availableMetrics = (res.data || []).map(m => m.metricName).filter((v, i, a) => a.indexOf(v) === i)
      }
      // 获取已有规则
      const rulesRes = await service.get('/monitor/alert/rules/target', { params: { targetType, targetId } })
      this.alertForm = {
        show: true,
        targetType,
        targetId,
        targetName,
        editId: '',
        metricName: '',
        operator: '>',
        thresholdValue: '',
        triggerPeriods: 1,
        recoverPeriods: 1,
        alertLevel: 'warning',
        enabled: 1,
        existingRules: rulesRes.data || [],
        availableMetrics
      }
    },

    closeAlertForm() {
      this.alertForm.show = false
      this.alertForm.editId = ''
    },

    editAlertRule(rule) {
      this.alertForm.editId = rule.id
      this.alertForm.metricName = rule.metricName
      this.alertForm.operator = rule.operator
      this.alertForm.thresholdValue = rule.thresholdValue
      this.alertForm.triggerPeriods = rule.triggerPeriods || 1
      this.alertForm.recoverPeriods = rule.recoverPeriods || 1
      this.alertForm.alertLevel = rule.alertLevel
      this.alertForm.enabled = rule.enabled
    },

    async deleteAlertRule(rule) {
      if (!confirm('确定删除这条告警规则吗？')) return
      try {
        await service.delete('/monitor/alert/rules/' + rule.id)
        // 刷新规则列表
        const rulesRes = await service.get('/monitor/alert/rules/target', { params: { targetType: this.alertForm.targetType, targetId: this.alertForm.targetId } })
        this.alertForm.existingRules = rulesRes.data || []
        this.alertForm.editId = ''
        this.alertForm.metricName = ''
        this.alertForm.thresholdValue = ''
        this.alertForm.triggerPeriods = 1
        this.alertForm.recoverPeriods = 1
      } catch (e) {
        console.error('删除告警规则失败:', e)
      }
    },

    async saveAlertRule() {
      const f = this.alertForm
      if (!f.metricName) { alert('请选择指标'); return }
      if (!f.thresholdValue) { alert('请输入阈值'); return }
      // 连续周期数：1 为单周期即生效；上限 720 个周期（按 2 分钟周期约合 1 天，与后端 AlertStreakState.MAX_PERIODS 一致）
      const triggerPeriods = Math.min(Math.max(Number(f.triggerPeriods) || 1, 1), 720)
      const recoverPeriods = Math.min(Math.max(Number(f.recoverPeriods) || 1, 1), 720)
      const payload = {
        targetType: f.targetType,
        targetId: f.targetId,
        metricName: f.metricName,
        operator: f.operator,
        thresholdValue: f.thresholdValue,
        triggerPeriods,
        recoverPeriods,
        alertLevel: f.alertLevel,
        enabled: f.enabled
      }
      try {
        if (f.editId) {
          await service.put('/monitor/alert/rules/' + f.editId, payload)
        } else {
          await service.post('/monitor/alert/rules', payload)
        }
        // 刷新规则列表
        const rulesRes = await service.get('/monitor/alert/rules/target', { params: { targetType: f.targetType, targetId: f.targetId } })
        this.alertForm.existingRules = rulesRes.data || []
        this.alertForm.editId = ''
        this.alertForm.metricName = ''
        this.alertForm.thresholdValue = ''
        this.alertForm.triggerPeriods = 1
        this.alertForm.recoverPeriods = 1
      } catch (e) {
        console.error('保存告警规则失败:', e)
        alert('保存失败: ' + (e.message || '未知错误'))
      }
    },

    // ========== 通知渠道（企业微信群机器人） ==========

    async openNotifySettings() {
      this.notifyForm.show = true
      this.resetNotifyForm()
      await this.loadNotifyChannels()
    },

    closeNotifySettings() {
      this.notifyForm.show = false
      this.resetNotifyForm()
    },

    resetNotifyForm() {
      this.notifyForm.editId = ''
      this.notifyForm.channelName = ''
      this.notifyForm.webhookUrl = ''
      this.notifyForm.enabled = 1
      this.notifyForm.testingId = ''
    },

    async loadNotifyChannels() {
      try {
        const res = await service.get('/monitor/notify/channels')
        this.notifyForm.channels = res.data || []
      } catch (e) {
        console.error('加载通知渠道失败:', e)
      }
    },

    editNotifyChannel(channel) {
      this.notifyForm.editId = channel.id
      this.notifyForm.channelName = channel.channelName
      // 明文地址不下发前端，编辑时留空表示不修改
      this.notifyForm.webhookUrl = ''
      this.notifyForm.enabled = channel.enabled
    },

    async saveNotifyChannel() {
      const f = this.notifyForm
      if (!f.channelName || !f.channelName.trim()) { alert('请输入渠道名称'); return }
      if (!f.editId && (!f.webhookUrl || !f.webhookUrl.trim())) { alert('请输入 Webhook 地址'); return }
      const payload = {
        channelName: f.channelName.trim(),
        webhookUrl: f.webhookUrl ? f.webhookUrl.trim() : '',
        enabled: f.enabled
      }
      try {
        if (f.editId) {
          await service.put('/monitor/notify/channels/' + f.editId, payload)
        } else {
          await service.post('/monitor/notify/channels', payload)
        }
        this.resetNotifyForm()
        await this.loadNotifyChannels()
      } catch (e) {
        console.error('保存通知渠道失败:', e)
        alert('保存失败: ' + (e.message || '未知错误'))
      }
    },

    async deleteNotifyChannel(channel) {
      if (!confirm('确定删除通知渠道「' + channel.channelName + '」吗？')) return
      try {
        await service.delete('/monitor/notify/channels/' + channel.id)
        if (this.notifyForm.editId === channel.id) this.resetNotifyForm()
        await this.loadNotifyChannels()
      } catch (e) {
        console.error('删除通知渠道失败:', e)
        alert('删除失败: ' + (e.message || '未知错误'))
      }
    },

    async testNotifyChannel(channel) {
      if (this.notifyForm.testingId) return
      this.notifyForm.testingId = channel.id
      try {
        await service.post('/monitor/notify/channels/' + channel.id + '/test')
        alert('测试消息已发送，请查看企业微信群')
      } catch (e) {
        console.error('测试通知渠道失败:', e)
        alert('测试发送失败: ' + (e.message || '未知错误'))
      } finally {
        this.notifyForm.testingId = ''
      }
    }
  }
}
</script>

<style lang="scss" scoped>
.topology-page {
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
}

/* ========== 大屏标题栏 ========== */
.screen-header {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  height: 64px;
  z-index: 800;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 28px;
  box-sizing: border-box;
  pointer-events: none;
  user-select: none;
  background: linear-gradient(180deg, rgba(6, 12, 28, 0.96) 0%, rgba(8, 16, 34, 0.78) 62%, rgba(10, 14, 26, 0) 100%);
}
.screen-header::after {
  content: '';
  position: absolute;
  left: 50%;
  bottom: 0;
  width: 92%;
  height: 1px;
  transform: translateX(-50%);
  background: linear-gradient(90deg, transparent, rgba(77, 171, 247, 0.55), transparent);
}
.screen-title {
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  margin: 0;
  font-size: 26px;
  font-weight: 700;
  letter-spacing: 6px;
  color: #4dabf7;
  white-space: nowrap;
  text-shadow: 0 0 20px rgba(77, 171, 247, 0.5), 0 0 40px rgba(77, 171, 247, 0.2);
}
.screen-title::before,
.screen-title::after {
  content: '';
  position: absolute;
  top: 50%;
  width: 80px;
  height: 1px;
}
.screen-title::before {
  right: 100%;
  margin-right: 20px;
  background: linear-gradient(90deg, transparent, rgba(77, 171, 247, 0.6));
}
.screen-title::after {
  left: 100%;
  margin-left: 20px;
  background: linear-gradient(270deg, transparent, rgba(77, 171, 247, 0.6));
}
.screen-clock {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
  font-family: Consolas, "Courier New", monospace;
}
.screen-clock-date {
  font-size: 12px;
  letter-spacing: 1px;
  color: rgba(77, 171, 247, 0.5);
}
.screen-clock-time {
  font-size: 17px;
  letter-spacing: 1px;
  color: rgba(77, 171, 247, 0.9);
  text-shadow: 0 0 12px rgba(77, 171, 247, 0.35);
}
.screen-refresh-hint {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: rgba(77, 171, 247, 0.6);
}
.screen-refresh-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #51cf66;
  box-shadow: 0 0 8px rgba(81, 207, 102, 0.8);
  animation: refreshPulse 2s ease-in-out infinite;
}
@keyframes refreshPulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.35; }
}

#canvas-container {
  position: relative;
  width: 100%;
  height: 100%;
  background: #0a0e1a;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  cursor: grab;
}


#world {
  position: absolute;
  left: 50%;
  top: 50%;
  /* 尺寸与 data 中的 worldWidth / worldHeight 保持一致（2400 高容纳 12 个叶子节点） */
  width: 3000px;
  height: 2400px;
  transform-origin: center center;
  pointer-events: none;
}
svg {
  position: absolute;
  top: 0; left: 0;
  width: 3000px;
  height: 2400px;
  pointer-events: none;
}
.link-path {
  fill: none;
  stroke: rgba(77, 171, 247, 0.35);
  stroke-width: 2;
}
.link-hit {
  fill: none;
  stroke: transparent;
  stroke-width: 16;
  cursor: pointer;
  pointer-events: stroke;
}
.link-hit:hover + .link-path,
.link-path:hover {
  stroke: rgba(77, 171, 247, 0.7);
  stroke-width: 3;
}
.flow-dot {
  fill: #4dabf7;
  filter: drop-shadow(0 0 4px #4dabf7);
}
.node {
  position: absolute;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-weight: bold;
  cursor: pointer;
  transition: box-shadow 0.2s;
  user-select: none;
  pointer-events: auto;
  overflow: visible;
  width: 160px;
  height: 160px;
  background: rgba(20, 80, 180, 0.15);
  border: 2px solid #4dabf7;
  border-radius: 8px;
  box-shadow: 0 0 25px rgba(77, 171, 247, 0.35), inset 0 0 20px rgba(77, 171, 247, 0.05);
}
.node:hover { z-index: 10; }

/* 节点折叠开关：右上角圆点，仅在节点有下游时显示 */
.node-collapse-btn {
  position: absolute;
  right: -12px;
  top: -12px;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: rgba(10, 30, 70, 0.95);
  border: 1px solid rgba(77, 171, 247, 0.8);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #4dabf7;
  font-size: 18px;
  font-weight: bold;
  line-height: 1;
  cursor: pointer;
  z-index: 3;
  user-select: none;
  transition: background 0.2s, color 0.2s, box-shadow 0.2s;
}
.node-collapse-btn:hover {
  background: rgba(77, 171, 247, 0.35);
  color: #fff;
  box-shadow: 0 0 12px rgba(77, 171, 247, 0.8);
}
.node-collapse-btn.is-collapsed {
  background: rgba(77, 171, 247, 0.25);
  color: #fff;
}

/* 收起后的隐藏节点数量角标（按隐藏子树的告警等级着色，与折叠按钮同排） */
.node-collapse-badge {
  position: absolute;
  right: -52px;
  top: -8px;
  min-width: 26px;
  height: 20px;
  padding: 0 6px;
  box-sizing: border-box;
  border-radius: 10px;
  background: rgba(10, 30, 70, 0.95);
  border: 1px solid rgba(77, 171, 247, 0.6);
  color: #4dabf7;
  font-size: 12px;
  font-weight: normal;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 3;
  pointer-events: none;
}
.node-collapse-badge.badge-warning {
  border-color: rgba(255, 200, 0, 0.9);
  color: #ffc800;
  box-shadow: 0 0 10px rgba(255, 200, 0, 0.6);
}
.node-collapse-badge.badge-critical {
  border-color: rgba(255, 60, 60, 0.9);
  color: #ff3c3c;
  box-shadow: 0 0 10px rgba(255, 60, 60, 0.7);
}
.node.node-collapsed {
  border-style: dashed;
}

.node-name {
  word-break: break-all;
  font-size: 14px;
  text-align: center;
  line-height: 1.2;
  padding: 0 8px;
  box-sizing: border-box;
  margin-top: -15px;
}

/* 节点卡片上的 IP 属性（实例 IP / 日志过滤的匹配 IP）：一个 IP 一行，不拆断 */
.node-ip-list {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1px;
  max-width: 150px;
  margin-top: 4px;
}
.node-ip {
  font-size: 12px;
  font-weight: normal;
  line-height: 1.2;
  color: #74c0fc;
  white-space: nowrap;
  letter-spacing: 0.2px;
}

/* 节点内关键指标展示 */
.node-metrics-inline {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  margin-top: 6px;
  pointer-events: none;
  width: 100%;
  padding: 0 8px;
  box-sizing: border-box;
}
.node-metric-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
  gap: 6px;
}
.node-metric-label {
  color: #8899aa;
  font-size: 11px;
  white-space: nowrap;
  flex-shrink: 0;
}
.node-metric-value {
  color: #4dabf7;
  font-size: 12px;
  font-weight: bold;
  word-break: break-all;
  text-align: right;
  flex-shrink: 1;
  min-width: 0;
}

.node-client {
  width: 160px; height: 160px;
  background: rgba(20, 80, 180, 0.15);
  border: 2px solid #4dabf7;
  border-radius: 8px;
  font-size: 15px;
  box-shadow: 0 0 25px rgba(77, 171, 247, 0.35), inset 0 0 20px rgba(77, 171, 247, 0.05);
}
.node-client:hover {
  box-shadow: 0 0 40px rgba(77, 171, 247, 0.6), inset 0 0 30px rgba(77, 171, 247, 0.1);
}
.node-nginx {
  width: 160px; height: 160px;
  background: rgba(20, 80, 180, 0.15);
  border: 2px solid #4dabf7;
  border-radius: 8px;
  font-size: 15px;
  box-shadow: 0 0 25px rgba(77, 171, 247, 0.35), inset 0 0 20px rgba(77, 171, 247, 0.05);
}
.node-nginx:hover {
  box-shadow: 0 0 40px rgba(77, 171, 247, 0.6), inset 0 0 30px rgba(77, 171, 247, 0.1);
}
.node-ppos {
  width: 160px; height: 160px;
  background: rgba(20, 80, 180, 0.15);
  border: 2px solid #4dabf7;
  border-radius: 8px;
  font-size: 15px;
  box-shadow: 0 0 25px rgba(77, 171, 247, 0.35), inset 0 0 20px rgba(77, 171, 247, 0.05);
}
.node-ppos:hover { box-shadow: 0 0 40px rgba(77, 171, 247, 0.6), inset 0 0 30px rgba(77, 171, 247, 0.1); }
.node-ppos10 { }
.node-ppos30 {
  background: rgba(100, 100, 100, 0.15);
  border-color: #4dabf7;
  box-shadow: 0 0 25px rgba(77, 171, 247, 0.35), inset 0 0 20px rgba(77, 171, 247, 0.05);
}
.node-ppos30:hover { box-shadow: 0 0 40px rgba(77, 171, 247, 0.6), inset 0 0 30px rgba(77, 171, 247, 0.1); }
/* 终态拓扑：后端应用节点（客户端/nginx 之外的服务统一样式） */
.node-app {
  width: 160px; height: 160px;
  background: rgba(20, 80, 180, 0.15);
  border: 2px solid #4dabf7;
  border-radius: 8px;
  font-size: 15px;
  box-shadow: 0 0 25px rgba(77, 171, 247, 0.35), inset 0 0 20px rgba(77, 171, 247, 0.05);
}
.node-app:hover {
  box-shadow: 0 0 40px rgba(77, 171, 247, 0.6), inset 0 0 30px rgba(77, 171, 247, 0.1);
}

.node-circle {
  width: 160px; height: 160px;
  border-radius: 50%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  font-size: 15px;
  line-height: 1.4;
  padding: 10px;
}
.node-circle-blue {
  background: rgba(20, 80, 180, 0.15);
  border: 2px solid #4dabf7;
  box-shadow: 0 0 25px rgba(77, 171, 247, 0.35), inset 0 0 20px rgba(77, 171, 247, 0.05);
}
.node-circle-blue:hover {
  box-shadow: 0 0 40px rgba(77, 171, 247, 0.6), inset 0 0 30px rgba(77, 171, 247, 0.1);
}
.node-circle-green {
  background: rgba(50, 180, 80, 0.15);
  border: 2px solid #51cf66;
  box-shadow: 0 0 25px rgba(81, 207, 102, 0.35), inset 0 0 20px rgba(81, 207, 102, 0.05);
}
.node-circle-green:hover {
  box-shadow: 0 0 40px rgba(81, 207, 102, 0.6), inset 0 0 30px rgba(81, 207, 102, 0.1);
}
.node-daoyue10 { }
.node-daoyue10gz { width: 160px; height: 160px; }
.node-daoyue30wj { width: 160px; height: 160px; }
.node-daoyue30ws { }

#coord-panel {
  position: fixed;
  bottom: 20px;
  left: 20px;
  background: rgba(10, 30, 70, 0.85);
  border: 1px solid rgba(77, 171, 247, 0.4);
  border-radius: 4px;
  padding: 6px 12px;
  color: #4dabf7;
  font-size: 13px;
  font-family: "Courier New", monospace;
  z-index: 1000;
  pointer-events: none;
  user-select: none;
}

/* 层级折叠快捷栏（默认全展开） */
.collapse-toolbar {
  position: fixed;
  top: 92px;
  left: 20px;
  display: flex;
  gap: 8px;
  z-index: 1000;
}
.collapse-toolbar button {
  background: rgba(10, 30, 70, 0.85);
  border: 1px solid rgba(77, 171, 247, 0.4);
  border-radius: 4px;
  color: #4dabf7;
  font-size: 13px;
  font-family: inherit;
  padding: 6px 14px;
  cursor: pointer;
  transition: background 0.2s, color 0.2s, border-color 0.2s;
}
.collapse-toolbar button:hover {
  background: rgba(77, 171, 247, 0.25);
  color: #fff;
}
.collapse-toolbar button.active {
  background: rgba(77, 171, 247, 0.3);
  border-color: rgba(77, 171, 247, 0.9);
  color: #fff;
}
.axis-labels {
  position: fixed;
  pointer-events: none;
  z-index: 5;
}
.x-labels {
  top: 68px;
  left: 0;
  width: 100%;
  height: 20px;
}
.x-labels span {
  position: absolute;
  top: 0;
  color: rgba(77, 171, 247, 0.55);
  font-size: 11px;
  transform: translateX(-50%);
}
.y-labels {
  top: 0;
  left: 2px;
  width: 40px;
  height: 100%;
}
.y-labels span {
  position: absolute;
  left: 0;
  color: rgba(77, 171, 247, 0.55);
  font-size: 11px;
  transform: translateY(-50%);
}
.tooltip {
  position: fixed;
  background: rgba(10, 30, 70, 0.95);
  border: 1px solid #4dabf7;
  border-radius: 8px;
  padding: 16px 20px;
  color: #fff;
  font-size: 14px;
  box-shadow: 0 0 20px rgba(77, 171, 247, 0.4);
  pointer-events: none;
  opacity: 0;
  transition: opacity 0.15s;
  z-index: 10000;
  width: 420px;
  max-width: 90vw;
  overflow: visible;
}
.tooltip.show {
  opacity: 1;
  pointer-events: auto;
}
#tooltip-content {
  max-height: 270px;
  overflow-y: auto;
}
#tooltip-content::-webkit-scrollbar {
  width: 4px;
}
#tooltip-content::-webkit-scrollbar-track {
  background: rgba(77, 171, 247, 0.1);
  border-radius: 2px;
}
#tooltip-content::-webkit-scrollbar-thumb {
  background: rgba(77, 171, 247, 0.4);
  border-radius: 2px;
}


/* 气泡箭头 - tooltip在元素下方，箭头在顶部 */
.tooltip.arrow-top::after {
  content: '';
  position: absolute;
  bottom: 100%;
  left: var(--arrow-left, 50%);
  transform: translateX(-50%);
  width: 0;
  height: 0;
  border-left: 8px solid transparent;
  border-right: 8px solid transparent;
  border-bottom: 8px solid #4dabf7;
}
/* 气泡箭头 - tooltip在元素上方，箭头在底部 */
.tooltip.arrow-bottom::after {
  content: '';
  position: absolute;
  top: 100%;
  left: var(--arrow-left, 50%);
  transform: translateX(-50%);
  width: 0;
  height: 0;
  border-left: 8px solid transparent;
  border-right: 8px solid transparent;
  border-top: 8px solid #4dabf7;
}
.tooltip-title {
  font-size: 15px;
  font-weight: bold;
  color: #4dabf7;
  margin-bottom: 10px;
  border-bottom: 1px solid rgba(77, 171, 247, 0.3);
  padding-bottom: 8px;
}
.tooltip-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  line-height: 1.9;
}
.tooltip-label {
  color: #adb5bd;
  white-space: nowrap;
  flex: 0 0 auto;
  padding-right: 12px;
}
.tooltip-value {
  color: #fff;
  font-weight: bold;
  flex: 0 1 auto;
  text-align: right;
  min-width: 78px;
  max-width: 270px;
  padding-right: 10px;
  word-break: keep-all;
  overflow-wrap: break-word;
}
.tooltip-value.simulated {
  color: #ffd43b;
}
.tooltip-section {
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px solid rgba(77, 171, 247, 0.15);
}

/* 编辑模式开关 */
#edit-mode-toggle {
  position: fixed;
  top: 16px;
  right: 20px;
  z-index: 1000;
  display: flex;
  align-items: center;
  gap: 10px;
  background: rgba(10, 30, 70, 0.85);
  border: 1px solid rgba(77, 171, 247, 0.3);
  border-radius: 6px;
  padding: 8px 14px;
}
.edit-label {
  color: #4dabf7;
  font-size: 13px;
  user-select: none;
}
.edit-switch {
  position: relative;
  display: inline-block;
  width: 40px;
  height: 22px;
}
.edit-switch input {
  opacity: 0;
  width: 0;
  height: 0;
}
.edit-slider {
  position: absolute;
  cursor: pointer;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: rgba(77, 171, 247, 0.2);
  border-radius: 22px;
  transition: .3s;
}
.edit-slider:before {
  position: absolute;
  content: "";
  height: 16px;
  width: 16px;
  left: 3px;
  bottom: 3px;
  background-color: #adb5bd;
  border-radius: 50%;
  transition: .3s;
}
.edit-switch input:checked + .edit-slider {
  background-color: rgba(77, 171, 247, 0.5);
}
.edit-switch input:checked + .edit-slider:before {
  transform: translateX(18px);
  background-color: #4dabf7;
}

/* 编辑模式下节点样式 */
.node-editable {
  box-shadow: 0 0 0 3px rgba(255, 170, 0, 0.6), 0 0 20px rgba(255, 170, 0, 0.3);
  animation: editablePulse 2s ease-in-out infinite;
}
@keyframes editablePulse {
  0%, 100% { box-shadow: 0 0 0 3px rgba(255, 170, 0, 0.6), 0 0 20px rgba(255, 170, 0, 0.3); }
  50% { box-shadow: 0 0 0 5px rgba(255, 170, 0, 0.4), 0 0 30px rgba(255, 170, 0, 0.5); }
}

/* 连线拖拽覆盖层 */
.link-drag-overlay {
  position: absolute;
  top: 0;
  left: 0;
  pointer-events: none;
  z-index: 50;
}

/* 右键菜单 */
.context-menu {
  position: fixed;
  background: rgba(10, 30, 70, 0.95);
  border: 1px solid rgba(77, 171, 247, 0.4);
  border-radius: 6px;
  padding: 4px 0;
  min-width: 130px;
  z-index: 10001;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.5);
}
.context-menu-item {
  padding: 8px 16px;
  color: #fff;
  font-size: 13px;
  cursor: pointer;
  transition: background 0.15s;
}
.context-menu-item:hover {
  background: rgba(77, 171, 247, 0.2);
}
.context-menu-danger {
  color: #ff6b6b;
}
.context-menu-danger:hover {
  background: rgba(255, 107, 107, 0.15);
}

/* 节点表单弹窗 */
.node-form-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(0, 0, 0, 0.6);
  z-index: 10002;
  display: flex;
  align-items: center;
  justify-content: center;
}
.node-form-panel {
  background: rgba(10, 25, 60, 0.98);
  border: 1px solid rgba(77, 171, 247, 0.4);
  border-radius: 8px;
  width: 400px;
  max-width: 90vw;
  padding: 0;
  box-shadow: 0 0 30px rgba(77, 171, 247, 0.3);
}
.node-form-title {
  padding: 16px 20px;
  color: #4dabf7;
  font-size: 16px;
  font-weight: bold;
  border-bottom: 1px solid rgba(77, 171, 247, 0.2);
}
.node-form-body {
  padding: 16px 20px;
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.form-row {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.form-row-half {
  width: calc(50% - 6px);
}
.form-row label {
  color: #adb5bd;
  font-size: 12px;
}
.form-row input,
.form-row select {
  background: rgba(0, 0, 0, 0.4);
  border: 1px solid rgba(77, 171, 247, 0.3);
  border-radius: 4px;
  padding: 8px 10px;
  color: #fff;
  font-size: 13px;
  outline: none;
}
.form-row select option {
  background: #1a2a4a;
  color: #e0e8f0;
}
.form-row input:focus,
.form-row select:focus {
  border-color: #4dabf7;
}
.node-form-footer {
  padding: 12px 20px 16px;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  border-top: 1px solid rgba(77, 171, 247, 0.15);
}
.btn-cancel,
.btn-confirm {
  padding: 7px 18px;
  border-radius: 4px;
  font-size: 13px;
  cursor: pointer;
  border: none;
  outline: none;
}
.btn-cancel {
  background: rgba(77, 171, 247, 0.1);
  color: #adb5bd;
  border: 1px solid rgba(77, 171, 247, 0.2);
}
.btn-cancel:hover {
  background: rgba(77, 171, 247, 0.2);
}
.btn-confirm {
  background: rgba(77, 171, 247, 0.7);
  color: #fff;
}
.btn-confirm:hover {
  background: #4dabf7;
}

/* ========== 趋势图弹窗样式 ========== */
.trend-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(0, 0, 0, 0.7);
  z-index: 20000;
  display: flex;
  align-items: center;
  justify-content: center;
}
.trend-panel {
  background: rgba(10, 25, 60, 0.98);
  border: 1px solid rgba(77, 171, 247, 0.4);
  border-radius: 10px;
  width: 900px;
  max-width: 92vw;
  box-shadow: 0 0 40px rgba(77, 171, 247, 0.25);
  overflow: hidden;
}
.trend-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid rgba(77, 171, 247, 0.2);
}
.trend-title {
  color: #4dabf7;
  font-size: 15px;
  font-weight: bold;
}
.trend-close {
  color: #adb5bd;
  font-size: 24px;
  cursor: pointer;
  line-height: 1;
  padding: 0 4px;
}
.trend-close:hover {
  color: #ff6b6b;
}
.trend-toolbar {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 12px 20px;
  border-bottom: 1px solid rgba(77, 171, 247, 0.1);
  flex-wrap: wrap;
}
.trend-tool-group {
  display: flex;
  align-items: center;
  gap: 6px;
}
.trend-tool-group label {
  color: #adb5bd;
  font-size: 12px;
}
.trend-tool-group input,
.trend-tool-group select {
  background: rgba(0, 0, 0, 0.4);
  border: 1px solid rgba(77, 171, 247, 0.3);
  border-radius: 4px;
  padding: 5px 8px;
  color: #fff;
  font-size: 13px;
  outline: none;
}
.trend-tool-group input:focus,
.trend-tool-group select:focus {
  border-color: #4dabf7;
}
.trend-canvas-wrap {
  position: relative;
  width: 100%;
  height: 400px;
  background: rgba(0, 0, 0, 0.2);
}
.trend-canvas-wrap canvas {
  width: 100%;
  height: 100%;
}
/* 文本型指标双日对照列表 */
.trend-text-list {
  width: 100%;
  height: 100%;
  overflow-y: auto;
  padding: 10px 16px;
  box-sizing: border-box;
  font-size: 12px;
}
.trend-text-list::-webkit-scrollbar {
  width: 6px;
}
.trend-text-list::-webkit-scrollbar-thumb {
  background: rgba(77, 171, 247, 0.3);
  border-radius: 3px;
}
.trend-text-head {
  display: flex;
  position: sticky;
  top: -10px;
  background: #0a1730;
  color: #4dabf7;
  font-weight: bold;
  padding: 8px 0;
  border-bottom: 1px solid rgba(77, 171, 247, 0.3);
}
.trend-text-row {
  display: flex;
  padding: 6px 0;
  border-bottom: 1px solid rgba(77, 171, 247, 0.08);
  color: #dee2e6;
}
.trend-text-row:hover {
  background: rgba(77, 171, 247, 0.08);
}
.trend-text-list .col-time {
  flex: 0 0 70px;
  color: #adb5bd;
  font-family: 'Courier New', monospace;
}
.trend-text-list .col-val {
  flex: 1;
  word-break: break-all;
}
.trend-text-list .col-compare {
  color: #adb5bd;
}
.trend-text-list .col-compare.val-diff {
  color: #ffd43b;
}
.trend-text-none {
  text-align: center;
  color: #adb5bd;
  padding: 40px 0;
}
.trend-loading {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  color: #4dabf7;
  font-size: 14px;
}
.trend-hover-tip {
  position: absolute;
  background: rgba(10, 25, 60, 0.95);
  border: 1px solid rgba(77, 171, 247, 0.4);
  border-radius: 6px;
  padding: 8px 12px;
  pointer-events: none;
  z-index: 10;
  min-width: 130px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.4);
}
.trend-hover-time {
  color: #4dabf7;
  font-size: 12px;
  font-weight: bold;
  margin-bottom: 5px;
  border-bottom: 1px solid rgba(77, 171, 247, 0.15);
  padding-bottom: 4px;
}
.trend-hover-row {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #adb5bd;
  font-size: 11px;
  margin-top: 3px;
}
.trend-hover-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
  flex-shrink: 0;
}
.trend-legend {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 10px 20px;
  border-top: 1px solid rgba(77, 171, 247, 0.1);
}
.legend-item {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #adb5bd;
  font-size: 12px;
}
.legend-line {
  display: inline-block;
  width: 20px;
  height: 2px;
  border-radius: 1px;
}
.trend-stats {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 10px 20px 14px;
  border-top: 1px solid rgba(77, 171, 247, 0.1);
}
.stat-item {
  display: flex;
  align-items: center;
  gap: 8px;
}
.stat-label {
  color: #adb5bd;
  font-size: 12px;
}
.stat-value {
  font-size: 14px;
  font-weight: bold;
}
.stat-value.up {
  color: #51cf66;
}
.stat-value.down {
  color: #ff6b6b;
}

/* tooltip 指标行可点击 */
.tooltip-row-clickable {
  cursor: pointer;
  transition: background 0.15s;
  border-radius: 3px;
  padding: 0 4px;
  margin: 0 -4px;
}
.tooltip-row-clickable:hover {
  background: rgba(77, 171, 247, 0.15);
}

/* ========== 右滑详情面板样式 ========== */
.detail-panel {
  position: fixed;
  top: 0;
  right: 0;
  width: 420px;
  max-width: 90vw;
  height: 100vh;
  background: rgba(10, 20, 50, 0.98);
  border-left: 1px solid rgba(77, 171, 247, 0.3);
  box-shadow: -5px 0 30px rgba(0, 0, 0, 0.5);
  z-index: 15000;
  transform: translateX(100%);
  transition: transform 0.3s ease;
  display: flex;
  flex-direction: column;
}
.detail-panel-open {
  transform: translateX(0);
}
.detail-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  border-bottom: 1px solid rgba(77, 171, 247, 0.2);
  flex-shrink: 0;
}
.detail-title {
  color: #4dabf7;
  font-size: 15px;
  font-weight: bold;
}
.detail-close {
  color: #adb5bd;
  font-size: 24px;
  cursor: pointer;
  line-height: 1;
}
.detail-close:hover {
  color: #ff6b6b;
}
.detail-toolbar {
  padding: 10px 18px;
  border-bottom: 1px solid rgba(77, 171, 247, 0.1);
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.detail-time-switch {
  display: flex;
  gap: 8px;
}
.instance-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px;
  border-radius: 4px;
  background: rgba(77, 171, 247, 0.1);
  border: 1px solid rgba(77, 171, 247, 0.3);
  color: #4dabf7;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
  white-space: nowrap;
}
.instance-btn:hover {
  background: rgba(77, 171, 247, 0.25);
  border-color: #4dabf7;
  color: #fff;
}
.instance-btn svg {
  width: 13px;
  height: 13px;
}
.time-btn {
  padding: 4px 12px;
  border-radius: 4px;
  background: rgba(77, 171, 247, 0.1);
  border: 1px solid rgba(77, 171, 247, 0.2);
  color: #adb5bd;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}
.time-btn.active,
.time-btn:hover {
  background: rgba(77, 171, 247, 0.3);
  border-color: #4dabf7;
  color: #fff;
}
.detail-body {
  flex: 1;
  overflow-y: auto;
  padding: 12px 14px;
}
.detail-loading,
.detail-empty {
  color: #adb5bd;
  text-align: center;
  padding: 40px 0;
  font-size: 13px;
}
.detail-text-metrics {
  margin-bottom: 12px;
}
.detail-text-item {
  background: rgba(77, 171, 247, 0.08);
  border: 1px solid rgba(77, 171, 247, 0.15);
  border-radius: 6px;
  padding: 10px 12px;
  margin-bottom: 8px;
}
.detail-text-name {
  color: #4dabf7;
  font-size: 12px;
  font-weight: bold;
  margin-bottom: 4px;
}
.detail-text-value {
  color: #adb5bd;
  font-size: 12px;
  line-height: 1.5;
  word-break: break-all;
}
.detail-chart-block {
  margin-bottom: 16px;
}
.detail-text-metrics {
  margin-bottom: 12px;
}
.detail-text-item {
  background: rgba(77, 171, 247, 0.08);
  border: 1px solid rgba(77, 171, 247, 0.15);
  border-radius: 6px;
  padding: 10px 12px;
  margin-bottom: 8px;
}
.detail-text-name {
  color: #4dabf7;
  font-size: 12px;
  font-weight: bold;
  margin-bottom: 4px;
}
.detail-text-value {
  color: #adb5bd;
  font-size: 12px;
  line-height: 1.5;
  word-break: break-all;
}
.detail-chart-title {
  color: #adb5bd;
  font-size: 12px;
  margin-bottom: 6px;
  padding-left: 4px;
}
.detail-chart {
  width: 100%;
  height: 180px;
  background: rgba(0, 0, 0, 0.2);
  border-radius: 6px;
}
.detail-distribution-metrics {
  margin-bottom: 16px;
}
.detail-distribution-item {
  margin-bottom: 12px;
}
.detail-distribution-item .detail-chart {
  height: 200px;
  width: 100%;
}

/* ========== 告警闪烁动画 ========== */
@keyframes alertWarningPulse {
  0%, 100% {
    box-shadow: 0 0 10px rgba(255, 200, 0, 0.4), 0 0 20px rgba(255, 200, 0, 0.2);
    border-color: rgba(255, 200, 0, 0.6);
  }
  50% {
    box-shadow: 0 0 25px rgba(255, 200, 0, 0.8), 0 0 50px rgba(255, 200, 0, 0.4);
    border-color: rgba(255, 200, 0, 1);
  }
}
@keyframes alertCriticalPulse {
  0%, 100% {
    box-shadow: 0 0 12px rgba(255, 60, 60, 0.5), 0 0 25px rgba(255, 60, 60, 0.25);
    border-color: rgba(255, 60, 60, 0.7);
  }
  50% {
    box-shadow: 0 0 30px rgba(255, 60, 60, 0.9), 0 0 60px rgba(255, 60, 60, 0.5);
    border-color: rgba(255, 60, 60, 1);
  }
}
.node-alert-warning {
  animation: alertWarningPulse 1.5s ease-in-out infinite !important;
  border-color: rgba(255, 200, 0, 0.8) !important;
  z-index: 11;
}
.node-alert-critical {
  animation: alertCriticalPulse 0.8s ease-in-out infinite !important;
  border-color: rgba(255, 60, 60, 0.9) !important;
  z-index: 12;
}

/* 连线告警闪烁 */
@keyframes linkWarningBlink {
  0%, 100% { stroke: rgba(255, 200, 0, 0.5); }
  50% { stroke: rgba(255, 200, 0, 1); }
}
@keyframes linkCriticalBlink {
  0%, 100% { stroke: rgba(255, 60, 60, 0.5); }
  50% { stroke: rgba(255, 60, 60, 1); }
}
.link-alert-warning {
  animation: linkWarningBlink 1.5s ease-in-out infinite;
  stroke-width: 3;
}
.link-alert-critical {
  animation: linkCriticalBlink 0.8s ease-in-out infinite;
  stroke-width: 3;
}

/* ========== 告警悬浮面板 ========== */
.alert-float-panel {
  position: fixed;
  right: 16px;
  top: 50%;
  transform: translateY(-50%);
  z-index: 9000;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}
/* 收起状态的切换按钮 */
.locate-btn {
  width: 44px;
  height: 44px;
  border-radius: 22px;
  background: rgba(10, 25, 55, 0.85);
  border: 1px solid rgba(77, 171, 247, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  margin-bottom: 10px;
  transition: all 0.2s;
  color: #4dabf7;
  font-size: 20px;
}
.locate-btn:hover {
  background: rgba(20, 50, 100, 0.9);
  border-color: #4dabf7;
  box-shadow: 0 0 15px rgba(77, 171, 247, 0.4);
}
.alert-float-toggle {
  width: 44px;
  height: 44px;
  border-radius: 22px;
  background: rgba(10, 25, 55, 0.85);
  border: 1px solid rgba(77, 171, 247, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.3s ease;
  backdrop-filter: blur(10px);
  box-shadow: 0 0 15px rgba(77, 171, 247, 0.15);
  position: relative;
}
.alert-float-toggle:hover {
  border-color: rgba(77, 171, 247, 0.7);
  box-shadow: 0 0 25px rgba(77, 171, 247, 0.3);
  transform: scale(1.08);
}
.alert-float-icon {
  font-size: 20px;
  line-height: 1;
}
.alert-float-count {
  position: absolute;
  top: -6px;
  right: -6px;
  background: #ff3c3c;
  color: #fff;
  font-size: 11px;
  font-weight: bold;
  min-width: 18px;
  height: 18px;
  line-height: 18px;
  text-align: center;
  border-radius: 9px;
  padding: 0 4px;
  animation: alertBadgePulse 1s ease-in-out infinite;
  box-shadow: 0 0 8px rgba(255, 60, 60, 0.6);
}
@keyframes alertBadgePulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.7; transform: scale(1.15); }
}
/* 展开后的面板主体 */
.alert-float-body {
  width: 420px;
  max-height: 520px;
  background: rgba(8, 20, 48, 0.92);
  border: 1px solid rgba(77, 171, 247, 0.25);
  border-radius: 12px;
  backdrop-filter: blur(16px);
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.5), 0 0 20px rgba(77, 171, 247, 0.1);
  margin-top: 10px;
  overflow: hidden;
  animation: alertPanelSlideIn 0.3s ease;
}
@keyframes alertPanelSlideIn {
  from { opacity: 0; transform: translateX(20px); }
  to { opacity: 1; transform: translateX(0); }
}
.alert-float-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid rgba(77, 171, 247, 0.15);
  color: #4dabf7;
  font-size: 14px;
  font-weight: bold;
  letter-spacing: 1px;
}
.alert-float-close {
  cursor: pointer;
  font-size: 14px;
  color: #868e96;
  transition: color 0.2s;
  line-height: 1;
}
.alert-float-close:hover {
  color: #ff6b6b;
}
.alert-float-list {
  max-height: 450px;
  overflow-y: auto;
  padding: 8px 0;
}
.alert-float-list::-webkit-scrollbar {
  width: 3px;
}
.alert-float-list::-webkit-scrollbar-thumb {
  background: rgba(77, 171, 247, 0.3);
  border-radius: 2px;
}
.alert-float-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 28px 0;
  color: #51cf66;
  font-size: 13px;
}
.alert-float-ok-icon {
  font-size: 28px;
  opacity: 0.8;
}
.alert-float-item {
  padding: 10px 16px;
  cursor: pointer;
  transition: all 0.2s;
  border-left: 3px solid transparent;
}
.alert-float-item:hover {
  background: rgba(77, 171, 247, 0.08);
}
.alert-float-warning {
  border-left-color: #ffc800;
}
.alert-float-critical {
  border-left-color: #ff3c3c;
  background: rgba(255, 60, 60, 0.04);
}
.alert-float-critical:hover {
  background: rgba(255, 60, 60, 0.1);
}
.alert-float-item-left {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.alert-float-level-tag {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 3px;
  font-weight: bold;
  letter-spacing: 0.5px;
}
.tag-warning {
  background: rgba(255, 200, 0, 0.15);
  color: #ffc800;
  border: 1px solid rgba(255, 200, 0, 0.3);
}
.tag-critical {
  background: rgba(255, 60, 60, 0.15);
  color: #ff3c3c;
  border: 1px solid rgba(255, 60, 60, 0.3);
}
.alert-float-target {
  color: #e9ecef;
  font-size: 13px;
  font-weight: bold;
}
/* 告警维度标签：链路 / 具体实例 / 节点汇总 */
.alert-float-scope {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 3px;
  white-space: nowrap;
  border: 1px solid transparent;
}
.scope-instance {
  color: #4dabf7;
  background: rgba(77, 171, 247, 0.12);
  border-color: rgba(77, 171, 247, 0.35);
  font-family: 'Consolas', monospace;
}
.scope-aggregate {
  color: #adb5bd;
  background: rgba(173, 181, 189, 0.08);
  border-color: rgba(173, 181, 189, 0.2);
}
.scope-link {
  color: #51cf66;
  background: rgba(81, 207, 102, 0.12);
  border-color: rgba(81, 207, 102, 0.3);
}
.alert-float-item-detail {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 3px;
}
.alert-float-metric {
  color: #adb5bd;
  font-size: 11px;
}
.alert-float-value {
  color: #ff6b6b;
  font-size: 12px;
  font-weight: bold;
  font-family: 'Courier New', monospace;
}
.alert-float-item-time {
  color: #495057;
  font-size: 10px;
}
/* Tab 切换 */
.alert-float-tabs {
  display: flex;
  border-bottom: 1px solid rgba(77, 171, 247, 0.15);
  padding: 0 16px;
}
.alert-tab {
  padding: 8px 14px;
  font-size: 12px;
  color: #868e96;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: all 0.2s;
  user-select: none;
}
.alert-tab:hover {
  color: #adb5bd;
}
.tab-active {
  color: #4dabf7;
  border-bottom-color: #4dabf7;
  font-weight: bold;
}
/* 告警项主体（可点击区域） */
.alert-float-item-main {
  cursor: pointer;
}
/* 历史记录项 */
.alert-float-history {
  opacity: 0.75;
  border-left-color: #495057 !important;
}
.alert-float-history:hover {
  opacity: 1;
}
/* 状态标签 */
.alert-float-status-tag {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 3px;
  font-weight: bold;
}
.status-active {
  background: rgba(255, 60, 60, 0.15);
  color: #ff6b6b;
  border: 1px solid rgba(255, 60, 60, 0.3);
}
.status-resolved {
  background: rgba(81, 207, 102, 0.15);
  color: #51cf66;
  border: 1px solid rgba(81, 207, 102, 0.3);
}
/* 恢复时间 */
.alert-resolve-time {
  color: #51cf66;
  margin-left: 4px;
}
/* 空状态时间 */
.alert-float-empty-time {
  color: #495057;
  font-size: 11px;
  font-family: 'Courier New', monospace;
  margin-top: 4px;
}

/* ========== 告警规则配置弹窗 ========== */
.alert-config-panel {
  width: 480px;
  max-width: 90vw;
}
.alert-config-icon {
  font-size: 16px;
  margin-right: 6px;
}
.alert-config-section {
  margin-bottom: 12px;
}
.alert-config-section-title {
  font-size: 12px;
  color: #4dabf7;
  font-weight: bold;
  padding: 6px 0;
  border-bottom: 1px solid rgba(77, 171, 247, 0.12);
  margin-bottom: 8px;
  letter-spacing: 0.5px;
}
.alert-rules-list {
  width: 100%;
  max-height: 140px;
  overflow-y: auto;
  border: 1px solid rgba(77, 171, 247, 0.12);
  border-radius: 6px;
  background: rgba(0, 0, 0, 0.2);
}
.alert-rule-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 12px;
  font-size: 12px;
  color: #dee2e6;
  border-bottom: 1px solid rgba(77, 171, 247, 0.06);
  transition: background 0.15s;
}
.alert-rule-item:last-child {
  border-bottom: none;
}
.alert-rule-item.rule-editing {
  background: rgba(77, 171, 247, 0.1);
  border-left: 3px solid #4dabf7;
}
.rule-metric {
  flex: 1;
  font-family: 'Courier New', monospace;
  font-size: 11px;
  color: #adb5bd;
}
.alert-rule-level {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 3px;
  font-weight: bold;
  white-space: nowrap;
}
.rule-period {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 3px;
  white-space: nowrap;
  color: #4dabf7;
  background: rgba(77, 171, 247, 0.15);
}
.level-warning {
  background: rgba(255, 200, 0, 0.2);
  color: #ffc800;
}
.level-critical {
  background: rgba(255, 60, 60, 0.2);
  color: #ff3c3c;
}
.alert-rule-status.enabled {
  color: #51cf66;
  font-size: 11px;
}
.alert-rule-status.disabled {
  color: #868e96;
  font-size: 11px;
}
.alert-rule-action {
  color: #4dabf7;
  cursor: pointer;
  font-size: 11px;
  white-space: nowrap;
}
.alert-rule-action:hover {
  text-decoration: underline;
}
.alert-rule-action.danger {
  color: #ff6b6b;
}
.alert-rules-empty {
  color: #868e96;
  font-size: 12px;
  text-align: center;
  padding: 14px 0;
  width: 100%;
  background: rgba(0, 0, 0, 0.15);
  border-radius: 6px;
  border: 1px dashed rgba(77, 171, 247, 0.12);
}
.alert-rule-form {
  width: 100%;
}
.alert-config-tip {
  margin-top: 8px;
  font-size: 11px;
  line-height: 1.6;
  color: #868e96;
}
.form-row-inline {
  display: flex;
  gap: 10px;
}

/* ========== 通知渠道设置弹窗 ========== */
.alert-float-title-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.alert-float-notify-btn {
  cursor: pointer;
  font-size: 14px;
  color: #868e96;
  transition: color 0.2s;
  line-height: 1;
}
.alert-float-notify-btn:hover {
  color: #4dabf7;
}
.notify-channel-name {
  flex: 0 0 auto;
  max-width: 110px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #dee2e6;
}
.notify-channel-url {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: 'Courier New', monospace;
  font-size: 10px;
  color: #868e96;
}

/* ========== 拓扑编辑（自由布局拖动 / 实例绑定） ========== */
.node-draggable {
  cursor: move;
}
.node-dragging {
  z-index: 20 !important;
}
.context-menu-disabled {
  color: #6c757d;
  cursor: default;
}
.context-menu-disabled:hover {
  background: transparent;
}

.node-edit-panel {
  width: 620px;
}
.node-edit-panel .form-row-inline {
  width: 100%;
}
.binding-section {
  width: 100%;
  border-top: 1px solid rgba(77, 171, 247, 0.15);
  padding-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.binding-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  color: #4dabf7;
  font-size: 13px;
  font-weight: bold;
}
.binding-add {
  color: #4dabf7;
  font-size: 12px;
  cursor: pointer;
  padding: 2px 8px;
  border: 1px solid rgba(77, 171, 247, 0.4);
  border-radius: 4px;
}
.binding-add:hover {
  background: rgba(77, 171, 247, 0.2);
}
.binding-empty {
  color: #868e96;
  font-size: 12px;
  padding: 6px 0;
}
.binding-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.binding-row-inline {
  display: flex;
  align-items: center;
  gap: 8px;
}
.binding-row-inline label {
  color: #adb5bd;
  font-size: 12px;
  white-space: nowrap;
}
.binding-row-inline select,
.binding-row-inline input {
  background: rgba(0, 0, 0, 0.4);
  border: 1px solid rgba(77, 171, 247, 0.3);
  border-radius: 4px;
  padding: 6px 8px;
  color: #fff;
  font-size: 12px;
  outline: none;
}
.binding-row-inline select option {
  background: #1a2a4a;
  color: #e0e8f0;
}
.binding-row-inline input {
  width: 150px;
}
.binding-row input,
.binding-row select {
  background: rgba(0, 0, 0, 0.4);
  border: 1px solid rgba(77, 171, 247, 0.3);
  border-radius: 4px;
  padding: 6px 8px;
  color: #fff;
  font-size: 12px;
  outline: none;
}
.binding-row input:focus,
.binding-row select:focus {
  border-color: #4dabf7;
}
.binding-row select option {
  background: #1a2a4a;
  color: #e0e8f0;
}
.binding-ip {
  width: 130px;
}
.binding-type {
  width: 96px;
}
.binding-prefix {
  width: 110px;
}
.binding-prefix:disabled {
  opacity: 0.45;
}
.binding-enabled {
  display: flex;
  align-items: center;
  gap: 3px;
  color: #adb5bd;
  font-size: 12px;
  white-space: nowrap;
}
.binding-enabled input {
  width: 13px;
  height: 13px;
}
.binding-remove {
  color: #ff6b6b;
  font-size: 12px;
  cursor: pointer;
  white-space: nowrap;
}
.binding-tip {
  font-size: 11px;
  line-height: 1.6;
  color: #868e96;
}
</style>
