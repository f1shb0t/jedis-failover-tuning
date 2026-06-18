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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ElastiCache Failover —— 业务读写影响时间测试。
 *
 * 与 {@link FailoverTuningTest}（纯压测视角，统计底层连接 downtime / p99）不同，
 * 本类站在【真实业务代码】视角：每个「业务请求」由一组读写组合（事务）构成，
 * 任意一步失败即整笔业务失败。目标是精确测量 failover 对【业务请求】的影响时间窗口，
 * 即业务侧真正"不可用"的时长，而非底层单次 GET/SET 的中断。
 *
 * 业务事务模型（模拟一次典型的会话更新场景）：
 *   1. GET  session:{id}        读取用户会话
 *   2. SET  session:{id}        更新会话（带 TTL）
 *   3. INCR counter:{id}        业务计数器自增
 *   4. GET  counter:{id}        回读校验
 * 上述 4 步任意一步抛异常 → 本笔业务请求判定为失败。
 *
 * 统计指标（区别于底层 downtime）：
 *   - 业务影响开始：第一笔失败业务请求的时间
 *   - 业务影响结束：失败后第一笔恢复成功的业务请求时间
 *   - 业务影响窗口（business impact window）：上述两者之差（ms）
 *   - 受影响期间失败的业务请求笔数
 *   - failover 前后业务延迟基线对比（p50/p99 是否回到基线）
 *
 * 用法：
 *   java -cp target/jedis-failover-tuning-1.0.0.jar com.aws.redis.BusinessImpactTest \
 *        <primary-endpoint> [port] [config] [duration-sec] [qps]
 *
 * 参数：
 *   primary-endpoint: ElastiCache Primary Endpoint（不带端口）
 *   port: 默认 6379
 *   config: default | conservative | aggressive | ultra（默认 aggressive）
 *   duration-sec: 测试时长秒（默认 300）
 *   qps: 目标业务请求/秒（默认 50，每笔含 4 个 Redis 命令）
 */
public class BusinessImpactTest {

    private static final Logger log = LoggerFactory.getLogger(BusinessImpactTest.class);
    private static final int SOCKET_TIMEOUT_MS = 2000;
    private static final int CONNECTION_TIMEOUT_MS = 2000;

    // 业务请求级统计
    private final AtomicLong totalReq = new AtomicLong(0);
    private final AtomicLong successReq = new AtomicLong(0);
    private final AtomicLong failedReq = new AtomicLong(0);
    private final AtomicLong consecutiveFailures = new AtomicLong(0);

    // 业务影响窗口（基于业务请求成功/失败判定，非底层连接）
    private volatile long impactStartMs = 0;   // 第一笔失败业务请求时间
    private volatile long impactEndMs = 0;     // 恢复后第一笔成功业务请求时间
    private volatile boolean inImpact = false;

    // 异常类型分布
    private final ConcurrentHashMap<String, AtomicLong> errorTypes = new ConcurrentHashMap<>();

    // 端到端业务延迟（整笔事务 4 个命令的总耗时），分 failover 前 / 后两段统计基线
    private final Histogram latencyAll = new Histogram(60_000_000L, 3);     // max 60s
    private final Histogram latencyBefore = new Histogram(60_000_000L, 3);  // 影响开始前
    private final Histogram latencyAfter = new Histogram(60_000_000L, 3);   // 恢复之后

    private final String host;
    private final int port;
    private final JedisPoolConfig poolConfig;
    private final String configName;

    public BusinessImpactTest(String host, int port, JedisPoolConfig poolConfig, String configName) {
        this.host = host;
        this.port = port;
        this.poolConfig = poolConfig;
        this.configName = configName;
    }

