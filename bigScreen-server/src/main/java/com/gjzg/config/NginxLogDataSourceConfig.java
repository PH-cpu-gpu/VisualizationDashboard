package com.gjzg.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Nginx远程日志库数据源配置
 * 用于查询远程 DAOCLOUD_PLATFORM.T_MT_NGINX_ACCESS_LOG 表
 */
@Configuration
public class NginxLogDataSourceConfig {

    @Primary
    @Bean(name = "dataSource")
    @ConfigurationProperties(prefix = "spring.datasource")
    public DataSource dataSource() {
        return new HikariDataSource();
    }

    @Bean(name = "nginxLogDataSource")
    @ConfigurationProperties(prefix = "spring.datasource.nginx-log")
    public DataSource nginxLogDataSource() {
        return new HikariDataSource();
    }

    @Bean(name = "nginxLogJdbcTemplate")
    public JdbcTemplate nginxLogJdbcTemplate(@Qualifier("nginxLogDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
