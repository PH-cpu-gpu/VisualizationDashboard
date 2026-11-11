-- ============================================================
-- 1. 节点信息（t_mt_node_info）
--    实例 IP 是节点的多值属性，逗号分隔存放在 INSTANCE_IPS：
--      bind_type = IP          实例 IP（同机多应用可共享同一 IP）
--      bind_type = LOG_FILTER  用 REMOTE_ADDR 列表 + REQUEST_PREFIX 定位
-- ============================================================
CREATE TABLE t_mt_node_info (
    id             VARCHAR2(32)  NOT NULL,
    node_name      VARCHAR2(100) NOT NULL,
    description    VARCHAR2(500),
    pos_x          NUMBER,
    pos_y          NUMBER,
    node_type      VARCHAR2(20),
    style_class    VARCHAR2(50),
    bind_type      VARCHAR2(20)  DEFAULT 'IP',
    instance_ips   VARCHAR2(1000),
    request_prefix VARCHAR2(100),
    create_time    DATE          DEFAULT SYSDATE,
    CONSTRAINT pk_t_mt_node_info PRIMARY KEY (id),
    CONSTRAINT uk_node_name UNIQUE (node_name)
);

COMMENT ON TABLE t_mt_node_info IS '拓扑节点：名称、坐标、样式、实例 IP 绑定属性';
COMMENT ON COLUMN t_mt_node_info.bind_type IS '绑定方式：IP=实例IP，LOG_FILTER=REMOTE_ADDR+请求前缀';
COMMENT ON COLUMN t_mt_node_info.instance_ips IS 'IP 列表，逗号分隔，同一 IP 可属多个节点（同机多应用）';
COMMENT ON COLUMN t_mt_node_info.request_prefix IS '请求前缀，仅 LOG_FILTER 使用，如 /pposgjdf';


-- ============================================================
-- 2. 链路信息
-- ============================================================
CREATE TABLE t_mt_link_info (
    id             VARCHAR2(32) NOT NULL,
    source_node_id VARCHAR2(32) NOT NULL,
    target_node_id VARCHAR2(32) NOT NULL,
    link_name      VARCHAR2(100),
    create_time    DATE DEFAULT SYSDATE,
    CONSTRAINT pk_t_mt_link_info PRIMARY KEY (id),
    CONSTRAINT uk_link_nodes UNIQUE (source_node_id, target_node_id),
    CONSTRAINT fk_li_source_node FOREIGN KEY (source_node_id) REFERENCES t_mt_node_info (id),
    CONSTRAINT fk_li_target_node FOREIGN KEY (target_node_id) REFERENCES t_mt_node_info (id)
);

COMMENT ON TABLE t_mt_link_info IS '拓扑链路（有向边），前端层深由链路 BFS 推导';


-- ============================================================
-- 3. 节点指标定义
--    METRIC_NAME 必须与采集器常量完全一致（否则采集值无法匹配写入）
-- ============================================================
CREATE TABLE t_mt_node_metric_def (
    id            VARCHAR2(32)  NOT NULL,
    node_id       VARCHAR2(32)  NOT NULL,
    metric_name   VARCHAR2(100) NOT NULL,
    metric_unit   VARCHAR2(50),
    collect_desc  VARCHAR2(500),
    time_window   NUMBER        DEFAULT 3,
    sort_order    NUMBER        DEFAULT 0,
    create_time   DATE          DEFAULT SYSDATE,
    CONSTRAINT pk_t_mt_node_metric_def PRIMARY KEY (id),
    CONSTRAINT fk_nmd_node_id FOREIGN KEY (node_id) REFERENCES t_mt_node_info (id)
);

CREATE INDEX idx_nmd_node_id ON t_mt_node_metric_def (node_id);

COMMENT ON COLUMN t_mt_node_metric_def.time_window IS '指标统计窗口（分钟）';


-- ============================================================
-- 4. 节点指标最新值（大屏展示）
--    uk_mvl_node_metric 保证 节点+指标+实例 只有一条最新记录；
-- ============================================================
CREATE TABLE t_mt_metric_value_latest (
    id           VARCHAR2(32)  NOT NULL,
    collect_id   VARCHAR2(32),
    node_id      VARCHAR2(32)  NOT NULL,
    metric_id    VARCHAR2(32)  NOT NULL,
    server_ip    VARCHAR2(50),
    metric_value VARCHAR2(500),
    source_time  DATE,
    create_time  DATE          DEFAULT SYSDATE,
    CONSTRAINT pk_t_mt_metric_value_latest PRIMARY KEY (id),
    CONSTRAINT uk_mvl_node_metric UNIQUE (node_id, metric_id, server_ip),
    CONSTRAINT fk_mvl_node_id FOREIGN KEY (node_id) REFERENCES t_mt_node_info (id),
    CONSTRAINT fk_mvl_metric_id FOREIGN KEY (metric_id) REFERENCES t_mt_node_metric_def (id)
);

