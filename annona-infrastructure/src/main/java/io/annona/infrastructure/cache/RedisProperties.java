package io.annona.infrastructure.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redisson 连接配置（{@code annona.redis.*}）。
 *
 * <p>env → 属性的映射写在 {@code application.yaml}（{@code annona.redis.host: ${REDIS_HOST:localhost}}），
 * 不在此类里读 env——Boot relaxed binding 只把 {@code REDIS_HOST} 映到 {@code redis.host}，
 * 与 {@code annona.redis.host} 不同路径（阶段总结 D14 同型坑）。
 */
@ConfigurationProperties(prefix = "annona.redis")
public class RedisProperties {

    /** Redis 主机名（compose 内网为 {@code cache}）。 */
    private String host = "localhost";
    private int port = 6379;
    /** 无密码时留空串/null（本机开发常无密码）。 */
    private String password;
    private int database = 0;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public int getDatabase() {
        return database;
    }

    public void setDatabase(int database) {
        this.database = database;
    }
}
