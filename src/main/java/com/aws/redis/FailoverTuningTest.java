package com.aws.redis;

import org.HdrHistogram.Histogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ElastiCache Failover 测试主程序。
 *
 * 功能：
 * 1. 持续向 ElastiCache Primary Endpoint 发读写请求
 * 2. 记录每次请求的延迟、成功/失败
 * 3. failover 触发后统计：首次报错时间、恢复时间、总中断时长、错误数
 * 4. 生成 p50/p95/p99 延迟分布
 *
 * 用法：
 *   java -jar target/jedis-failover-tuning-1.0.0.jar <primary-endpoint> [port] [config]
 *
 * 参数：
 *   primary-endpoint: ElastiCache Primary Endpoint（不带端口）
 *   port: 默认 6379
 *   config: default | conservative | aggressive | ultra (默认 aggressive)
 */
public class FailoverTuningTest {

    private static final Logger log = LoggerFactory.getLogger(FailoverTuningTest.class);
    private static final int SOCKET_TIMEOUT_MS = 2000;
    private static final int CONNECTION_TIMEOUT_MS = 2000;

    // 统计
    private final AtomicLong totalOps = new AtomicLong(0);
    private final AtomicLong successOps = new AtomicLong(0);
    private final AtomicLong failedOps = new AtomicLong(0);
    private final AtomicLong consecutiveFailures = new AtomicLong(0);
    private final AtomicBoolean inFailover = new AtomicBoolean(false);
    private volatile long failoverStartMs = 0;
    private volatile long failoverEndMs = 0;
    private final Histogram latencyHistogram = new Histogram(60_000_000L, 3); // max 60s, 3 sig figs

    private final String host;
    private final int port;
    private final JedisPoolConfig poolConfig;
    private final String configName;

    public FailoverTuningTest(String host, int port, JedisPoolConfig poolConfig, String configName) {
        this.host = host;
        this.port = port;
        this.poolConfig = poolConfig;
        this.configName = configName;
    }