CREATE INDEX idx_mvl_node_id ON t_mt_metric_value_latest (node_id);
CREATE INDEX idx_mvl_metric_id ON t_mt_metric_value_latest (metric_id);
CREATE INDEX idx_mvl_collect_time ON t_mt_metric_value_latest (create_time);
CREATE INDEX idx_mvl_server_ip ON t_mt_metric_value_latest (server_ip);


-- ============================================================
-- 5. 节点指标历史（趋势图）
-- ============================================================
CREATE TABLE t_mt_metric_value_history (
    id           VARCHAR2(32)  NOT NULL,
    collect_id   VARCHAR2(32),
    node_id      VARCHAR2(32)  NOT NULL,
    metric_id    VARCHAR2(32)  NOT NULL,
    server_ip    VARCHAR2(50),
    metric_value VARCHAR2(500),
    source_time  DATE,
    create_time  DATE          DEFAULT SYSDATE,
    CONSTRAINT pk_t_mt_metric_value_history PRIMARY KEY (id),
    CONSTRAINT fk_mvh_node_id FOREIGN KEY (node_id) REFERENCES t_mt_node_info (id),
    CONSTRAINT fk_mvh_metric_id FOREIGN KEY (metric_id) REFERENCES t_mt_node_metric_def (id)
);

CREATE INDEX idx_mvh_node_id ON t_mt_metric_value_history (node_id);
CREATE INDEX idx_mvh_metric_id ON t_mt_metric_value_history (metric_id);
CREATE INDEX idx_mvh_collect_time ON t_mt_metric_value_history (create_time);
CREATE INDEX idx_mvh_node_metric_time ON t_mt_metric_value_history (node_id, metric_id, create_time);
CREATE INDEX idx_mvh_server_ip ON t_mt_metric_value_history (server_ip);


-- ============================================================
-- 6. 链路指标定义
-- ============================================================
CREATE TABLE t_mt_link_metric_def (
    id            VARCHAR2(32)  NOT NULL,
    link_id       VARCHAR2(32)  NOT NULL,
    metric_name   VARCHAR2(100) NOT NULL,
    metric_unit   VARCHAR2(50),
    collect_desc  VARCHAR2(500),
    time_window   NUMBER        DEFAULT 3,
    sort_order    NUMBER        DEFAULT 0,
    create_time   DATE          DEFAULT SYSDATE,
    CONSTRAINT pk_t_mt_link_metric_def PRIMARY KEY (id),
    CONSTRAINT fk_lmd_link_id FOREIGN KEY (link_id) REFERENCES t_mt_link_info (id)
);

CREATE INDEX idx_lmd_link_id ON t_mt_link_metric_def (link_id);


-- ============================================================
-- 7. 链路指标最新值
-- ============================================================
CREATE TABLE t_mt_link_value_latest (
    id           VARCHAR2(32)  NOT NULL,
    collect_id   VARCHAR2(32),
    link_id      VARCHAR2(32)  NOT NULL,
    metric_id    VARCHAR2(32)  NOT NULL,
    metric_value VARCHAR2(500),
    source_time  DATE,
    create_time  DATE          DEFAULT SYSDATE,
    CONSTRAINT pk_t_mt_link_value_latest PRIMARY KEY (id),
    CONSTRAINT uk_lvl_link_metric UNIQUE (link_id, metric_id),
    CONSTRAINT fk_lvl_link_id FOREIGN KEY (link_id) REFERENCES t_mt_link_info (id),
    CONSTRAINT fk_lvl_metric_id FOREIGN KEY (metric_id) REFERENCES t_mt_link_metric_def (id)
);

CREATE INDEX idx_lvl_link_id ON t_mt_link_value_latest (link_id);
CREATE INDEX idx_lvl_metric_id ON t_mt_link_value_latest (metric_id);
CREATE INDEX idx_lvl_collect_time ON t_mt_link_value_latest (create_time);


