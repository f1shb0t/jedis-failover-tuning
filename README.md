# Jedis ElastiCache Failover Tuning Kit

针对 **AWS ElastiCache Redis 7.1 (Cluster Mode Disabled)** + **Jedis 4.4.8** 的 failover 恢复调优测试工具。

---

## 目录

- [背景与原理](#背景与原理)
- [Quick Start](#quick-start)
- [测试步骤](#测试步骤)
- [配置方案对比](#配置方案对比)
- [调优建议](#调优建议)
- [FAQ](#faq)

---

## 背景与原理

### ElastiCache Failover 机制（Cluster Mode Disabled）

```
                  Primary Endpoint (DNS)
                         │
              ┌──────────┼──────────┐
              │          │          │
         ┌────▼────┐  ┌──▼───┐  ┌──▼───┐
         │ Primary  │  │Replica│  │Replica│
         │ (Write)  │  │(Read) │  │(Read) │
         └────┬────┘  └───────┘  └───────┘
              │
         [Failover!]
              │
              ▼
         Replica 提升为新 Primary
         DNS 指向新 Primary IP
         旧连接全部失效
```

**Failover 过程中对客户端的影响：**
1. **网络层**：旧 TCP 连接断开（RST / timeout）
2. **DNS 层**：Primary Endpoint DNS 记录更新到新节点 IP
3. **连接池层**：池中缓存的连接全部失效
4. **JVM 层**：如果 JVM 缓存了旧 DNS，即使连接池创建新连接也连到旧 IP

### 影响 RTO 的关键因素

| 因素 | 影响 | 对策 |
|------|------|------|
| JVM DNS 缓存 | 永久缓存 = 永远连旧 IP | `-Dsun.net.inetaddr.ttl=5` |
| Socket timeout | 过长 = 等 TCP 超时才发现断连 | 设 2000ms |
| 连接池死连接 | 不检测 = 每次借到坏连接 | testOnBorrow 或高频驱逐 |
| maxWait 过长 | 线程长时间阻塞等连接 | 1-2s 上限 |
| 无重试逻辑 | 单次失败直接报错 | 业务层加重试 |

---

## Quick Start

### 前置条件

- JDK 11+（安装命令见 [Step 1](#step-1-准备环境)）
- Maven 3.6+
- 能访问 ElastiCache Primary Endpoint 的 EC2 实例（同 VPC）

### 编译

```bash
cd jedis-failover-tuning
mvn clean package -DskipTests
```

### 单次测试

```bash
# 推荐：设置 DNS TTL + aggressive 配置
java -Dsun.net.inetaddr.ttl=5 \
  -jar target/jedis-failover-tuning-1.0.0.jar \
  my-cluster.xxxxx.ng.0001.use1.cache.amazonaws.com \
  6379 \
  aggressive \
  300 \
  100
```

参数说明：
- `endpoint`: ElastiCache Primary Endpoint
- `port`: 默认 6379
- `config`: `default` | `conservative` | `aggressive` | `ultra`
- `duration`: 测试时长秒数（默认 300）
- `ops-per-sec`: 每秒操作数（默认 100）

---

## 测试步骤

### Step 1: 准备环境

1. 确认 EC2 实例和 ElastiCache 在同一 VPC，Security Group 放行 6379

2. SSH 到 EC2 实例，安装 **Java 11** 和 Maven（按操作系统选择）：

   **Amazon Linux 2023（推荐用 Corretto 11）**
   ```bash
   sudo dnf install -y java-11-amazon-corretto-devel maven
   ```

   **Amazon Linux 2**
   ```bash
   # Corretto 11
   sudo amazon-linux-extras install -y java-openjdk11 || \
     sudo yum install -y java-11-amazon-corretto-devel
   sudo yum install -y maven
   # 若仓库无 maven，可手动装：
   # sudo wget https://dlcdn.apache.org/maven/maven-3/3.9.6/binaries/apache-maven-3.9.6-bin.tar.gz -P /opt && \
   #   sudo tar xzf /opt/apache-maven-3.9.6-bin.tar.gz -C /opt && \
   #   echo 'export PATH=/opt/apache-maven-3.9.6/bin:$PATH' | sudo tee /etc/profile.d/maven.sh && source /etc/profile.d/maven.sh
   ```

   **Ubuntu / Debian**
   ```bash
   sudo apt-get update
   sudo apt-get install -y openjdk-11-jdk maven
   ```

   **RHEL / CentOS / Rocky / AlmaLinux**
   ```bash
   # OpenJDK 11
   sudo yum install -y java-11-openjdk-devel maven
   # 或使用 Amazon Corretto 11：
   # sudo rpm --import https://yum.corretto.aws/corretto.key
   # sudo curl -Lo /etc/yum.repos.d/corretto.repo https://yum.corretto.aws/corretto.repo
   # sudo yum install -y java-11-amazon-corretto-devel maven
   ```

3. 验证安装（`java -version` 应显示 `11.x`）：
   ```bash
   java -version    # 期望输出含 "11.0.x"（openjdk 或 Corretto-11 均可）
   mvn -version     # 期望 Maven 3.6+，且 "Java version: 11.x"
   ```

   > ⚠️ 如果机器上装了多个 JDK，确保默认指向 11：
   > - Amazon Linux / RHEL：`sudo alternatives --config java`
   > - Ubuntu/Debian：`sudo update-alternatives --config java`
   > 或显式设置 `export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))`

4. 克隆本项目并编译：
   ```bash
   git clone https://github.com/f1shb0t/jedis-failover-tuning.git
   cd jedis-failover-tuning
   mvn clean package -DskipTests
   ```

### Step 2: 基准测试（无 failover）

先跑 60s 确认连通性和正常延迟：

```bash
java -Dsun.net.inetaddr.ttl=5 -jar target/jedis-failover-tuning-1.0.0.jar \
  <primary-endpoint> 6379 aggressive 60 100
```

✅ 预期结果：0 errors，p99 < 2ms

### Step 3: DNS 影响测试

验证 JVM DNS TTL 是否生效：

```bash
# 不加 DNS TTL（对比）
java -cp target/jedis-failover-tuning-1.0.0.jar com.aws.redis.DnsCacheTest <primary-endpoint>

# 加 DNS TTL = 5s
java -Dsun.net.inetaddr.ttl=5 -cp target/jedis-failover-tuning-1.0.0.jar com.aws.redis.DnsCacheTest <primary-endpoint>
```

在运行过程中去 AWS Console 触发 failover，观察 DNS 变化时间。

### Step 3.5: 业务读写影响时间测试（BusinessImpactTest）

`FailoverTuningTest`（主程序）站在**底层连接**视角统计单次 GET/SET 的中断。
`BusinessImpactTest` 则站在**真实业务代码**视角：每笔「业务请求」由一组读写组合（事务）构成——
`GET session` → `SET session` → `INCR counter` → `GET counter` 回读校验，
任意一步失败即整笔业务请求失败。它测量的是 failover 期间**业务侧真正不可用的时间窗口**。

```bash
# 业务视角 failover 影响测试（推荐配 DNS TTL）
java -Dsun.net.inetaddr.ttl=5 -cp target/jedis-failover-tuning-1.0.0.jar \
  com.aws.redis.BusinessImpactTest <primary-endpoint> 6379 aggressive 300 50
```

参数说明：
- `endpoint` / `port`：同主程序
- `config`：`default` | `conservative` | `aggressive` | `ultra`
- `duration`：测试时长秒数（默认 300）
- `qps`：每秒**业务请求**数（默认 50，每笔含 4 个 Redis 命令）

运行中去 AWS Console 触发 failover。输出报告重点指标：
- **BUSINESS IMPACT WINDOW**：业务请求持续失败的时长（ms）—— 这是业务侧真正感知到的"宕机时间"
- **Failed requests during impact**：受影响期间失败的业务请求笔数
- **ERROR TYPE DISTRIBUTION**：异常类型分布（连接异常 / 一致性错误等）
- **BEFORE vs AFTER 延迟基线**：failover 前后业务延迟 p50/p99 对比，判断恢复后性能是否回到基线

> 💡 与 `FailoverTuningTest` 的 `TOTAL DOWNTIME` 对比看：业务影响窗口通常 ≥ 底层连接 downtime，
> 因为业务事务多步、任一步失败即算失败，更贴近用户真实感受。

### Step 4: 多配置对比测试

```bash
chmod +x run-all-configs.sh
./run-all-configs.sh <primary-endpoint> 6379
```

脚本会依次用 4 种配置运行测试。**每轮测试开始后**，去 AWS Console 触发 failover：
- ElastiCache → Replication Groups → 选择你的集群
- Actions → **Test Failover** → 选择 Primary 所在的 AZ → Confirm

### Step 5: 分析结果

对比 `result_*.log` 中的关键指标：

```
>>> TOTAL DOWNTIME: xxxms
Failed: xxx (x.xx%)
p99: xxxus
```

---

## 配置方案对比

| 配置 | testOnBorrow | 驱逐频率 | maxWait | 特点 | 预期 RTO |
|------|-------------|----------|---------|------|----------|
| `default` | ❌ | 30s/轮 | 无限等 | Jedis 默认，作为对比基准 | 30-60s |
| `conservative` | ✅ | 15s/轮 | 2s | 安全但每次借连接多 1 RTT | 10-20s |
| `aggressive` | ❌ | 5s/轮 | 1s | 推荐平衡方案 | 5-15s |
| `ultra` | ✅ | 3s/轮 | 500ms | 极端场景，连接开销大 | 3-8s |

> **注意**：实际 RTO 还取决于 ElastiCache 自身的 failover 完成时间（通常 15-30s），
> 上面的 RTO 指的是「ElastiCache failover 完成后，客户端恢复正常操作的时间」。

---

## 调优建议

### 🔴 必须做（不做 failover 可能永远恢复不了）

#### 1. 设置 JVM DNS TTL

```bash
# JVM 启动参数（推荐）
java -Dsun.net.inetaddr.ttl=5 -jar your-app.jar

# 或在代码中设置
java.security.Security.setProperty("networkaddress.cache.ttl", "5");
```

**原理**：ElastiCache failover 后 Primary Endpoint DNS 指向新 IP。JVM 默认永久缓存 DNS，
不设 TTL 意味着 JVM 永远解析到旧 IP，连接池创建的新连接也是连旧节点。

#### 2. 设置合理的 Socket Timeout

```java
// Jedis 4.x
JedisPool pool = new JedisPool(config, host, port,
    2000,  // connectionTimeout (ms) - TCP 握手超时
    2000,  // soTimeout (ms) - 读写超时
    null,  // password
    0,     // database
    null); // clientName
```

### 🟡 强烈建议

#### 3. 连接池驱逐策略

```java
config.setTestWhileIdle(true);                   // 空闲时检测
config.setTimeBetweenEvictionRunsMillis(5000);   // 5s 跑一轮
config.setMinEvictableIdleTimeMillis(30000);     // 30s 未用即驱逐
config.setNumTestsPerEvictionRun(-1);            // -1 = 检查全部
```

#### 4. 快速失败 + 业务层重试

```java
config.setMaxWaitMillis(1000); // 1s 拿不到连接就抛异常

// 业务层重试（示例）
public String getWithRetry(String key, int maxRetries) {
    for (int i = 0; i < maxRetries; i++) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.get(key);
        } catch (JedisConnectionException e) {
            if (i == maxRetries - 1) throw e;
            try { Thread.sleep(100 * (i + 1)); } catch (InterruptedException ie) { break; }
        }
    }
    return null;
}
```

#### 5. testOnBorrow vs 高频驱逐

| 方案 | 优点 | 缺点 |
|------|------|------|
| `testOnBorrow=true` | 绝不会拿到坏连接 | 每次请求多一次 PING（+0.2-0.5ms） |
| `testOnBorrow=false` + 高频驱逐 | 正常路径零开销 | failover 后前几个请求可能失败 |

**推荐**：对延迟敏感的服务用方案 B（高频驱逐）；对可靠性要求极高的用方案 A（testOnBorrow）。

### 🟢 锦上添花

#### 6. 连接池预热

启动时主动建立 minIdle 个连接，避免冷启动时的连接风暴：

```java
// 预热示例
for (int i = 0; i < config.getMinIdle(); i++) {
    try (Jedis jedis = pool.getResource()) { jedis.ping(); }
}
```

#### 7. 监控指标

建议在应用中暴露以下 metrics（Prometheus/CloudWatch）：

- `jedis_pool_active` - 当前活跃连接数
- `jedis_pool_idle` - 空闲连接数
- `jedis_connection_errors_total` - 连接错误计数
- `jedis_command_duration_seconds` - 命令延迟分布

---

## FAQ

### Q: 为什么不用 JedisSentinelPool？

ElastiCache Cluster Mode Disabled **不支持 Redis Sentinel 协议**。它有自己的 failover 机制（基于 DNS 切换），
不暴露 Sentinel 端口。所以只能用 `JedisPool` 连 Primary Endpoint。

### Q: Jedis 4.x 的 JedisPooled 和 JedisPool 有什么区别？

`JedisPooled`（Jedis 4.0+）是 `JedisPool` 的新封装，底层共用同一个连接池。
failover 行为一致，选哪个都行。本项目用 `JedisPool` 因为 API 更直观易调试。

### Q: 为什么不建议用 Lettuce 代替 Jedis？

Lettuce 基于 Netty 异步模型，有自动重连机制（`reconnect=true`），failover 恢复通常更快。
但如果你的项目已经用了 Jedis 且改动成本高，通过本文档的调优可以达到接近的效果。
如果是新项目，建议评估 Lettuce 或 AWS 官方的 `amazon-elasticache-client`。

### Q: ElastiCache failover 通常多久完成？

根据 AWS 文档和实测：
- **Multi-AZ 开启**：15-30 秒（自动检测 + 提升 replica）
- **触发时间**：故障检测 ~5s + DNS propagation ~5-10s + 连接重建

你的客户端 RTO = AWS failover 时间 + 连接池恢复时间 + DNS 缓存刷新时间。

### Q: 我的测试结果 downtime 很长（>60s），怎么排查？

1. 检查是否设了 `-Dsun.net.inetaddr.ttl=5`
2. 用 `DnsCacheTest` 确认 DNS 确实在更新
3. 检查 socket timeout 是否设置（默认无限等待）
4. 检查连接池 maxWait 是否太长

---

## 项目结构

```
jedis-failover-tuning/
├── pom.xml                          # Maven 配置（Jedis 4.4.8）
├── README.md                        # 本文档
├── run-all-configs.sh              # 批量测试脚本
└── src/main/java/com/aws/redis/
    ├── FailoverTuningTest.java     # 主测试程序（底层连接视角）
    ├── BusinessImpactTest.java     # 业务读写影响时间测试（业务请求视角）
    ├── PoolConfig.java             # 4 种连接池配置方案
    └── DnsCacheTest.java           # DNS 缓存影响测试
```

---

## License

MIT
