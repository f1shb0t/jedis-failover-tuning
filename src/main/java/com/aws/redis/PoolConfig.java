package com.aws.redis;

import redis.clients.jedis.JedisPoolConfig;

/**
 * 多组连接池配置方案，用于对比 failover 恢复速度。
 *
 * 关键调优参数说明：
 * - maxTotal: 最大连接数
 * - maxIdle / minIdle: 空闲连接上下限
 * - maxWaitMillis: 借连接等待超时
 * - timeBetweenEvictionRunsMillis: 驱逐线程运行间隔
 * - minEvictableIdleTimeMillis: 连接最小空闲时间后可被驱逐
 * - testOnBorrow / testOnReturn / testWhileIdle: 连接健康检查开关
 */
public class PoolConfig {

    /**
     * 方案 A: Jedis 默认配置 (baseline)
     * 不做任何调优，作为对比基准。
     */
    public static JedisPoolConfig defaultConfig() {
        JedisPoolConfig config = new JedisPoolConfig();
        // Jedis defaults: maxTotal=8, maxIdle=8, minIdle=0, testOnBorrow=false
        return config;
    }

    /**
     * 方案 B: 保守调优
     * - 开启 testOnBorrow 检测死连接（增加 1 RTT 开销）
     * - 缩短 maxWait 避免线程长时间阻塞
     * - 加快驱逐频率清理断开的连接
     */
    public static JedisPoolConfig conservativeConfig() {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(20);
        config.setMaxIdle(10);
        config.setMinIdle(5);
        config.setMaxWaitMillis(2000);

        // 健康检查
        config.setTestOnBorrow(true);    // 每次借连接做 PING（+1 RTT）
        config.setTestOnReturn(false);
        config.setTestWhileIdle(true);   // 空闲时定期检测

        // 驱逐策略
        config.setTimeBetweenEvictionRunsMillis(15000); // 15s 跑一次驱逐
        config.setMinEvictableIdleTimeMillis(60000);    // 60s 未使用则驱逐
        config.setNumTestsPerEvictionRun(3);

        return config;
    }

    /**
     * 方案 C: 激进调优（推荐用于 failover 敏感场景）
     * - testOnBorrow=false，改用短 maxIdle 生命周期 + 高频驱逐
     * - 极短的 maxWait，快速 fail 让业务层重试
     * - minIdle > 0 保持热连接池
     */
    public static JedisPoolConfig aggressiveConfig() {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(30);
        config.setMaxIdle(15);
        config.setMinIdle(5);
        config.setMaxWaitMillis(1000); // 1s 拿不到连接就 fail

        // 不用 testOnBorrow（省 RTT），靠快速驱逐清理坏连接
        config.setTestOnBorrow(false);
        config.setTestOnReturn(true);    // 还连接时检测
        config.setTestWhileIdle(true);

        // 高频驱逐：5s 一轮，30s 未用即踢
        config.setTimeBetweenEvictionRunsMillis(5000);
        config.setMinEvictableIdleTimeMillis(30000);
        config.setNumTestsPerEvictionRun(-1); // -1 = 检查所有空闲连接

        return config;
    }

    /**
     * 方案 D: 超短生命周期（极端 failover 优化）
     * - 连接存活极短，几乎无死连接残留
     * - 代价：更多连接创建开销
     * - 适用于 failover RTO 要求 < 5s 的场景
     */
    public static JedisPoolConfig ultraShortLivedConfig() {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(50);
        config.setMaxIdle(10);
        config.setMinIdle(2);
        config.setMaxWaitMillis(500); // 500ms 超时

        config.setTestOnBorrow(true);
        config.setTestOnReturn(true);
        config.setTestWhileIdle(true);

        // 极端驱逐：3s 一轮，15s 就踢
        config.setTimeBetweenEvictionRunsMillis(3000);
        config.setMinEvictableIdleTimeMillis(15000);
        config.setNumTestsPerEvictionRun(-1);

        return config;
    }
}