-- ============================================================
-- 8. 链路指标历史
-- ============================================================
CREATE TABLE t_mt_link_value_history (
    id           VARCHAR2(32)  NOT NULL,
    collect_id   VARCHAR2(32),
    link_id      VARCHAR2(32)  NOT NULL,
    metric_id    VARCHAR2(32)  NOT NULL,
    metric_value VARCHAR2(500),
    source_time  DATE,
    create_time  DATE          DEFAULT SYSDATE,
    CONSTRAINT pk_t_mt_link_value_history PRIMARY KEY (id),
    CONSTRAINT fk_lvh_link_id FOREIGN KEY (link_id) REFERENCES t_mt_link_info (id),
    CONSTRAINT fk_lvh_metric_id FOREIGN KEY (metric_id) REFERENCES t_mt_link_metric_def (id)
);

CREATE INDEX idx_lvh_link_id ON t_mt_link_value_history (link_id);
CREATE INDEX idx_lvh_metric_id ON t_mt_link_value_history (metric_id);
CREATE INDEX idx_lvh_collect_time ON t_mt_link_value_history (create_time);
CREATE INDEX idx_lvh_link_metric_time ON t_mt_link_value_history (link_id, metric_id, create_time);


-- ============================================================
-- 9. 节点指标模板（新增节点按 style_class 自动复制指标定义）
-- ============================================================
CREATE TABLE t_mt_node_metric_tpl (
    id            VARCHAR2(32)  NOT NULL,
    style_class   VARCHAR2(50)  NOT NULL,
    metric_name   VARCHAR2(100) NOT NULL,
    metric_unit   VARCHAR2(50),
    collect_desc  VARCHAR2(500),
    time_window   NUMBER        DEFAULT 3,
    sort_order    NUMBER        DEFAULT 0,
    create_time   DATE          DEFAULT SYSDATE,
    CONSTRAINT pk_t_mt_node_metric_tpl PRIMARY KEY (id),
    CONSTRAINT uk_nmt_style_name UNIQUE (style_class, metric_name)
);

COMMENT ON TABLE t_mt_node_metric_tpl IS '节点指标模板：新增节点按 style_class（client/nginx/app）套用';

-- ------------------------------------------------------------
-- 9.1 模板种子
--     client: 6 个（NginxLogMetricCollector 客户端指标）
--     nginx : 14 个（NginxLogMetricCollector 集群指标）
--     app   : 7 个（TomcatLogMetricCollector 应用指标）
-- ------------------------------------------------------------
INSERT INTO t_mt_node_metric_tpl
    (id, style_class, metric_name, metric_unit, collect_desc, time_window, sort_order, create_time)
SELECT REPLACE(RAWTOHEX(SYS_GUID()), '-'), t.style_class, t.metric_name, t.metric_unit,
       t.collect_desc, t.time_window, t.sort_order, SYSDATE
  FROM (
        SELECT 'client' AS style_class, '总IP数' AS metric_name, '个' AS metric_unit,
               '最近N分钟去重的客户端IP总数' AS collect_desc, 1440 AS time_window, 1 AS sort_order FROM DUAL
        UNION ALL SELECT 'client', 'IP活跃数', '个', '最近N分钟有请求的客户端IP数（去重）', 3, 2 FROM DUAL
        UNION ALL SELECT 'client', 'TOP访问IP', '', '最近N分钟访问次数最多的客户端IP，格式：IP(次数)', 1440, 3 FROM DUAL
        UNION ALL SELECT 'client', '并发连接数', '次/秒', '最近N分钟平均每秒请求数（并发近似值）', 1, 4 FROM DUAL
        UNION ALL SELECT 'client', '终端分布', '', '最近N分钟终端类型（iOS/Android/Windows/Mac/Linux）TOP3占比', 1440, 5 FROM DUAL
        UNION ALL SELECT 'client', 'UA类型', '', '最近N分钟浏览器类型（Chrome/Edge/Safari/Firefox等）TOP3占比', 1440, 6 FROM DUAL
       ) t
 WHERE NOT EXISTS (SELECT 1 FROM t_mt_node_metric_tpl x
                    WHERE x.style_class = t.style_class
                      AND x.metric_name = t.metric_name);

INSERT INTO t_mt_node_metric_tpl
    (id, style_class, metric_name, metric_unit, collect_desc, time_window, sort_order, create_time)
