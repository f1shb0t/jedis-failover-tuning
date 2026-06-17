#!/bin/bash
# =============================================================================
# run-all-configs.sh
# 依次用 4 种配置跑 failover 测试，每轮之间等待用户触发 failover
# =============================================================================

ENDPOINT=${1:?"Usage: ./run-all-configs.sh <primary-endpoint> [port]"}
PORT=${2:-6379}
DURATION=300
OPS=100
JAR="target/jedis-failover-tuning-1.0.0.jar"

# 推荐 DNS TTL 设置
JVM_OPTS="-Dsun.net.inetaddr.ttl=5"

CONFIGS=("default" "conservative" "aggressive" "ultra")

echo "============================================="
echo " ElastiCache Failover Tuning - Batch Runner"
echo "============================================="
echo " Endpoint: ${ENDPOINT}:${PORT}"
echo " Duration per test: ${DURATION}s"
echo " OPS: ${OPS}/s"
echo " JVM Options: ${JVM_OPTS}"
echo "============================================="
echo ""

for CONFIG in "${CONFIGS[@]}"; do
    echo "---------------------------------------------"
    echo " Next: config=[${CONFIG}]"
    echo " Before starting, ensure ElastiCache is stable (not mid-failover)."
    echo "---------------------------------------------"
    read -p "Press ENTER to start [${CONFIG}] test (trigger failover during test)..."
    echo ""

    java ${JVM_OPTS} -jar ${JAR} ${ENDPOINT} ${PORT} ${CONFIG} ${DURATION} ${OPS} \
        2>&1 | tee "result_${CONFIG}_$(date +%Y%m%d_%H%M%S).log"

    echo ""
    echo "[${CONFIG}] test complete. Wait 60s for cluster to stabilize..."
    sleep 60
done

echo ""
echo "============================================="
echo " All tests complete! Compare the result_*.log files."
echo "============================================="
