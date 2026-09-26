<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements. See the NOTICE file
distributed with this work for additional information
regarding copyright ownership. The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied. See the License for the
specific language governing permissions and limitations
under the License.
-->

# Rust PXF extensions

`pxf` (external tables) and `pxf_fdw` version 3.0 use the
[cloudberry-contrib/pgrx fork](https://github.com/cloudberry-contrib/pgrx), pinned to
`5077608658eb038aa0ebed9ec780b0f3f83233e7`. Both build independently and statically
link the shared Rust crates. Neither extension depends on installing the other.

## Layout

- `crates/pxf-core`: byte-oriented URI/options, metadata/headers, filter wire format,
  GPDBWritable v1/v2, multibyte delimited records and streaming HTTP.
- `crates/pxf-pg`: shared PostgreSQL adapter, COPY conversion, memory ownership,
  predicate/projection extraction and callbacks. `cloudberry.c` bridges opaque
  Cloudberry structures/macros and COPY error reporting missing from pgrx bindings.
- `external-table`: protocol, formatters, activity functions and SQL migrations.
- `fdw`: FDW handler, validator and SQL migrations. See [FDW usage](fdw/PXF_FDW.md).
- `tests`: core-independent database tests and Java-PXF roundtrips. `tests/legacy`
  contains frozen C sources used only for historical-version and upgrade tests.

The root `external-table/` and `fdw/` Makefiles forward to this workspace.
Production builds and packages use `pxf_rust.so` and `pxf_fdw_rust.so`.

## Build and install

Use Rust 1.85.1 (pinned in `rust-toolchain.toml`), a C compiler, libclang, Python 3,
and the **target Cloudberry server headers**. Stock PostgreSQL headers are not
ABI-compatible. Cargo-pgrx is unnecessary: SQL files are maintained explicitly.
Normal builds use `Cargo.lock` with `--locked` and default to the release profile.

```sh
make -C extension external-table PG_CONFIG=/usr/local/cloudberry-db/bin/pg_config
make -C extension fdw PG_CONFIG=/usr/local/cloudberry-db/bin/pg_config
make -C extension stage-external-table stage-fdw PG_CONFIG=/usr/local/cloudberry-db/bin/pg_config
make -C extension install-external-table install-fdw PG_CONFIG=/usr/local/cloudberry-db/bin/pg_config
```

Installation requires write access to the target installation; `DESTDIR` supports
package staging. Installed files are replaced atomically, preserving the inode of
libraries already mapped by running backends. `PROFILE=debug` is available.

`pg_config` selects the kernel feature and `target/pg<major>` directory. Exactly
one of `pg14` / `pg16` may be enabled. **Validated target: Cloudberry 2.1, PG14.7,
Linux aarch64. PG16 remains unqualified; its Cargo feature is not a compatibility
claim.** Build against each target installation, including its patched headers.

## Upgrade

Install the new libraries and SQL on the coordinator and every segment host,
then run in every database using PXF:

```sql
ALTER EXTENSION pxf UPDATE TO '3.0';      -- from 2.2
ALTER EXTENSION pxf_fdw UPDATE TO '3.0';  -- from 2.0
```

Update older SQL versions to these predecessors using the old distribution first.
The scripts replace implementation functions in place, preserving OIDs, existing
external/foreign tables, servers, mappings, dependent views and ACLs. The new
library names allow old and new implementations to coexist in one backend during
UPDATE. Retain old C libraries until no old-version database/backend needs them.
There is no automatic downgrade from Rust 3.0. Fresh installations use ordinary
`CREATE EXTENSION pxf` / `CREATE EXTENSION pxf_fdw` independently.

Kernel upgrades require rebuilding and installing both libraries against the
new Cloudberry headers on every host. The legacy `pxf-pre-gpupgrade` and
`pxf-post-gpupgrade` helpers retain Rust 3.x function definitions, rather than
rewriting them to old C entry points. Keep the library paths present in
`pg_proc.probin` available. A cross-kernel upgrade is not covered by the PG14
SQL-version upgrade tests.

## Tests

```sh
make -C extension test-core check-fmt
bash extension/tests/automation.sh
```

Automation uses `pxf/cbdb-testcontainer-ubuntu:2`, creates an isolated container and
Cloudberry demo cluster, builds both release libraries separately, starts Java
PXF, and tests installation on every primary segment, live file profiles,
HTTP failures/cancellation, original C SQL regressions and real C→Rust upgrades.
`PXF_RUST_KEEP_CONTAINER=1` retains only the newly created container for debugging.
No host database is used. Individual `smoke`, `integration`, `regress-fdw`, and
`regress-external-table` targets require a disposable cluster with installed libs;
`tests/prepare-pxf.sh` prepares the local PXF test configuration.

Validation includes 32 Rust unit/HTTP tests, 7 original SQL regression cases,
CSV and Parquet roundtrips in both adapters, cross-adapter reads, 20,000 streamed
binary rows, dropped columns, local/remote predicates, rescan, INSERT/COPY,
reject limits/error logs, error hints, cancellation, early LIMIT, aborted writes,
activity fanout/ACLs and LATIN1 conversion. Cloudberry forbids SQL_ASCII database
creation; arbitrary SQL_ASCII bytes are covered by core/header tests instead.

The Java Testcontainers image and harness in `automation/` copy this workspace
and install Rust by default. Historical SQL-version tests install the frozen C
fixtures separately; additional tests explicitly cover Rust 3.0 installation and
upgrade. The selected Java runs pass 16 installation/upgrade tests, 17 S3 tests
through external tables and seven S3 access/write tests through FDW.
Run `make -C automation test-tc TC_GROUP=pxf-extension,pxf-fdw-extension`
with `PXF_HOME` pointing to the staged server. On Colima use
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.

The local release smoke benchmark uses 100,000 CSV rows, three measured scans
after warmup and the same Java PXF. One run measured Rust 0.169 s / C 0.149 s;
the fresh automation run alongside other builds measured 0.316 s / 0.232 s.
These medians include psql startup and shared-host load. They establish a
repeatable comparison, not performance parity or production throughput.

## Transport and compatibility

Both adapters use reqwest/rustls with a Tokio `current_thread` runtime, bounded
streaming, cooperative cancellation and explicit upload completion. Memory-context
destructors abort unfinished requests on executor errors and early termination.
All PostgreSQL calls remain outside the async runtime.

The wire metadata preserves raw database bytes and repeated projection headers.
All pushed predicates remain local quals; unsupported OR/NOT subtrees and
non-C string collations are kept local. INSERT/COPY are supported; UPDATE/DELETE
are not newly introduced. See [COMPATIBILITY.md](COMPATIBILITY.md).

Arrow IPC is tested over ordinary HTTP streams in both directions. Arrow is a
**dev-only dependency**; enabling it as a PXF format still requires Java-side
encoding/decoding, negotiation, PostgreSQL type mapping and batch memory limits.
See [TRANSPORT.md](TRANSPORT.md). The pgrx fork currently emits one known
module-magic deprecation warning; clippy otherwise runs with warnings denied.