SELECT REPLACE(RAWTOHEX(SYS_GUID()), '-'), t.style_class, t.metric_name, t.metric_unit,
       t.collect_desc, t.time_window, t.sort_order, SYSDATE
  FROM (
        SELECT 'nginx' AS style_class, '每秒请求数(QPS)' AS metric_name, '次/秒' AS metric_unit,
               '最近N分钟请求总数/窗口秒数（按集群实例IP过滤）' AS collect_desc, 3 AS time_window, 1 AS sort_order FROM DUAL
        UNION ALL SELECT 'nginx', '请求错误率', '%', '最近N分钟状态码>=400的请求占总请求数的百分比', 3, 2 FROM DUAL
        UNION ALL SELECT 'nginx', '平均响应时间', '毫秒', '最近N分钟请求平均处理时间（REQUEST_TIME 秒转毫秒）', 3, 3 FROM DUAL
        UNION ALL SELECT 'nginx', 'Nginx进程存活', '', '最近3分钟有日志数据则为1(存活)，否则为0(宕机)', 3, 4 FROM DUAL
        UNION ALL SELECT 'nginx', '5xx错误率', '%', '最近N分钟状态码>=500的请求占总请求数的百分比', 3, 5 FROM DUAL
        UNION ALL SELECT 'nginx', '4xx错误率', '%', '最近N分钟状态码400~499的请求占总请求数的百分比（仅汇总维度）', 3, 6 FROM DUAL
        UNION ALL SELECT 'nginx', 'P95请求耗时', '毫秒', '最近N分钟请求处理时间的95分位值（P95）', 3, 7 FROM DUAL
        UNION ALL SELECT 'nginx', '上游响应耗时', '毫秒', '最近N分钟upstream请求的平均响应耗时（仅汇总维度）', 3, 8 FROM DUAL
        UNION ALL SELECT 'nginx', '上游状态码分布', '', '最近N分钟upstream请求的上游状态码 200/502/503/504 占比分布（仅汇总维度）', 3, 9 FROM DUAL
        UNION ALL SELECT 'nginx', 'P99请求耗时', '毫秒', '最近N分钟请求处理时间的99分位值（P99，PERCENTILE_CONT(0.99)，秒转毫秒）', 3, 10 FROM DUAL
        UNION ALL SELECT 'nginx', '慢请求占比', '%', '最近N分钟REQUEST_TIME>1秒的请求占全部请求的百分比', 3, 11 FROM DUAL
        UNION ALL SELECT 'nginx', 'Nginx自身处理耗时', '毫秒', '最近N分钟AVG(REQUEST_TIME-UP_RESPONSE_TIME)（负值归零，秒转毫秒，仅汇总维度）', 3, 12 FROM DUAL
        UNION ALL SELECT 'nginx', '上游耗时占比', '%', '最近N分钟SUM(UP_RESPONSE_TIME)/SUM(REQUEST_TIME)*100（仅汇总维度）', 3, 13 FROM DUAL
        UNION ALL SELECT 'nginx', '上游错误率', '%', '最近N分钟上游状态码502/503/504的请求占UP_STATUS非空请求的百分比（仅汇总维度）', 3, 14 FROM DUAL
       ) t
 WHERE NOT EXISTS (SELECT 1 FROM t_mt_node_metric_tpl x
                    WHERE x.style_class = t.style_class
                      AND x.metric_name = t.metric_name);

INSERT INTO t_mt_node_metric_tpl
    (id, style_class, metric_name, metric_unit, collect_desc, time_window, sort_order, create_time)
SELECT REPLACE(RAWTOHEX(SYS_GUID()), '-'), t.style_class, t.metric_name, t.metric_unit,
       t.collect_desc, t.time_window, t.sort_order, SYSDATE
  FROM (
        SELECT 'app' AS style_class, 'QPS' AS metric_name, '次/秒' AS metric_unit,
               '最近N分钟节点归属实例IP的Tomcat访问日志请求总数/窗口秒数' AS collect_desc, 3 AS time_window, 1 AS sort_order FROM DUAL
        UNION ALL SELECT 'app', '请求失败率', '%', '最近N分钟STATUS>=400的请求占总请求数的百分比', 3, 2 FROM DUAL
        UNION ALL SELECT 'app', '5xx错误率', '%', '最近N分钟STATUS>=500的请求占总请求数的百分比', 3, 3 FROM DUAL
        UNION ALL SELECT 'app', '接口请求量TOP', '', '最近N分钟按接口路径聚合的请求量TOP5（剔除查询参数，格式：path(次数) | ...）', 3, 4 FROM DUAL
        UNION ALL SELECT 'app', '请求错误率', '%', '最近N分钟(4xx+5xx)请求占总请求数的百分比', 3, 5 FROM DUAL
        UNION ALL SELECT 'app', '4xx错误率', '%', '最近N分钟STATUS 400~499的请求占总请求数的百分比（仅单实例维度采集）', 3, 6 FROM DUAL
        UNION ALL SELECT 'app', '进程存活', '', '最近3分钟有日志数据则为1(存活)，否则为0(宕机)（仅单实例维度采集）', 3, 7 FROM DUAL
       ) t
 WHERE NOT EXISTS (SELECT 1 FROM t_mt_node_metric_tpl x
                    WHERE x.style_class = t.style_class
                      AND x.metric_name = t.metric_name);


