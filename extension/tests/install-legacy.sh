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
# Test-only historical versions; primary controls keep Rust 3.0 as the default.
root=$(cd "$(dirname "$0")/.." && pwd)
pg_config=${PG_CONFIG:-pg_config}
libdir=$("$pg_config" --pkglibdir)
extdir=$("$pg_config" --sharedir)/extension
controls=$(mktemp -d)
trap 'rm -rf "$controls"' EXIT
for module in external-table fdw; do
    name=pxf
    if [[ $module == fdw ]]; then name=pxf_fdw; fi
    legacy="$root/tests/legacy/$module"
    make -C "$legacy" PG_CONFIG="$pg_config"
    sudo install -m755 "$legacy/$name.so" "$libdir/"
    for sql in "$legacy/$name"--*.sql; do
        sudo install -m644 "$sql" "$extdir/"
        version=$(basename "$sql" .sql)
        version=${version#${name}--}
        if [[ $version != *--* ]]; then
            printf "module_pathname = '\$libdir/%s'\n" "$name" > "$controls/$name--$version.control"
            sudo install -m644 "$controls/$name--$version.control" "$extdir/"
        fi
    done
done
