#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

set -e
# Only the disposable automation container invokes this script.
repo=$(cd "$(dirname "$0")/../.." && pwd)
export JAVA_HOME=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}
export PXF_HOME="$repo/server/build/stage"
export PXF_BASE=${PXF_BASE:-/home/gpadmin/pxf-rust-base}
export PXF_RUST_DATA=${PXF_RUST_DATA:-/tmp/pxf-fixture}
export PXF_JVM_OPTS='-Xmx512m -Xms256m'
make -C "$repo/server" stage-notest
"$PXF_HOME/bin/pxf" prepare
mkdir -p "$PXF_BASE/servers/rust_pxf_live" "$PXF_RUST_DATA"
cat > "$PXF_BASE/servers/rust_pxf_live/pxf-site.xml" <<XML
<configuration><property><name>pxf.fs.basePath</name><value>$PXF_RUST_DATA</value></property></configuration>
XML
cat > "$PXF_BASE/conf/pxf-profiles.xml" <<'XML'
<profiles><profile><name>file:parquet</name><plugins><fragmenter>org.apache.cloudberry.pxf.plugins.hdfs.HdfsDataFragmenter</fragmenter><accessor>org.apache.cloudberry.pxf.plugins.hdfs.ParquetFileAccessor</accessor><resolver>org.apache.cloudberry.pxf.plugins.hdfs.ParquetResolver</resolver></plugins></profile></profiles>
XML
printf '1,one\n2,two\n' > "$PXF_RUST_DATA/read.csv"
"$PXF_HOME/bin/pxf" start
