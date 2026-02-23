#!/usr/bin/env bash

# Load settings
root=`cd \`dirname $0\`/..;pwd`
bin=${root}/bin
. ${bin}/gphd-env.sh

echo Starting HBase standalone...
${HBASE_ROOT}/bin/start-hbase.sh

# Start Stargate
if [ "$START_STARGATE" == "true" ]; then
	echo Starting Stargate...
	${HBASE_BIN}/hbase-daemon.sh --config ${HBASE_CONF} start rest -p ${STARGATE_PORT}
fi
