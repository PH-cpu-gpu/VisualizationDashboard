# 监控大屏（bigScreen）项目说明文档

> 面向运维场景的监控大屏系统：以拓扑图为核心，实时展示「客户端 → Nginx → 应用服务 → 分站」四层链路状态，
> 数据来源于 Nginx / Tomcat 访问日志的定时聚合，并提供趋势分析、阈值告警与企业微信通知。

---

## 目录

1. [项目概述](#1-项目概述)
2. [工程结构](#2-工程结构)
3. [技术栈](#3-技术栈)
4. [系统架构与数据流](#4-系统架构与数据流)
5. [拓扑与节点模型](#5-拓扑与节点模型)
6. [指标采集](#6-指标采集)
7. [数据库设计](#7-数据库设计)
8. [HTTP 接口清单](#8-http-接口清单)
9. [告警与通知](#9-告警与通知)
10. [前端页面与交互](#10-前端页面与交互)
11. [本地开发与启动](#11-本地开发与启动)
12. [打包与部署](#12-打包与部署)
13. [运维排查指南](#13-运维排查指南)
14. [已知注意事项](#14-已知注意事项)

---

## 1. 项目概述

| 项目 | 说明 |
| --- | --- |
| 项目名称 | bigScreen（监控大屏） |
| Maven 坐标 | `com.gjzg:bigscreen:1.0.0-SNAPSHOT` |
| 服务标识 | `bigScreen-server`（group=`g`，version=`v1`） |
| 后端端口 | `9090` |
| 前端应用名 | `bigScreen`（版本号见 `bigScreen-web/package.json`） |
| 部署平台 | 道云（daocloud-platform）微服务集群部署 |

核心能力：

- **拓扑可视化**：节点/链路从数据库加载，前端按链路 BFS 推导层级，支持自由拖拽布局、层级折叠、粒子流线动效。
- **指标采集**：每 2 分钟从远程日志库聚合 Nginx / Tomcat 访问日志，计算 QPS、错误率、P95/P99 耗时等指标。
- **实例维度**：同一节点可绑定多个实例 IP，指标区分「节点汇总」与「单实例」两种口径。
- **趋势分析**：最新值 + 历史曲线（近 1 小时 / 近 24 小时 / 指定日期），支持与昨天、上周对比。
- **告警能力**：阈值规则 + 连续周期抑制 + 节点/实例/链路三级维度 + 活跃告警与历史记录。
- **通知能力**：企业微信群机器人 Webhook，异步批量推送，触发与恢复各推一次。

---

## 2. 工程结构

```
gjzqdapingkeshihua/
├── bigScreen-api/          # 公共 API 模块：实体、DTO、VO、MonitorService 接口
├── bigScreen-server/       # 后端服务：Spring Boot 应用（端口 9090）
├── bigScreen-web/          # 前端工程：hui 脚手架 + Vue 2 单页应用
├── bigScreen-zip/          # 部署包工程：deploy.xml、安装/启停脚本、配置模板
├── _sql/
│   └── schema_full.sql     # 全量建表脚本 + 指标模板种子数据
├── logs/                   # 本地调试日志与验证截图
├── run-backend.bat         # 本地启动后端（先安装 api 模块，再 spring-boot:run）
├── run-frontend.bat        # 本地启动前端（设置 Node 环境后 hui dev）
├── run_sql_scripts.bat     # sqlplus 批量执行 SQL（脚本清单需按实际调整）
├── bump-version-*.ps1      # 前后端版本号自增脚本
└── README.md               # 本文档
```

### 2.1 后端分层

| 包路径 | 职责 |
| --- | --- |
| `com.gjzg.ApplicationServerStarter` | Spring Boot 启动入口，扫描 `com.gjzg` 全包 |
| `com.gjzg.controller` | HTTP 接口层：监控查询、拓扑编辑、通知渠道、健康检查 |
| `com.gjzg.service` | 业务层：拓扑管理、绑定关系提供、告警状态、告警通知 |
| `com.gjzg.service.impl.MonitorServiceImpl` | 指标查询/写入/历史趋势/告警评估的核心实现 |
| `com.gjzg.collector` | 定时采集器：Nginx 日志、Tomcat 日志 |
| `com.gjzg.config` | 多数据源配置（业务库 + Nginx 日志库 + Tomcat 日志库） |
| `com.gjzg.mapper` / `resources/mapper` | MyBatis Mapper 接口与 XML |
| `com.gjzg.entity` | 数据库实体 |
| `com.gjzg.handler.GlobalExceptionHandler` | 全局异常处理 |

> 注意：`bigScreen-api` 中的 `MonitorService` 是普通 Spring 本地服务接口（非 RPC 服务）。

### 2.2 前端结构

```
bigScreen-web/
├── public/
│   ├── index.html
│   └── sysconfig.js          # 运行时配置：LOCAL_CONFIG.API_HOME
├── src/
│   ├── .hui/                 # 脚手架生成目录（hui.js / router.js / store.js 等），禁止手改
│   ├── views/
│   │   ├── TopologyPage.vue  # 拓扑大屏主页面（核心页面，约 5000 行）
│   │   └── InstancePage.vue  # 实例详情页
│   ├── layouts/default.vue
│   ├── router/index.js       # 转发到 .hui/router
│   ├── utils/request.js      # axios 封装 + 响应码归一化
│   ├── api/                  # 接口封装（dbQuery.js 为历史遗留，未使用）
│   ├── index.js              # hCore 初始化与启动
│   └── main.js
├── hui.config.js             # 脚手架配置：代理、插件、打包参数
└── package.json
```

---

## 3. 技术栈

### 3.1 后端

| 类别 | 组件 / 版本 |
| --- | --- |
| JDK | 1.8 |
| 框架 | Spring Boot 2.7.18 + JRES Cloud Starter 3.1.2.11 |
| 数据访问 | MyBatis（jrescloud-starter-mybatis 3.0.8.5）、HikariCP |
| 数据库 | Oracle 11g（`ojdbc6` 11.2.0.4） |
| JSON | fastjson |
| 日志 | log4j2（`log4j2.xml`） |
| 公共依赖 | `com.gjdf.daocloud:daocloud-common-base`（提供 `JsonResult`） |

### 3.2 前端

| 类别 | 组件 / 版本 |
| --- | --- |
| 框架 | Vue 2.6 + vue-router 3.5 + vuex 3.6 |
| UI 库 | h_ui 1.71、hcreator、hui-core 1.47.5 |
| 构建 | `hui` 自研脚手架（`@hui/cli`），底层 `@hui/bundler`（webpack 4） |
| 样式 | sass（通过 `overrides` 将 node-sass 替换为 `sass@^1.77.0`） |
| 请求 | axios 0.21 |
| Node.js | **必须 16.20.2**（v12 不满足 sass 要求，v17+ 会因 OpenSSL 3 导致 webpack 4 编译失败） |

> 前端采用微前端形态（`hui-plugin-micro-app`），`src/.hui/hui.js` 导出 `bootstrap/mount/unmount` 供 single-spa 调用。

---

## 4. 系统架构与数据流

```
                        ┌──────────────────────────────┐
   远程日志库            │  采集层（每 2 分钟）           │
 ┌──────────────────┐   │  NginxLogMetricCollector     │
 │ T_MT_NGINX_ACCESS│──▶│  TomcatLogMetricCollector    │
 │ T_MT_TOMCAT_ACC  │   └──────────────┬───────────────┘
 └──────────────────┘                  │ 写入
                                       ▼
                    ┌──────────────────────────────────────┐
                    │ 业务库（bigScreen）                   │
                    │ t_mt_metric_value_latest / _history  │
                    │ t_mt_link_value_latest  / _history   │
                    └──────────────┬───────────────────────┘
                                   │ 查询
                     ┌─────────────▼─────────────┐
                     │ MonitorController (/api)  │◀── 告警评估 ──▶ AlertNotifyService
                     └─────────────┬─────────────┘                （企业微信 Webhook）
                                   │ JSON
                     ┌─────────────▼─────────────┐
                     │ bigScreen-web（大屏）      │
                     │ 拓扑图 / 实例页 / 告警浮窗 │
                     └───────────────────────────┘
```

关键设计：

1. **绑定关系全部来自数据库**：采集器每轮通过 `TopologyBindingProvider.load()` 读取 `t_mt_node_info` 与 `t_mt_link_info` 构建只读快照，节点/链路在界面上的变更最多 1 个采集周期（2 分钟）后生效。
2. **采集与展示解耦**：采集写库，查询接口只读库；前端每 2 分钟轮询一次（与采集周期对齐），页面时钟每秒刷新。
3. **告警评估同步、通知异步**：指标入库后立即评估告警（失败仅记日志，不影响采集）；通知通过内存队列 + 独立线程推送。
4. **实例即属性**：实例 IP 不再是独立实体，而是节点属性（`INSTANCE_IPS` 逗号分隔），同一 IP 可归属多个节点（同机多应用）。

---

## 5. 拓扑与节点模型

### 5.1 四层结构

| 层级 | 节点类型（`style_class`） | 说明 |
| --- | --- | --- |
| 第 1 层 | `client` | 客户端（圆形节点），全量日志口径，不要求配置实例 IP |
| 第 2 层 | `nginx` | Nginx 节点（道合 3.0 / 道合 1.0 / 道悦 3.0 等），承载集群指标 |
| 第 3 层 | `app` | 应用服务节点（SSE 运维控制台、HSIAR_OB、操作员中心、微信小程序等） |
| 第 4 层 | `app` + `LOG_FILTER` | 1.0 分站，用 `REMOTE_ADDR` + 请求前缀过滤 Tomcat 日志 |
| （不展示） | `tomcat` | 兜底节点，承载未映射到任何节点的实例 IP 数据，不参与拓扑渲染 |

前端层级不是存储字段，而是根据 `t_mt_link_info` 的链路关系做 BFS 推导。

### 5.2 节点绑定属性（`t_mt_node_info`）

| 字段 | 含义 |
| --- | --- |
| `bind_type` | `IP`（实例 IP）或 `LOG_FILTER`（日志过滤） |
| `instance_ips` | IP 列表，逗号分隔；同一 IP 可属于多个节点 |
| `request_prefix` | 请求前缀，仅 `LOG_FILTER` 使用，如 `/pposgjdf` |
| `style_class` | 节点样式：`client` / `nginx` / `app`，决定指标模板与前端渲染 |
| `pos_x` / `pos_y` | 画布坐标，拖动落库 |
| `node_type` | 渲染形状（如 `circle`） |

`LOG_FILTER` 节点的 IP 是 Nginx 转发地址，前端不展示 IP 列表（避免误读）。

### 5.3 指标模板自动套用

- **新增节点**：`TopologyAdminService.createNode()` 按 `style_class` 从 `t_mt_node_metric_tpl` 复制指标定义到 `t_mt_node_metric_def`。
- **新增链路**：按两端节点样式从 `t_mt_link_metric_tpl` 复制到 `t_mt_link_metric_def`。
- 模板未初始化时不会插入任何行，节点仍可正常展示（只是没有指标）。

### 5.4 预置指标数量

| 模板 | 数量 | 主要指标 |
| --- | --- | --- |
| `client` | 6 | 总IP数、IP活跃数、TOP访问IP、并发连接数、终端分布、UA类型 |
| `nginx` | 14 | QPS、请求错误率、平均响应时间、P95/P99、慢请求占比、5xx/4xx、上游耗时/占比/错误率、Nginx 进程存活等 |
| `app` | 7 | QPS、请求失败率、5xx/4xx 错误率、请求错误率、接口请求量TOP、进程存活 |
| 链路 `client → nginx` | 5 | TOP请求路径、请求总数、请求失败次数、请求平均耗时、请求成功率 |

> **强约束**：`metric_name` 必须与采集器中的字符串常量**完全一致**（含空格、括号、大小写），否则采集值无法匹配写入。

---

## 6. 指标采集

### 6.1 定时任务

| 采集器 | 数据源 | 频率 |
| --- | --- | --- |
| `NginxLogMetricCollector` | `standdbppos` 用户的 `T_MT_NGINX_ACCESS_LOG` | `@Scheduled(fixedRate = 120000)`，2 分钟 |
| `TomcatLogMetricCollector` | `standdbppos` 用户的 `T_MT_TOMCAT_ACCESS_LOG` | 同上，2 分钟 |

每轮采集流程（两者一致）：

1. `bindingProvider.load()` 加载绑定快照，刷新「指标名 → METRIC_ID / TIME_WINDOW」缓存；
2. 查询远程日志表最新入库时间，无数据则本轮直接返回；
3. 按节点类型分别聚合：客户端、Nginx 集群（汇总 + 实例）、应用服务（汇总 + 实例）、1.0 分站（LOG_FILTER）；
4. 组装 `MetricValueDTO` 列表，调用 `MonitorServiceImpl.collectNodeMetrics/collectLinkMetrics` 写库；
5. 写库后同步执行告警评估。

### 6.2 统计窗口

- 每个指标定义的 `TIME_WINDOW` 即统计窗口（单位：分钟），由采集器读取，不写死在代码里。
- 典型取值：QPS / 错误率 / 响应时间 3 分钟；客户端「总IP数」「TOP访问IP」「终端分布」1440 分钟；「并发连接数」1 分钟。
- 历史值写入历史表（趋势图），最新值通过 `MERGE` 写入最新表（大屏展示）。

### 6.3 写入策略

- **历史表**：批量 `INSERT ... UNION ALL`，每行生成 `SYS_GUID()` 主键，同批次共享一个 `COLLECT_ID`。
- **最新表**：Oracle `MERGE` 单语句完成「有则更新、无则插入」；同一 `(NODE_ID, METRIC_ID, SERVER_IP)` 在一批中出现多行时，用 `ROW_NUMBER() OVER (PARTITION BY ... ORDER BY SOURCE_TIME DESC)` 取最新一行，避免值与时间错配。

### 6.4 采集口径要点

- **客户端节点**：按全部 Nginx 实例 IP 过滤，排除不属于本拓扑的日志；不按节点 IP 过滤。
- **Nginx 节点**：汇总维度 + 每实例维度；未绑定实例 IP 的节点跳过采集（空 IP 列表会退化为全量统计，必须避免）。
- **应用节点**：按 `SERVER_IP` 分组计算一次后分发给归属节点；同机多应用共享同一 IP 时，指标相同（机器维度，无法区分应用），节点描述含「同机」时前端会提示。
- **1.0 分站**：`REMOTE_ADDR IN (...)` 且 `REQUEST LIKE '前缀%'` 双重过滤。
- **Tomcat `STATUS` 字段**是字符串，先用 `REGEXP_LIKE(STATUS,'^[0-9]+$')` 过滤再转数字，避免 `ORA-01722`。
- **`METRIC_VALUE` 长度**为 `VARCHAR2(500)`，采集器对 TOP 类文本指标做 480 字符截断，避免 `ORA-12899`。

---

## 7. 数据库设计

业务库用户：`bigScreen`（库 `XE` / 生产 `pposjg`），表名前缀统一 `t_mt_`。

| # | 表名 | 用途 | 关键约束 |
| --- | --- | --- | --- |
| 1 | `t_mt_node_info` | 拓扑节点：名称、坐标、样式、绑定属性 | 主键 `id`；`node_name` 唯一 |
| 2 | `t_mt_link_info` | 拓扑链路（有向边） | `(source_node_id, target_node_id)` 唯一；两端外键 |
| 3 | `t_mt_node_metric_def` | 节点指标定义 | 外键 `node_id` |
| 4 | `t_mt_metric_value_latest` | 节点指标最新值（大屏） | `(node_id, metric_id, server_ip)` 唯一 |
| 5 | `t_mt_metric_value_history` | 节点指标历史（趋势） | 多索引：node+metric+time |
| 6 | `t_mt_link_metric_def` | 链路指标定义 | 外键 `link_id` |
| 7 | `t_mt_link_value_latest` | 链路指标最新值 | `(link_id, metric_id)` 唯一 |
| 8 | `t_mt_link_value_history` | 链路指标历史 | 多索引：link+metric+time |
| 9 | `t_mt_node_metric_tpl` | 节点指标模板 | `(style_class, metric_name)` 唯一 |
| 10 | `t_mt_link_metric_tpl` | 链路指标模板 | `(source_style, target_style, metric_name)` 唯一 |
| 11 | `t_mt_alert_rule` | 告警规则 | `target_type` = `node` / `link`；逻辑关联，不建外键 |
| 12 | `t_mt_alert_record` | 告警记录 | `rule_id` 外键 `ON DELETE SET NULL`；`status` = `active` / `resolved` |
| 13 | `t_mt_notify_channel` | 通知渠道（企业微信） | `channel_type` = `WECOM_WEBHOOK` |

说明：

- `SERVER_IP` 为 `NULL` 表示**节点/链路汇总维度**，非空表示**具体实例维度**，查询与告警均以此区分。
- 告警记录保留 `server_ip`、`trigger_periods` 快照，便于回溯触发时口径。
- `t_mt_alert_rule.target_id` 与节点/链路是逻辑关联，删除节点/链路时由服务层按 `target_id` 清理并关闭相关告警。
- 建表脚本中的模板种子使用 `WHERE NOT EXISTS` 保证幂等，可重复执行。

---

## 8. HTTP 接口清单

统一响应体为 `JsonResult`：成功 `success=true`、`code="0000"`、数据在 `obj` 字段；失败 `success=false` 且 `msg` 为错误描述。

### 8.1 健康检查

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/health` | 业务侧探针，返回 `bigScreen-server is running` |
| GET | `/health` | 框架 Actuator 端点（非本项目 Controller），返回 `{"status":"UP"}` |

### 8.2 监控查询（`/api/monitor`）

| 方法 | 路径 | 参数 | 说明 |
| --- | --- | --- | --- |
| GET | `/test` | — | 连通性测试，返回 `ok` |
| GET | `/topology/nodes` | — | 全部节点（含绑定属性） |
| GET | `/topology/links` | — | 全部链路 |
| GET | `/display/nodeMetrics` | `serverIp?` | 所有节点最新指标；不传 IP 取汇总 |
| GET | `/display/nodeMetrics/{nodeId}` | `serverIp?` | 指定节点最新指标 |
| GET | `/display/nodeMetrics/{nodeId}/instances` | — | 该节点出现过的实例 IP 列表 |
| GET | `/display/linkMetrics` | — | 所有链路最新指标 |
| GET | `/display/linkMetrics/{linkId}` | — | 指定链路最新指标 |
| GET | `/history/node` | `nodeId,metricName,date,serverIp?` | 某日某指标历史（趋势对比） |
| GET | `/history/link` | `linkId,metricName,date` | 链路某日某指标历史 |
| GET | `/trend/node` | `nodeId,hours=1,serverIp?` | 节点全部指标近 N 小时趋势 |
| GET | `/trend/link` | `linkId,hours=1` | 链路全部指标近 N 小时趋势 |
| POST | `/collect/nodeMetrics` | `List<MetricValueDTO>` | 外部上报节点指标（写历史 + 最新 + 告警评估） |
| POST | `/collect/linkMetrics` | `List<MetricValueDTO>` | 外部上报链路指标 |

`MetricValueDTO` 字段：`nodeId`、`linkId`、`metricId`、`metricValue`、`serverIp`、`sourceTime`（`yyyy-MM-dd HH:mm:ss`）。

### 8.3 告警规则与记录（`/api/monitor/alert`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/rules` | 全部规则（含禁用） |
| GET | `/rules/target` | 按 `targetType` + `targetId` 查询 |
| POST | `/rules` | 新增规则（周期数自动归一化到 1~720） |
| PUT | `/rules/{id}` | 修改规则（同时清空该规则的内存连续周期计数） |
| DELETE | `/rules/{id}` | 删除规则（先解除告警记录外键引用） |
| GET | `/active` | 当前活跃告警 |
| GET | `/history` | 告警历史（`limit` 默认 100） |

### 8.4 拓扑编辑（`/api/monitor/topology`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/nodes` | 新增节点，按样式套用指标模板 |
| PUT | `/nodes/{id}` | 更新节点（未传字段保持原值，传空串表示清空） |
| DELETE | `/nodes/{id}` | 删除节点，级联清理指标/链路/告警，返回影响面 |
| GET | `/nodes/{id}/impact` | 删除前影响面统计 |
| PUT | `/nodes/layout` | 批量保存坐标（拖拽落库） |
| POST | `/links` | 新增链路（自连/重复/成环返回可读错误） |
| DELETE | `/links/{id}` | 删除链路，级联清理链路指标与告警 |

### 8.5 通知渠道（`/api/monitor/notify`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/channels` | 查询全部渠道（Webhook 地址脱敏） |
| POST | `/channels` | 新增渠道（校验企业微信 Webhook 格式） |
| PUT | `/channels/{id}` | 修改渠道（地址留空表示不修改） |
| DELETE | `/channels/{id}` | 删除渠道 |
| POST | `/channels/{id}/test` | 发送测试消息，返回企业微信 `errcode/errmsg` |

---

## 9. 告警与通知

### 9.1 告警规则

| 字段 | 说明 |
| --- | --- |
| `target_type` / `target_id` | 告警目标：节点或链路 |
| `metric_name` | 参与判断的指标名（须与指标定义一致） |
| `operator` | `>`、`>=`、`<`、`<=`、`==`、`!=` |
| `threshold_value` | 阈值（字符串，按数值比较） |
| `alert_level` | `warning`（黄色闪烁）/ `critical`（红色闪烁） |
| `trigger_periods` | 连续 N 个采集周期超阈才触发，默认 1 |
| `recover_periods` | 连续 N 个周期正常才自动恢复，默认 1 |
| `enabled` | 1 启用 / 0 禁用 |

### 9.2 连续周期抑制（`AlertStreakState`）

- 内存态计数器，key = `targetType|targetId|ruleId|scope`，`scope` 为实例 IP 或汇总维度 `#`。
- 上限 `MAX_PERIODS = 720`（约 1 天），服务层写入时统一归一化。
- 规则修改/删除、节点/链路删除时显式清理，避免旧计数套用新阈值。
- 惰性过期：某维度连续多个周期未被评估时计数自动作废；进程重启后计数归零，最多延迟 N 个周期触发，不会漏报。

### 9.3 告警记录

- 仅由采集评估自动产生与关闭；指标恢复时写 `status=resolved`、`resolve_type=AUTO`。
- 删除节点/链路导致规则失效时，相关活跃告警以 `MANUAL` 方式关闭。
- 同一批次内同一「指标 + 实例」只评估一次，防止重复上报把连续周期计数刷高。
- 节点规则会同时校验**汇总值**与**各实例值**，触发时按实例分别生成记录。

### 9.4 通知（企业微信）

`AlertNotifyService` 设计要点：

- **完全异步**：评估线程只入队（容量 500），推送在独立守护线程执行，外网慢/不通不影响采集。
- **批量聚合**：2 秒窗口内的多条告警合并为一条 Markdown，规避企业微信 20 条/分钟限频。
- **分片发送**：Markdown 内容按 3500 字节分片（官方上限 4096 字节）。
- **失败隔离**：任何异常只记日志，不向调用方抛出。
- **安全校验**：仅允许 `https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=...` 形式地址。
- **渠道缓存**：成功缓存 60 秒，加载失败短缓存 30 秒，避免库不可用时每条告警反复查库。
- **脱敏**：查询接口只返回 `webhookUrlMask`，不返回明文 key。
- 全局生效：所有 `enabled=1` 的渠道接收全部告警（触发一次、恢复一次）。

---

## 10. 前端页面与交互

### 10.1 路由

| 路由 | 页面 | 说明 |
| --- | --- | --- |
| `/` | 重定向 | 跳转 `/topology` |
| `/topology` | `TopologyPage.vue` | 拓扑大屏主页面 |
| `/instances` | `InstancePage.vue` | 实例详情页（支持 `?nodeId=&serverIp=` 直达） |

使用 hash 模式（`hui.config.js` 中 `mode: 'hash'`，`base: '/'`）。

### 10.2 拓扑页功能

- **SVG 画布**：双层网格（50px / 250px）、发光滤镜、贝塞尔连线、流动粒子（按链路 ID 引用路径，避免折叠后粒子错位）。
- **节点渲染**：名称、实例 IP 列表（一行一个，`LOG_FILTER` 节点不展示）、关键指标内联、告警着色。
- **层级折叠**：对有下游的节点显示 `−/+` 开关与隐藏数量角标；折叠状态持久化到 `localStorage`（key `topology_collapsed_nodes_v1`），默认全展开；隐藏子树内的告警会在角标上着色。
- **自由布局**：编辑模式下拖动节点，松手后批量调用 `PUT /nodes/layout` 落库。
- **右键菜单**：
  - 空白处：编辑模式下「在此处添加节点」；
  - 节点：展开/收起下游、编辑节点、创建连线、均匀排列子节点、配置告警、删除节点；
  - 连线：配置告警、删除连线。
- **编辑模式**：可新增/编辑节点（名称、描述、样式、绑定方式、实例 IP 列表、请求前缀）、拖拽创建连线、删除节点/连线（删除前展示影响面）。
- **详情面板**：点击节点/连线右滑打开，展示文本型指标卡片、分布型指标图表、数值型指标趋势图；支持近 1 小时 / 近 24 小时切换，节点可跳转实例页。
- **趋势弹窗**：选择日期，支持对比昨天 / 上周今天 / 自定义日期；对比日无数据时自动回溯；文本型指标以双日对照列表呈现，并展示均值变化与最新值变化。
- **告警浮窗**：活跃告警 / 历史记录两个 Tab；点击告警定位到目标节点/连线；齿轮按钮打开通知渠道设置。
- **告警规则弹窗**：查看已有规则、新增/编辑/删除，表单字段与后端规则一致。
- **通知渠道弹窗**：增删改渠道、发送测试消息。
- **轮询**：指标与告警每 2 分钟刷新，页面时钟每秒刷新。

### 10.3 实例详情页

- 顶部：返回拓扑图、当前节点名、实时时钟。
- 左侧：节点下拉、实例下拉、实例快捷列表（含告警数量角标）。
- 右侧：当前实例的活跃告警横幅 + 指标卡片网格。
- 实例 IP 来源优先级：节点属性 `instanceIps` → 指标表中出现过的 `SERVER_IP`。
- 无实例 IP 时回落到「节点汇总」口径查询。
- 节点描述包含「同机」时展示共享提示，避免把机器级指标误读为应用级。

### 10.4 请求与配置

- axios `baseURL` 取值顺序：`window.LOCAL_CONFIG.API_HOME` → `VUE_APP_BASE_API` → `/api`。
- 开发环境 `public/sysconfig.js` 配置 `API_HOME = "/daocloud-platform/bigScreen-server/v1"`。
- `hui.config.js` 代理规则：`/daocloud-platform/bigScreen-server/v1` → `http://localhost:9090/api`。
- 响应码归一化：后端 `code="0000"` 映射为前端 `"000000"`；数据字段 `obj` 映射为 `data`；`success=false` 视为失败并弹出提示。

---

## 11. 本地开发与启动

### 11.1 前置环境

| 依赖 | 版本要求 | 说明 |
| --- | --- | --- |
| JDK | 1.8 | `run-backend.bat` 中默认 `JAVA_HOME=D:\Java\jdk8`，按实际修改 |
| Maven | 3.6+ | 需可访问内网私服（`settings-local.xml`） |
| Node.js | 16.20.2 | 见 `bigScreen-web/set_env.bat` |
| Oracle | 11g XE | 默认 `127.0.0.1:1521:XE`，用户 `bigScreen/123` |
| Oracle 客户端 | 含 `sqlplus` | 执行建表脚本用 |

### 11.2 初始化数据库

当前 `_sql/` 目录只保留全量脚本：

```bash
# Windows 下建议先设置字符集，避免中文指标名入库乱码
set NLS_LANG=SIMPLIFIED CHINESE_CHINA.ZHS16GBK
sqlplus bigScreen/123@127.0.0.1:1521/XE @_sql/schema_full.sql
```

脚本内容：13 张表 + 索引 + 注释 + 节点/链路指标模板种子（幂等，可重复执行）。

> `run_sql_scripts.bat` 中的 `SQL_FILES` 清单仍是早期拆分脚本名（`monitor_tables.sql` 等），这些文件已合并进 `schema_full.sql`；
> 直接运行会全部跳过。可改为执行 `schema_full.sql`，或按需调整该清单。

### 11.3 启动后端

```powershell
# 方式一：项目脚本（先安装 bigScreen-api，再启动 server）
.\run-backend.bat

# 方式二：手动
mvn install -pl bigScreen-api -am -DskipTests
cd bigScreen-server
mvn spring-boot:run -Dserver.port=9090 -Dspring.profiles.active=dev
```

验证：

```bash
curl http://localhost:9090/api/health     # {"code":"000000","data":"bigScreen-server is running"}
curl http://localhost:9090/api/monitor/test  # ok
```

### 11.4 启动前端

```powershell
# 方式一：从项目根目录执行（内部调用 set_env.bat 后 npm run dev）
.\run-frontend.bat

# 方式二：手动执行
cd bigScreen-web
.\set_env.bat
npm install
npm run dev
```

- `npm run dev` 实际执行 `hui dev -b`（hui 脚手架，非 vue-cli-service）。
- `set_env.bat` 设置 Node 路径与内网 npm 私服 `npm_config_registry`，hui CLI 内部会安装 `@hui/bundler` 等私有包，必须指向私服。
- 访问地址形如 `http://localhost:8000/apps/bigScreen/`（以控制台输出为准）。

### 11.5 主要配置项

**`bigScreen-server/src/main/resources/application.yml`**

| 配置 | 说明 |
| --- | --- |
| `spring.datasource` | 业务库（`bigScreen`） |
| `spring.datasource.nginx-log` | Nginx 日志库（`standdbppos`） |
| `spring.datasource.tomcat-log` | Tomcat 日志库（`standdbppos`） |
| `spring.autoconfigure.exclude` | 排除 jrescloud 自带监控自动配置 |
| `spring.main.allow-circular-references` | 允许循环依赖（框架 Bean 需要） |
| `mybatis.mapper-locations` | `classpath:/mapper/*.xml` |
| `app.server.port` | 9090 |

**`middleware.properties`**：注册中心（ZooKeeper）、RPC（t3/12301）、RabbitMQ、默认数据源等框架级配置。

---

## 12. 打包与部署

### 12.1 后端

```bash
mvn clean package -DskipTests
```

- `bigScreen-server` 使用 `spring-boot-maven-plugin` 打成可执行 JAR（`executable=true`）。
- 部署包由 `bigScreen-zip` 的 `deploy/deploy.xml` 描述：`pack` 全量包、`systemType=daocloud-platform`、`appType=bigScreen-server`、集群模式、安装目录 `/home/srv/gjdf/`、默认 JVM 参数 `-Xms512m -Xmx1024m`。
- 包内包含启动/停止/校验脚本（`deploy/scripts/*.sh`）与配置模板（`deploy/template/*`），由道云平台调度执行。

### 12.2 前端

```bash
cd bigScreen-web
npm run see   # hui build --filename-hashing --see，产物 zip 输出到 os/ 与 docker/
```

> `npm run build` / `npm run serve` 走的是 `vue-cli-service`，当前构建体系已迁移到 hui，这两个命令不可用（会失败）。

### 12.3 版本号维护

- 后端：`bump-version-backend.ps1`
- 前端：`bump-version-frontend.ps1`（同步更新 `package.json` 的 `version`，影响打包名与 `publicPath`）

---

## 13. 运维排查指南

### 13.1 拓扑/指标无数据

按以下顺序排查：

1. **节点是否绑定 IP**：`t_mt_node_info.instance_ips` 为空则该节点不采集（客户端节点除外）。
   ```sql
   SELECT id, node_name, style_class, bind_type, instance_ips, request_prefix
     FROM t_mt_node_info ORDER BY style_class, node_name;
   ```
2. **指标定义是否存在**：`t_mt_node_metric_def` 中该节点是否有行；模板表 `t_mt_node_metric_tpl` 是否有对应 `style_class` 的种子。
3. **指标名是否一致**：`metric_name` 与采集器常量必须完全一致。
4. **远程日志是否有数据**：
   ```sql
   SELECT MAX(create_time) FROM T_MT_NGINX_ACCESS_LOG;
   SELECT MAX(create_time) FROM T_MT_TOMCAT_ACCESS_LOG;
   ```
5. **最新表是否有值**：`SELECT * FROM t_mt_metric_value_latest WHERE node_id = ? ORDER BY server_ip;`
6. **看后端日志**：`bigScreen-server/logs/` 下 `Bizlog.log` / `BizerrLog.log`，采集器启动与失败都会打日志。

### 13.2 指标曲线是水平直线

- 检查历史表该指标是否持续写入：`t_mt_metric_value_history`（按 `node_id + metric_id` 查最近记录）。
- 若历史表有数据但值完全相同，多为**日志源为静态样本数据**或**统计窗口内无新日志**，不是前端渲染问题。
- 样例/容器环境常见：日志表只有固定样本行，`MAX(create_time)` 不推进，采集结果恒定。

### 13.3 中文指标名乱码

`sqlplus` 执行脚本前必须设置 `NLS_LANG=SIMPLIFIED CHINESE_CHINA.ZHS16GBK`，否则中文指标名以错误编码入库，采集器按常量匹配将全部失败。

### 13.4 后端启动失败

| 现象 | 原因与处理 |
| --- | --- |
| `ConflictingBeanDefinitionException` | IDEA 增量编译残留旧包 `.class`；删除 `target/classes` 或执行 `mvn clean` / Rebuild |
| 端口占用 | 9090 被遗留 Maven/Java 进程占用；用 `netstat -ano` 过滤 9090 后结束对应进程 |
| 数据源连接失败 | `application.yml` 中三处 JDBC 地址/账号；远程日志库账号为 `standdbppos` |

### 13.5 前端启动/编译失败

| 现象 | 原因与处理 |
| --- | --- |
| `sass` 相关报错 | Node 版本过低；切换到 16.20.2 |
| `ERR_OSSL_EVP_UNSUPPORTED` | Node 17+ 与 webpack 4 的 OpenSSL 3 冲突；降级到 16.20.2 |
| 私有包安装失败 | `npm_config_registry` 未指向内网私服（见 `set_env.bat`） |
| 页面接口 404 | `sysconfig.js` 的 `API_HOME` 与 `hui.config.js` 代理前缀不一致 |

### 13.6 告警不触发

1. 规则是否 `enabled=1`，`metric_name` 是否与指标定义完全一致；
2. `trigger_periods` 是否过大（1 周期 ≈ 2 分钟）；
3. 检查 `t_mt_alert_record` 是否产生记录；无记录说明评估未命中阈值；
4. 修改规则后内存连续计数会清零，需要重新累计。

### 13.7 企业微信收不到消息

1. `t_mt_notify_channel` 中渠道 `enabled=1`；
2. Webhook 地址必须是 `qyapi.weixin.qq.com/cgi-bin/webhook/send?key=...`；
3. 在页面上点「测试」看返回的 `errcode/errmsg`；
4. 企业微信限频 20 条/分钟，服务已做 2 秒聚合，但突发大量告警仍可能被限流。

---

## 14. 已知注意事项

1. **`src/.hui/` 目录是脚手架生成产物**：`hui.js`、`router.js`、`store.js`、`polyfills.js`、`wss/`、`micro-app/` 每次启动都会重新生成，不要手动修改；路由改动请调整生成模板或 `hui.config.js`。
2. **`NodeInfo` 不再有独立实例绑定实体**：实例 IP 是节点属性字段（`instanceIps` 逗号分隔），前端通过拆分字符串得到实例列表。
3. **`src/api/dbQuery.js` 为历史遗留**：其中 `/api/monitor/topology/data`、`/nodeStatus`、`/linkMetrics` 等接口在现后端并不存在，页面实际使用 `utils/request.js` 直接请求；不要据此对接。
4. **节点编辑弹窗的注释已过时**：模板中 `<!-- 节点编辑弹窗已下线 -->` 是历史注释，实际编辑模式下右键「编辑节点」仍会打开表单。
5. **`/health` 被 Actuator 接管**：业务探针请使用 `/api/health`，否则永远匹配不到自定义 Controller。
6. **删除节点/链路会级联清理**：指标定义、指标值、绑定、告警规则都会被清理，活跃告警以 `MANUAL` 关闭；删除前接口会返回影响面，务必确认。
7. **`style_class` 仅允许 `client` / `nginx` / `app`**：新增样式必须同步修改前端连线尺寸计算表，否则连线端点会错位。
8. **同机多应用共享同一 IP 时指标相同**：Tomcat 日志只能定位到机器，无法区分具体应用，属于数据源限制。
9. **采集周期与前端刷新周期都是 2 分钟**：修改采集频率需同步调整 `@Scheduled(fixedRate)`、前端轮询间隔与 `AlertStreakState.COLLECT_INTERVAL_MS`。
10. **SQL 脚本**：`_sql/schema_full.sql` 是新机器部署的唯一全量脚本，存量库升级脚本已合并，不再单独维护。

---

## 附：快速索引

| 想了解 | 位置 |
| --- | --- |
| 启动入口 | `bigScreen-server/src/main/java/com/gjzg/ApplicationServerStarter.java` |
| 接口定义 | `bigScreen-server/src/main/java/com/gjzg/controller/` |
| 指标采集逻辑 | `bigScreen-server/src/main/java/com/gjzg/collector/` |
| 绑定关系构建 | `bigScreen-server/src/main/java/com/gjzg/service/TopologyBindingProvider.java` |
| 告警评估 | `bigScreen-server/src/main/java/com/gjzg/service/impl/MonitorServiceImpl.java` |
| 告警通知 | `bigScreen-server/src/main/java/com/gjzg/service/AlertNotifyService.java` |
| 核心 SQL | `bigScreen-server/src/main/resources/mapper/MonitorMapper.xml` |
| 建表与模板种子 | `_sql/schema_full.sql` |
| 拓扑页面 | `bigScreen-web/src/views/TopologyPage.vue` |
| 实例页面 | `bigScreen-web/src/views/InstancePage.vue` |
| 前端代理与打包 | `bigScreen-web/hui.config.js` |
| 部署描述 | `bigScreen-zip/deploy/deploy.xml` |