    public void run(int durationSeconds, int qps) {
        log.info("========================================");
        log.info("Business Impact Test (failover -> business request downtime)");
        log.info("========================================");
        log.info("Endpoint: {}:{}", host, port);
        log.info("Config: {}", configName);
        log.info("Duration: {}s | Target QPS: {} (each request = 4 redis cmds)", durationSeconds, qps);
        log.info("Socket Timeout: {}ms | Connection Timeout: {}ms", SOCKET_TIMEOUT_MS, CONNECTION_TIMEOUT_MS);
        log.info("Pool: maxTotal={}, maxIdle={}, minIdle={}, maxWait={}ms",
                poolConfig.getMaxTotal(), poolConfig.getMaxIdle(),
                poolConfig.getMinIdle(), poolConfig.getMaxWaitMillis());
        log.info("========================================");

        JedisPool pool = new JedisPool(poolConfig, host, port,
                CONNECTION_TIMEOUT_MS, SOCKET_TIMEOUT_MS, null, 0, null);

        warmUp(pool);

        long intervalMs = qps > 0 ? (1000L / qps) : 0;
        long startTime = System.currentTimeMillis();
        long endTime = startTime + (durationSeconds * 1000L);

        log.info("Test started at: {}", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        log.info("Trigger ElastiCache failover in AWS console now. Press Ctrl+C to stop early.");
        log.info("----------------------------------------");

        long reqId = 0;
        while (System.currentTimeMillis() < endTime) {
            long opStart = System.nanoTime();
            String error = doBusinessRequest(pool, reqId++);
            long opDurationUs = (System.nanoTime() - opStart) / 1000;
            boolean success = (error == null);

            totalReq.incrementAndGet();
            long now = System.currentTimeMillis();

            if (success) {
                successReq.incrementAndGet();
                long capped = Math.min(opDurationUs, 60_000_000L);
                latencyAll.recordValue(capped);
                if (!inImpact && impactStartMs == 0) {
                    latencyBefore.recordValue(capped); // 还没进过影响期 -> 基线
                } else if (impactEndMs > 0) {
                    latencyAfter.recordValue(capped);  // 已恢复 -> 恢复后延迟
                }

                if (consecutiveFailures.get() > 0 && inImpact) {
                    // 业务从失败中恢复
                    impactEndMs = now;
                    inImpact = false;
                    long window = impactEndMs - impactStartMs;
                    log.warn(">>> BUSINESS RECOVERED! Impact window: {}ms ({} consecutive failed requests before recovery)",
                            window, consecutiveFailures.get());
                }
                consecutiveFailures.set(0);
            } else {
                failedReq.incrementAndGet();
                errorTypes.computeIfAbsent(error, k -> new AtomicLong(0)).incrementAndGet();
                long failures = consecutiveFailures.incrementAndGet();

                if (failures == 1 && !inImpact) {
                    inImpact = true;
                    // 仅记录"首次"业务影响开始（多次 failover 取第一次窗口；后续在报告中说明）
                    if (impactStartMs == 0) {
                        impactStartMs = now;
                    }
                    log.error(">>> BUSINESS IMPACT STARTED at {} (request #{} failed: {})",
                            LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), reqId, error);
                }

                if (failures % 10 == 0) {
                    log.error("    ... {} consecutive failed business requests ({}ms since impact start, last error: {})",
                            failures, now - impactStartMs, error);
                }
            }

            long sleepMs = intervalMs - (opDurationUs / 1000);
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        pool.close();
        printReport(durationSeconds);
    }

    /**
     * 执行一笔业务事务（4 个 Redis 命令的读写组合）。
     * @return null 表示成功；否则返回异常类型简称（用于分布统计）
     */
    private String doBusinessRequest(JedisPool pool, long reqId) {
        try (Jedis jedis = pool.getResource()) {
            String sessionKey = "session:" + (reqId % 1000);
            String counterKey = "counter:" + (reqId % 1000);
            long ts = System.currentTimeMillis();

            // 1. 读会话
            jedis.get(sessionKey);
            // 2. 更新会话（带 TTL）
            jedis.setex(sessionKey, 300, "u" + reqId + "@" + ts);
            // 3. 业务计数器自增
            long c = jedis.incr(counterKey);
            // 4. 回读校验
            String back = jedis.get(counterKey);
            if (back == null || Long.parseLong(back) != c) {
                return "ConsistencyError"; // 写后读不一致（failover 期间可能短暂出现）
            }
            return null;
        } catch (JedisConnectionException e) {
            return "JedisConnectionException";
        } catch (JedisException e) {
            return "JedisException";
        } catch (NumberFormatException e) {
            return "ConsistencyError";
        } catch (Exception e) {
            return e.getClass().getSimpleName();
        }
    }

    private void warmUp(JedisPool pool) {
        log.info("Warming up connection pool...");
        int warmUpCount = Math.max(Math.min(poolConfig.getMinIdle(), poolConfig.getMaxTotal()), 3);
        int ok = 0;
        for (int i = 0; i < warmUpCount; i++) {
            try (Jedis jedis = pool.getResource()) {
                jedis.ping();
                ok++;
            } catch (Exception e) {
                log.warn("Warm-up connection {} failed: {}", i, e.getMessage());
            }
        }
        log.info("Warm-up complete: {}/{} connections established", ok, warmUpCount);
    }

    private void printReport(int durationSeconds) {
        log.info("");
        log.info("========================================");
        log.info("BUSINESS IMPACT REPORT");
        log.info("========================================");
        log.info("Config: {}", configName);
        log.info("Duration: {}s", durationSeconds);
        log.info("----------------------------------------");
        log.info("Total business requests: {}", totalReq.get());
        log.info("Successful:              {} ({}%)", successReq.get(), pct(successReq.get(), totalReq.get()));
        log.info("Failed:                  {} ({}%)", failedReq.get(), pct(failedReq.get(), totalReq.get()));
        log.info("----------------------------------------");

        if (impactStartMs > 0) {
            log.info(">>> BUSINESS IMPACT (business-request view) <<<");
            log.info("  Impact started:  {} (epoch: {}ms)", Instant.ofEpochMilli(impactStartMs), impactStartMs);
            if (impactEndMs > 0) {
                long window = impactEndMs - impactStartMs;
                log.info("  Recovered:       {} (epoch: {}ms)", Instant.ofEpochMilli(impactEndMs), impactEndMs);
                log.info("  >>> BUSINESS IMPACT WINDOW: {}ms ({}s) <<<", window, String.format("%.1f", window / 1000.0));
            } else {
                log.info("  >>> BUSINESS NOT RECOVERED during test window <<<");
            }
            log.info("  Failed requests during impact: {}", failedReq.get());
        } else {
            log.info("No business impact detected during test (no failover, or impact too short to catch).");
        }

        log.info("----------------------------------------");
        if (!errorTypes.isEmpty()) {
            log.info("ERROR TYPE DISTRIBUTION:");
            errorTypes.forEach((type, count) -> log.info("  {}: {}", type, count.get()));
            log.info("----------------------------------------");
        }

        log.info("BUSINESS REQUEST LATENCY (end-to-end, 4 cmds, microseconds):");
        log.info("  overall  p50/p95/p99/max: {}/{}/{}/{}",
                latencyAll.getValueAtPercentile(50), latencyAll.getValueAtPercentile(95),
                latencyAll.getValueAtPercentile(99), latencyAll.getMaxValue());
        if (latencyBefore.getTotalCount() > 0) {
            log.info("  BEFORE   p50/p99: {}/{}us  (baseline)",
                    latencyBefore.getValueAtPercentile(50), latencyBefore.getValueAtPercentile(99));
        }
        if (latencyAfter.getTotalCount() > 0) {
            log.info("  AFTER    p50/p99: {}/{}us  (post-recovery — check if back to baseline)",
                    latencyAfter.getValueAtPercentile(50), latencyAfter.getValueAtPercentile(99));
        }
        log.info("========================================");
        log.info("NOTE: 'business impact window' = duration business requests were failing,");
        log.info("      which may differ from raw connection downtime in FailoverTuningTest.");
        log.info("========================================");
    }

    private static String pct(long n, long total) {
        return String.format("%.2f", total > 0 ? (n * 100.0 / total) : 0.0);
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java -cp target/jedis-failover-tuning-1.0.0.jar com.aws.redis.BusinessImpactTest \\");
            System.err.println("            <primary-endpoint> [port] [config] [duration-sec] [qps]");
            System.err.println();
            System.err.println("  config options: default | conservative | aggressive | ultra (default: aggressive)");
            System.err.println("  duration-sec:   test duration in seconds (default: 300)");
            System.err.println("  qps:            target business requests per second (default: 50)");
            System.err.println();
            System.err.println("Example:");
            System.err.println("  java -Dsun.net.inetaddr.ttl=5 -cp target/jedis-failover-tuning-1.0.0.jar \\");
            System.err.println("       com.aws.redis.BusinessImpactTest my-cluster.xxxxx.ng.0001.use1.cache.amazonaws.com 6379 aggressive 300 50");
            System.exit(1);
        }

        String host = args[0];
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 6379;
        String configName = args.length > 2 ? args[2] : "aggressive";
        int duration = args.length > 3 ? Integer.parseInt(args[3]) : 300;
        int qps = args.length > 4 ? Integer.parseInt(args[4]) : 50;

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

        BusinessImpactTest test = new BusinessImpactTest(host, port, poolConfig, configName);
        test.run(duration, qps);
    }
}