    public void run(int durationSeconds, int opsPerSecond) {
        log.info("========================================");
        log.info("Failover Tuning Test");
        log.info("========================================");
        log.info("Endpoint: {}:{}", host, port);
        log.info("Config: {}", configName);
        log.info("Duration: {}s | Target OPS: {}", durationSeconds, opsPerSecond);
        log.info("Socket Timeout: {}ms | Connection Timeout: {}ms", SOCKET_TIMEOUT_MS, CONNECTION_TIMEOUT_MS);
        log.info("Pool: maxTotal={}, maxIdle={}, minIdle={}, maxWait={}ms",
                poolConfig.getMaxTotal(), poolConfig.getMaxIdle(),
                poolConfig.getMinIdle(), poolConfig.getMaxWaitMillis());
        log.info("testOnBorrow={}, testWhileIdle={}, evictionInterval={}ms",
                poolConfig.getTestOnBorrow(), poolConfig.getTestWhileIdle(),
                poolConfig.getTimeBetweenEvictionRunsMillis());
        log.info("========================================");

        JedisPool pool = new JedisPool(poolConfig, host, port,
                CONNECTION_TIMEOUT_MS, SOCKET_TIMEOUT_MS, null, 0, null);

        // 预热连接池
        warmUp(pool);

        long intervalMs = 1000 / opsPerSecond;
        long startTime = System.currentTimeMillis();
        long endTime = startTime + (durationSeconds * 1000L);

        log.info("Test started at: {}", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        log.info("Press Ctrl+C to stop early. Trigger failover in AWS console now.");
        log.info("----------------------------------------");

        // 主循环
        while (System.currentTimeMillis() < endTime) {
            long opStart = System.nanoTime();
            boolean success = doOperation(pool);
            long opDurationUs = (System.nanoTime() - opStart) / 1000; // microseconds

            totalOps.incrementAndGet();

            if (success) {
                successOps.incrementAndGet();
                latencyHistogram.recordValue(Math.min(opDurationUs, 60_000_000L));

                if (consecutiveFailures.get() > 0) {
                    // 从失败恢复
                    long recoveryTime = System.currentTimeMillis();
                    if (inFailover.compareAndSet(true, false)) {
                        failoverEndMs = recoveryTime;
                        long downtime = failoverEndMs - failoverStartMs;
                        log.warn(">>> RECOVERED! Downtime: {}ms ({} consecutive failures before recovery)",
                                downtime, consecutiveFailures.get());
                    }
                    consecutiveFailures.set(0);
                }
            } else {
                failedOps.incrementAndGet();
                long failures = consecutiveFailures.incrementAndGet();

                if (failures == 1 && inFailover.compareAndSet(false, true)) {
                    failoverStartMs = System.currentTimeMillis();
                    log.error(">>> FAILOVER DETECTED at {}",
                            LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                }

                // 每 10 次连续失败打一次日志（避免刷屏）
                if (failures % 10 == 0) {
                    log.error("    ... {} consecutive failures ({}ms since failover start)",
                            failures, System.currentTimeMillis() - failoverStartMs);
                }
            }

            // 控制 OPS
            try {
                Thread.sleep(Math.max(1, intervalMs - (opDurationUs / 1000)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        pool.close();
        printReport(durationSeconds);
    }

    private boolean doOperation(JedisPool pool) {
        try (Jedis jedis = pool.getResource()) {
            long ts = System.currentTimeMillis();
            String key = "failover_test:" + (ts % 1000);

            // 写操作
            jedis.setex(key, 300, "v_" + ts);

            // 读操作（验证一致性）
            String val = jedis.get(key);
            if (val == null) {
                log.debug("Read returned null for key={} (possible stale read after failover)", key);
            }

            return true;
        } catch (JedisConnectionException e) {
            log.trace("Connection error: {}", e.getMessage());
            return false;
        } catch (JedisException e) {
            log.trace("Redis error: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.trace("Unexpected error: {}", e.getMessage());
            return false;
        }
    }

    private void warmUp(JedisPool pool) {
        log.info("Warming up connection pool...");
        int warmUpCount = Math.min(poolConfig.getMinIdle(), poolConfig.getMaxTotal());
        warmUpCount = Math.max(warmUpCount, 3);
        int successCount = 0;

        for (int i = 0; i < warmUpCount; i++) {
            try (Jedis jedis = pool.getResource()) {
                jedis.ping();
                successCount++;
            } catch (Exception e) {
                log.warn("Warm-up connection {} failed: {}", i, e.getMessage());
            }
        }
        log.info("Warm-up complete: {}/{} connections established", successCount, warmUpCount);
    }

    private void printReport(int durationSeconds) {
        log.info("");
        log.info("========================================");
        log.info("TEST REPORT");
        log.info("========================================");
        log.info("Config: {}", configName);
        log.info("Duration: {}s", durationSeconds);
        log.info("----------------------------------------");
        log.info("Total Operations: {}", totalOps.get());
        log.info("Successful:       {} ({}%)", successOps.get(),
                String.format("%.2f", totalOps.get() > 0 ? (successOps.get() * 100.0 / totalOps.get()) : 0.0));
        log.info("Failed:           {} ({}%)", failedOps.get(),
                String.format("%.2f", totalOps.get() > 0 ? (failedOps.get() * 100.0 / totalOps.get()) : 0.0));
        log.info("----------------------------------------");

        if (failoverStartMs > 0) {
            long downtime = failoverEndMs > 0 ? (failoverEndMs - failoverStartMs) : -1;
            log.info("FAILOVER METRICS:");
            log.info("  First error at:    {} (epoch: {}ms)",
                    Instant.ofEpochMilli(failoverStartMs), failoverStartMs);
            if (failoverEndMs > 0) {
                log.info("  Recovered at:      {} (epoch: {}ms)",
                        Instant.ofEpochMilli(failoverEndMs), failoverEndMs);
                log.info("  >>> TOTAL DOWNTIME: {}ms ({}s) <<<", downtime, String.format("%.1f", downtime / 1000.0));
            } else {
                log.info("  >>> NOT RECOVERED during test window <<<");
            }
        } else {
            log.info("No failover detected during test.");
        }

        log.info("----------------------------------------");
        log.info("LATENCY (successful ops, microseconds):");
        log.info("  p50:  {}us", latencyHistogram.getValueAtPercentile(50));
        log.info("  p95:  {}us", latencyHistogram.getValueAtPercentile(95));
        log.info("  p99:  {}us", latencyHistogram.getValueAtPercentile(99));
        log.info("  p999: {}us", latencyHistogram.getValueAtPercentile(99.9));
        log.info("  max:  {}us", latencyHistogram.getMaxValue());
        log.info("========================================");
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java -jar jedis-failover-tuning.jar <primary-endpoint> [port] [config] [duration-sec] [ops-per-sec]");
            System.err.println();
            System.err.println("  config options: default | conservative | aggressive | ultra");
            System.err.println("  duration-sec: test duration in seconds (default: 300)");
            System.err.println("  ops-per-sec:  target operations per second (default: 100)");
            System.err.println();
            System.err.println("Example:");
            System.err.println("  java -jar target/jedis-failover-tuning-1.0.0.jar my-cluster.xxxxx.ng.0001.use1.cache.amazonaws.com 6379 aggressive 300 100");
            System.exit(1);
        }

        String host = args[0];
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 6379;
        String configName = args.length > 2 ? args[2] : "aggressive";
        int duration = args.length > 3 ? Integer.parseInt(args[3]) : 300;
        int opsPerSec = args.length > 4 ? Integer.parseInt(args[4]) : 100;

        JedisPoolConfig poolConfig;
        switch (configName.toLowerCase()) {
            case "default":
                poolConfig = PoolConfig.defaultConfig();
                break;
            case "conservative":
                poolConfig = PoolConfig.conservativeConfig();
                break;
            case "aggressive":
                poolConfig = PoolConfig.aggressiveConfig();
                break;
            case "ultra":
                poolConfig = PoolConfig.ultraShortLivedConfig();
                break;
            default:
                System.err.println("Unknown config: " + configName);
                System.err.println("Options: default | conservative | aggressive | ultra");
                System.exit(1);
                return;
        }

        FailoverTuningTest test = new FailoverTuningTest(host, port, poolConfig, configName);
        test.run(duration, opsPerSec);
    }
}
