#!/usr/bin/env bash

# Load settings
root=`cd \`dirname $0\`/..;pwd`
bin=${root}/bin
. ${bin}/gphd-env.sh

if [ "$START_STARGATE" == "true" ]; then
	echo Stopping Stargate...
	${HBASE_BIN}/hbase-daemon.sh --config ${HBASE_CONF} stop rest
fi

echo Stopping HBase standalone...
${HBASE_ROOT}/bin/stop-hbase.sh
