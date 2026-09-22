#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under the Apache
# License, Version 2.0. See http://www.apache.org/licenses/LICENSE-2.0
set -eo pipefail

bin=$(cd "$(dirname "$0")" && pwd)
. "${bin}/gphd-env.sh"

# Keep the copied singlecluster configuration byte-for-byte intact. Container-only
# overrides are applied to the installed files when the image starts.
sed -i 's#hdfs://0\.0\.0\.0:8020#hdfs://singlecluster:8020#' \
    "${HADOOP_CONF}/core-site.xml"
sed -i '/<name>dfs.replication<\/name>/{n;s/<value>3<\/value>/<value>1<\/value>/;}' \
    "${HADOOP_CONF}/hdfs-site.xml"
sed -i '/<name>hive.compactor.initiator.on<\/name>/{n;s/<value>true<\/value>/<value>false<\/value>/;}' \
    "${HIVE_CONF}/hive-site.xml"
sed -i '/<name>hive.compactor.worker.threads<\/name>/{n;s/<value>1<\/value>/<value>0<\/value>/;}' \
    "${HIVE_CONF}/hive-site.xml"
sed -i '/<name>hive.support.concurrency<\/name>/{n;s/<value>true<\/value>/<value>false<\/value>/;}' \
    "${HIVE_CONF}/hive-site.xml"
sed -i '/<name>hive.txn.manager<\/name>/{n;s#<value>org.apache.hadoop.hive.ql.lockmgr.DbTxnManager</value>#<value>org.apache.hadoop.hive.ql.lockmgr.DummyTxnManager</value>#;}' \
    "${HIVE_CONF}/hive-site.xml"
sed -i '/<\/configuration>/i\
    <property>\
        <name>yarn.scheduler.capacity.maximum-am-resource-percent</name>\
        <value>0.5</value>\
    </property>' "${HADOOP_CONF}/capacity-scheduler.xml"
sed -i '/<\/configuration>/i\
    <property>\
        <name>hive.server2.tez.initialize.default.sessions</name>\
        <value>false</value>\
    </property>' "${HIVE_CONF}/hive-site.xml"
sed -i 's#export HIVE_SERVER_OPTS="#export HIVE_SERVER_OPTS="--skiphbasecp #' \
    "${HIVE_CONF}/hive-env.sh"

stop_services() {
    "${HADOOP_BIN}/hdfs" --daemon stop httpfs || true
    "${bin}/stop-hive.sh" || true
    "${bin}/stop-yarn.sh" || true
    "${bin}/stop-hdfs.sh" || true
}
trap stop_services EXIT
trap 'exit 0' TERM INT

if [[ ! -f "${HADOOP_STORAGE_ROOT}/dfs/name/current/VERSION" ]]; then
    "${HADOOP_BIN}/hdfs" namenode -format -nonInteractive
fi

"${bin}/start-hdfs.sh"
"${bin}/start-yarn.sh"
"${bin}/start-hive.sh"
"${HADOOP_BIN}/hdfs" dfs -mkdir -p /tmp /user/gpadmin
"${HADOOP_BIN}/hdfs" dfs -chmod 1777 /tmp
printf 'ready\n' | "${HADOOP_BIN}/hdfs" dfs -put -f - /tmp/testcontainers-healthcheck
"${HADOOP_BIN}/hdfs" dfs -rm /tmp/testcontainers-healthcheck

"${HADOOP_BIN}/hdfs" --daemon start httpfs
for attempt in $(seq 1 60); do
    if curl -fsS 'http://localhost:14000/webhdfs/v1/?op=GETHOMEDIRECTORY&user.name=gpadmin' >/dev/null; then
        break
    fi
    if [[ ${attempt} -eq 60 ]]; then
        echo 'HttpFS did not become ready' >&2
        exit 1
    fi
    sleep 1
done

for attempt in $(seq 1 120); do
    if (echo >/dev/tcp/localhost/10000) >/dev/null 2>&1; then
        break
    fi
    if [[ ${attempt} -eq 120 ]]; then
        echo 'HiveServer2 port did not become ready' >&2
        cat "${LOGS_ROOT}"/hive-gpadmin-hiveserver2-*.out >&2 || true
        exit 1
    fi
    sleep 1
done

# Exercise Hive through Tez/YARN rather than treating an open socket as ready.
timeout 180 "${HIVE_BIN}/beeline" -u 'jdbc:hive2://localhost:10000/default;auth=noSasl' -n gpadmin -e '
DROP TABLE IF EXISTS testcontainers_source;
DROP TABLE IF EXISTS testcontainers_ctas;
CREATE TABLE testcontainers_source (id INT);
INSERT INTO testcontainers_source VALUES (1);
CREATE TABLE testcontainers_ctas AS SELECT id FROM testcontainers_source;
SELECT assert_true(count(*) = 1) FROM testcontainers_ctas;
DROP TABLE testcontainers_ctas;
DROP TABLE testcontainers_source;'

echo 'SingleCluster is ready: HDFS, HttpFS, YARN, Tez and Hive'
while true; do
    "${HADOOP_BIN}/hdfs" dfsadmin -safemode get >/dev/null
    curl -fsS 'http://localhost:14000/webhdfs/v1/?op=GETHOMEDIRECTORY&user.name=gpadmin' >/dev/null
    kill -0 "$(cat "${PIDS_ROOT}/hive-gpadmin-hiveserver2.pid")"
    sleep 5 &
    wait $!
done
