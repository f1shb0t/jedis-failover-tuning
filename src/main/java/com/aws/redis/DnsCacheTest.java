package com.aws.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * DNS 缓存影响测试。
 *
 * ElastiCache failover 后，Primary Endpoint DNS 指向新主节点。
 * JVM 默认永久缓存 DNS（networkaddress.cache.ttl），导致客户端持续连旧 IP。
 *
 * 此测试验证 JVM DNS TTL 配置对 failover 恢复的影响。
 *
 * 用法：
 *   # 不设 DNS TTL（JVM 默认永久缓存）—— 对比基准
 *   java -jar target/jedis-failover-tuning-1.0.0.jar <endpoint> 6379 aggressive 300 100
 *
 *   # 设置 DNS TTL = 5s（推荐）
 *   java -Dsun.net.inetaddr.ttl=5 -jar target/jedis-failover-tuning-1.0.0.jar <endpoint> 6379 aggressive 300 100
 *
 *   # 或通过 Security Manager 设置（更通用）
 *   java -Dnetworkaddress.cache.ttl=5 -jar ...
 */
public class DnsCacheTest {

    private static final Logger log = LoggerFactory.getLogger(DnsCacheTest.class);

    /**
     * 打印当前 JVM DNS TTL 配置，帮助确认设置是否生效。
     */
    public static void printDnsCacheSettings() {
        String ttl = java.security.Security.getProperty("networkaddress.cache.ttl");
        String negativeTtl = java.security.Security.getProperty("networkaddress.cache.negative.ttl");
        String sysProp = System.getProperty("sun.net.inetaddr.ttl");

        log.info("========================================");
        log.info("JVM DNS Cache Settings:");
        log.info("  networkaddress.cache.ttl = {} (Security property)", ttl != null ? ttl : "NOT SET (infinite)");
        log.info("  networkaddress.cache.negative.ttl = {}", negativeTtl != null ? negativeTtl : "NOT SET (default 10s)");
        log.info("  sun.net.inetaddr.ttl = {} (System property)", sysProp != null ? sysProp : "NOT SET");
        log.info("========================================");

        if (ttl == null && sysProp == null) {
            log.warn("⚠️  DNS TTL not configured! JVM will cache DNS indefinitely.");
            log.warn("⚠️  After failover, connections may go to the OLD primary IP.");
            log.warn("⚠️  Recommend: -Dsun.net.inetaddr.ttl=5");
        }
    }

    /**
     * 解析 endpoint 的当前 IP，用于 failover 前后对比。
     */
    public static void resolveAndPrint(String host) {
        try {
            java.net.InetAddress[] addresses = java.net.InetAddress.getAllByName(host);
            log.info("DNS resolution for {}: ", host);
            for (java.net.InetAddress addr : addresses) {
                log.info("  -> {}", addr.getHostAddress());
            }
        } catch (Exception e) {
            log.error("DNS resolution failed: {}", e.getMessage());
        }
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java [-Dsun.net.inetaddr.ttl=5] -cp ... com.aws.redis.DnsCacheTest <endpoint> [port]");
            System.exit(1);
        }

        String host = args[0];
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 6379;

        printDnsCacheSettings();
        resolveAndPrint(host);

        log.info("");
        log.info("Continuous DNS monitoring (resolve every 2s for 120s)...");
        log.info("Trigger failover in AWS Console now!");
        log.info("");

        long endTime = System.currentTimeMillis() + 120_000;
        String lastIp = "";

        while (System.currentTimeMillis() < endTime) {
            try {
                String currentIp = java.net.InetAddress.getByName(host).getHostAddress();
                if (!currentIp.equals(lastIp)) {
                    log.warn(">>> DNS CHANGED: {} -> {} at {}",
                            lastIp.isEmpty() ? "(initial)" : lastIp,
                            currentIp,
                            LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                    lastIp = currentIp;
                }
                Thread.sleep(2000);
            } catch (Exception e) {
                log.error("Error: {}", e.getMessage());
            }
        }
    }
}