-- ============================================================
-- 9.2 链路指标模板（新建链路按两端节点样式自动复制指标定义）
--     采集器仅对“客户端 -> nginx”链路采集指标，其余链路纯展示
-- ============================================================
CREATE TABLE t_mt_link_metric_tpl (
    id            VARCHAR2(32)  NOT NULL,
    source_style  VARCHAR2(50)  NOT NULL,
    target_style  VARCHAR2(50)  NOT NULL,
    metric_name   VARCHAR2(100) NOT NULL,
    metric_unit   VARCHAR2(50),
    collect_desc  VARCHAR2(500),
    time_window   NUMBER        DEFAULT 3,
    sort_order    NUMBER        DEFAULT 0,
    create_time   DATE          DEFAULT SYSDATE,
    CONSTRAINT pk_t_mt_link_metric_tpl PRIMARY KEY (id),
    CONSTRAINT uk_lmt_style_name UNIQUE (source_style, target_style, metric_name)
);

COMMENT ON TABLE t_mt_link_metric_tpl IS '链路指标模板：按两端节点样式（client->nginx）套用，新建链路时复制到 t_mt_link_metric_def';


-- ============================================================
-- 9.3 链路指标模板种子（client -> nginx，5 个，名称与 NginxLogMetricCollector 常量一致）
-- ============================================================
INSERT INTO t_mt_link_metric_tpl
    (id, source_style, target_style, metric_name, metric_unit, collect_desc, time_window, sort_order, create_time)
SELECT REPLACE(RAWTOHEX(SYS_GUID()), '-'), t.source_style, t.target_style, t.metric_name, t.metric_unit,
       t.collect_desc, t.time_window, t.sort_order, SYSDATE
  FROM (
        SELECT 'client' AS source_style, 'nginx' AS target_style, 'TOP请求路径' AS metric_name, '' AS metric_unit,
               '最近N分钟请求次数最多的路径（剔除查询参数），格式：path(次数)' AS collect_desc, 3 AS time_window, 1 AS sort_order FROM DUAL
        UNION ALL SELECT 'client', 'nginx', '请求总数', '次', '最近N分钟经过该链路的请求总数', 3, 2 FROM DUAL
        UNION ALL SELECT 'client', 'nginx', '请求失败次数', '次', '最近N分钟状态码>=400的请求数', 3, 3 FROM DUAL
        UNION ALL SELECT 'client', 'nginx', '请求平均耗时', '毫秒', '最近N分钟请求平均处理时间', 3, 4 FROM DUAL
        UNION ALL SELECT 'client', 'nginx', '请求成功率', '%', '最近N分钟成功请求（状态码<400）占总请求数的百分比', 3, 5 FROM DUAL
       ) t
 WHERE NOT EXISTS (SELECT 1 FROM t_mt_link_metric_tpl x
                    WHERE x.source_style = t.source_style
                      AND x.target_style = t.target_style
                      AND x.metric_name = t.metric_name);


-- ============================================================
-- 10. 告警规则
--     target_type / target_id 逻辑关联节点或链路，不建外键（删除目标时按 target_id 清理）
-- ============================================================
CREATE TABLE t_mt_alert_rule (
    id              VARCHAR2(32)  NOT NULL,
    target_type     VARCHAR2(10)  NOT NULL,
    target_id       VARCHAR2(32)  NOT NULL,
    metric_name     VARCHAR2(100) NOT NULL,
    operator        VARCHAR2(5)   NOT NULL,
    threshold_value VARCHAR2(100) NOT NULL,
    alert_level     VARCHAR2(20)  DEFAULT 'warning',
    enabled         NUMBER(1)     DEFAULT 1,
    trigger_periods NUMBER(4)     DEFAULT 1 NOT NULL,
    recover_periods NUMBER(4)     DEFAULT 1 NOT NULL,
    create_time     DATE          DEFAULT SYSDATE,
    CONSTRAINT pk_t_mt_alert_rule PRIMARY KEY (id)
);

