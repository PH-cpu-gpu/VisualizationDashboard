package com.gjzg.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Tomcat远程日志库数据源配置
 * 用于查询远程 DAOCLOUD_PLATFORM.T_MT_TOMCAT_ACCESS_LOG 表
 * 说明：当前 Tomcat 日志表与 Nginx 日志表在同一库，连接参数一致，
 *       但独立配置（spring.datasource.tomcat-log），便于后续库迁移或单独调整连接池
 */
@Configuration
public class TomcatLogDataSourceConfig {

    @Bean(name = "tomcatLogDataSource")
    @ConfigurationProperties(prefix = "spring.datasource.tomcat-log")
    public DataSource tomcatLogDataSource() {
        return new HikariDataSource();
    }

    @Bean(name = "tomcatLogJdbcTemplate")
    public JdbcTemplate tomcatLogJdbcTemplate(@Qualifier("tomcatLogDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
