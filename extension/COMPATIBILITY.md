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

# Compatibility contract

Baseline C sources are frozen under `tests/legacy` from commit `12ae4ec8`.

| Area | Implementation and evidence |
| --- | --- |
| API/URI | Shared root API version; last `?`, byte-preserving options, case-insensitive validation, legacy error diagnostics; core + original SQL regressions |
| Headers | Raw-byte percent encoding, schema/table/query/segment identities, types/modifiers and repeated projection indexes; wire tests and HTTP fixture |
| FDW options | Legacy catalog validation and precedence; all 4 original FDW DDL regression cases pass |
| Distribution | Coordinator planning, native segment dispatch; serializable PostgreSQL plan nodes, no Rust pointers in dispatched plans |
| Predicates/projection | Supported comparison/boolean/IN/null predicates and dense live-column indexes; local quals always retained; dropped-column and OR/local fallback tests |
| FDW reads/writes | Native COPY type conversion, rescan, INSERT/COPY, reject limits/logs and PXF error-marker reporting |
| External protocol | Initialization, partial reads/writes, EOF/final-call handling; all 3 original external SQL regression cases pass |
| Formatters | GPDBWritable v1/v2 network order/alignment; multibyte delimited streaming, quoting, NULLs, encoding conversion; Java Parquet roundtrips and 20k-row scan |
| Lifecycle | HTTP sockets abort on cancellation, early LIMIT and errors; unfinished writes never send a success terminator; recovery in same SQL session |
| Activity | Segment-dispatched activity/cancel/interrupt, preserved function/view names and revoked PUBLIC access; live PXF and catalog tests |
| Upgrade | C pxf 2.2 / pxf_fdw 2.0 → Rust 3.0 in same loaded backend; OIDs, table dependencies and explicit grants verified |
| Packaging | Independent release libraries, SQL/control files, compatible gpextable/fdw payload directories, atomic install |
| Arrow IPC | Real IPC GET/POST tests; no production Arrow SQL/PXF format yet |

Intentional conservative changes: unsupported/non-C-collated predicates remain
local; malformed binary frames produce bounded SQL errors instead of unsafe
reads/FATAL termination; text NUL bytes are rejected; the delimited parser accepts
valid trailing NULL columns and handles fragmented quoted records without reading
outside the supplied buffer. These changes preserve valid-data results.

SQL_ASCII bytes are preserved by the core, but Cloudberry 2.1 rejects SQL_ASCII
as a database encoding. LATIN1 database/data conversions were tested. PG16 and
other CPU/OS combinations require their own qualification run. External-table
and FDW retain their distinct option/wire-format choices; Arrow support is future
work, not part of this compatibility release.