CREATE INDEX idx_ar_target ON t_mt_alert_rule (target_type, target_id);
CREATE INDEX idx_ar_enabled ON t_mt_alert_rule (enabled);

COMMENT ON COLUMN t_mt_alert_rule.target_type IS 'node / link';
COMMENT ON COLUMN t_mt_alert_rule.trigger_periods IS '持续时间抑制：连续N个采集周期超阈才触发告警，1=单周期即触发';
COMMENT ON COLUMN t_mt_alert_rule.recover_periods IS '恢复抑制：连续N个采集周期正常才自动解除告警，1=单周期正常即解除';


-- ============================================================
-- 11. 告警记录
--     只由采集评估自动产生与关闭：指标恢复正常时写 status=resolved、resolve_type=AUTO
-- ============================================================
CREATE TABLE t_mt_alert_record (
    id              VARCHAR2(32)  NOT NULL,
    rule_id         VARCHAR2(32),
    target_type     VARCHAR2(10)  NOT NULL,
    target_id       VARCHAR2(32)  NOT NULL,
    target_name     VARCHAR2(100),
    server_ip       VARCHAR2(64),
    metric_name     VARCHAR2(100) NOT NULL,
    metric_value    VARCHAR2(100),
    threshold_value VARCHAR2(100),
    operator        VARCHAR2(5),
    alert_level     VARCHAR2(20),
    trigger_periods NUMBER(4),
    status          VARCHAR2(20)  DEFAULT 'active',
    alert_time      DATE          DEFAULT SYSDATE,
    resolve_time    DATE,
    resolve_type    VARCHAR2(10),
    CONSTRAINT pk_t_mt_alert_record PRIMARY KEY (id),
    CONSTRAINT fk_arec_rule FOREIGN KEY (rule_id) REFERENCES t_mt_alert_rule (id) ON DELETE SET NULL
);

CREATE INDEX idx_arec_status ON t_mt_alert_record (status);
CREATE INDEX idx_arec_target ON t_mt_alert_record (target_type, target_id);
CREATE INDEX idx_arec_time ON t_mt_alert_record (alert_time);
CREATE INDEX idx_arec_rule ON t_mt_alert_record (rule_id);
CREATE INDEX idx_arec_rule_ip ON t_mt_alert_record (rule_id, server_ip);

COMMENT ON COLUMN t_mt_alert_record.server_ip IS '触发告警的实例 IP；为空表示节点/链路汇总维度';
COMMENT ON COLUMN t_mt_alert_record.status IS 'active / resolved';
COMMENT ON COLUMN t_mt_alert_record.trigger_periods IS '触发时生效的连续超阈周期数快照（同一次告警内保持不变）';
COMMENT ON COLUMN t_mt_alert_record.resolve_type IS '恢复方式：AUTO=指标自然恢复，MANUAL=删除节点/链路时关闭';


-- ============================================================
-- 12. 告警通知渠道（全局统一渠道）
--     所有 ENABLED = 1 的渠道都会接收全部告警：触发时推一次，指标恢复时再推一次
--     CHANNEL_TYPE 预留扩展：WECOM_WEBHOOK=企业微信群机器人
-- ============================================================
CREATE TABLE t_mt_notify_channel (
    id           VARCHAR2(32)   NOT NULL,
    channel_name VARCHAR2(100)  NOT NULL,
    channel_type VARCHAR2(30)   DEFAULT 'WECOM_WEBHOOK' NOT NULL,
    webhook_url  VARCHAR2(1000) NOT NULL,
    enabled      NUMBER(1)      DEFAULT 1,
    create_time  DATE           DEFAULT SYSDATE,
    update_time  DATE,
    CONSTRAINT pk_t_mt_notify_channel PRIMARY KEY (id)
);

CREATE INDEX idx_nc_enabled ON t_mt_notify_channel (enabled);

COMMENT ON TABLE t_mt_notify_channel IS '告警通知渠道：全局生效，启用后接收全部告警触发/恢复消息';
COMMENT ON COLUMN t_mt_notify_channel.channel_type IS '渠道类型：WECOM_WEBHOOK=企业微信群机器人';
COMMENT ON COLUMN t_mt_notify_channel.webhook_url IS '企业微信机器人 Webhook 地址（含 key，敏感信息，接口出参脱敏）';
COMMENT ON COLUMN t_mt_notify_channel.enabled IS '1启用 0禁用；仅启用的渠道会收到推送';


COMMIT;
