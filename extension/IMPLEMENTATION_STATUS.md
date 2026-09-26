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

# Rust migration validation

Worktree: `rust`. SQL versions: `pxf` 3.0 and `pxf_fdw` 3.0.
Fork: `cloudberry-contrib/pgrx`, pinned in Cargo.toml and Cargo.lock.

| Stage | Result |
| --- | --- |
| 1. Workspace and independent extensions | Implemented; both release libraries load on coordinator and all three primary segments. |
| 2. Compatibility inventory | Recorded in COMPATIBILITY.md; all seven original SQL regression cases pass. |
| 3. Shared URI, metadata, predicates and HTTP | Implemented; 32 unit/HTTP tests pass, including Arrow IPC GET/POST as a dev-only test. |
| 4. PostgreSQL adapter | Implemented; memory-context cleanup, cancellation and COPY error contexts tested. Small C shim bridges opaque Cloudberry structures. |
| 5. FDW | SELECT, rescans, projection, conservative predicates, INSERT/COPY, reject limits and error logging pass live tests. |
| 6. External tables and formatters | Protocol, GPDBWritable v1/v2, multibyte delimited records and activity APIs implemented. CSV/Parquet cross-adapter roundtrips and 20,000 binary rows pass. |
| 7. SQL installation and upgrade | Fresh installation and C 2.2/2.0 → Rust 3.0 pass, preserving function/table identity, dependencies and ACLs. |
| 8. Integration and transport | Live Java PXF, HTTP errors/hints, cancellation/recovery, LIMIT, aborted writes, LATIN1, repeated headers and smoke benchmark pass. Full automation.sh passes from a fresh image/cluster. All 40 selected Java Testcontainers tests pass: 16 installation/upgrade, 17 S3 external-table and seven S3 FDW. |
| 9. Build and packaging | Production C code moved to frozen test fixtures; root stage, tar installer, DESTDIR and real DEB installation/registration pass. CI updated; Apache RAT passes. |

All nine implementation stages are complete for the validated target.
Validated platform: Cloudberry 2.1 / PostgreSQL 14.7, Linux aarch64.
PG16 is configured in the existing CI build matrix but is not locally qualified.
Cloudberry rejects SQL_ASCII databases; raw bytes are tested in the shared core.
Arrow IPC is transport-tested; a production Arrow format remains separate work.

The isolated `pxf-rust-migration-dev` container holds the development cluster.
Java Testcontainers create their own disposable clusters. No host cluster changed.

Additional checks: root `make stage` passed 72 CLI specs and 1,808 server tests
(1,806 passed, two pre-existing skips). DEB-installed extensions load on the
coordinator and all primary segments and read through Java PXF. Rust `fmt`,
workspace `clippy` and Apache RAT pass. CI workflow syntax/dependencies checked;
remote GitHub Actions and RPM installation have not been run locally.

The S3 suite exposed a legacy-diagnostic mismatch. The adapter now preserves
SQLSTATE 08000 and the original message prefix, with HTTP status / Java trace
at LOG verbosity. The extended live integration test and clippy pass after
that fix. The kernel-upgrade helper guard was tested against a Rust 3.0 database:
function OIDs, library names and pgrx symbols remain unchanged.

Final Java runs use the Rust-enabled automation image `:2`. For the last
external-table S3 rerun, a local derivative with the same pinned Cargo source
cache (`pxf/cbdb-testcontainer-rust-cached:2`) avoided a GitHub TLS interruption;
all extension binaries were rebuilt from the current source. MinIO used the
locally cached Docker Hub image of the same release because quay.io returned 401.
No test failures or skips remain in the 40 selected Java tests.

Both the direct installer and tar installer atomically replace extension
libraries, including copies bundled under PXF_HOME. A hard-link test confirms
that old mapped library inodes retain their original contents during upgrade.
