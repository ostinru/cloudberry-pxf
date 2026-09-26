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

set -euo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd)
base_image=${PXF_RUST_BASE_IMAGE:-pxf/cbdb-testcontainer-ubuntu:2}
image=${PXF_RUST_BUILD_IMAGE:-pxf/rust-extension-test:pg14}
container=${PXF_RUST_CONTAINER:-pxf-rust-smoke-$$}
repo=/home/gpadmin/workspace/cloudberry-pxf

if ! docker image inspect "$base_image" >/dev/null 2>&1; then
    echo "Missing automation image: $base_image" >&2
    echo "Build automation/src/main/resources/testcontainers/pxf-cbdb/Dockerfile first." >&2
    exit 1
fi
docker build --build-arg "BASE_IMAGE=$base_image" -t "$image" "$root/extension/tests"
docker run -d --name "$container" --hostname mdw "$image" tail -f /dev/null
# Register cleanup only after creating our own container successfully.
cleanup() {
    if [[ ${PXF_RUST_KEEP_CONTAINER:-0} == 1 ]]; then
        echo "Retained test container: $container"
    else
        docker rm -f "$container" >/dev/null
    fi
}
trap cleanup EXIT
docker exec "$container" mkdir -p "$repo"
COPYFILE_DISABLE=1 tar --no-xattrs -C "$root" --exclude=extension/target --exclude=extension/build \
    -cf - extension api_version version common.mk server | docker cp - "$container:$repo"
docker exec -u root "$container" chown -R gpadmin:gpadmin "$repo"
docker exec "$container" bash "$repo/extension/tests/setup-cloudberry.sh"
docker exec "$container" bash -c '
    set -e
    source /usr/local/cloudberry-db/cloudberry-env.sh
    source /home/gpadmin/workspace/cloudberry/gpAux/gpdemo/gpdemo-env.sh
    cd /home/gpadmin/workspace/cloudberry-pxf/extension
    make test-core check-fmt
    PGRX_PG_CONFIG_PATH=$(command -v pg_config) CARGO_TARGET_DIR=target/pg14 cargo clippy --locked --workspace --all-targets --no-default-features --features pg14 -- -D warnings -A deprecated
    # Separate builds deliberately exercise separate extension artifacts.
    make stage-external-table PG_CONFIG=/usr/local/cloudberry-db/bin/pg_config
    make stage-fdw PG_CONFIG=/usr/local/cloudberry-db/bin/pg_config
    major=$(pg_config --version | sed -E "s/.* ([0-9]+)\..*/\1/")
    for module in external-table fdw; do
        sudo install -m 755 build/pg${major}/$module/*_rust.so "$(pg_config --pkglibdir)/"
        sudo install -m 644 build/pg${major}/$module/*.control build/pg${major}/$module/*.sql "$(pg_config --sharedir)/extension/"
    done
    make smoke
    bash tests/prepare-pxf.sh
    make integration regress-fdw regress-external-table
    bash tests/upgrade.sh
    python3 tests/benchmark.py
'
