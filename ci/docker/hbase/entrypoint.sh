#!/bin/bash
set -e

${HBASE_HOME}/bin/start-hbase.sh

echo "HBase standalone is up (master + regionserver + embedded ZK)"

# keep container alive
tail -f ${HBASE_HOME}/logs/*.log
