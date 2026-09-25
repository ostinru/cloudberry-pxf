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

# HTTP transport

The shared `pxf-core::transport` module uses reqwest 0.12.28 with rustls and a
private Tokio `current_thread` runtime per transfer. The dependency versions in
`Cargo.lock` build with the pinned Rust 1.85.1. There is no libcurl dependency.

## Reference implementation

Supabase Wrappers was inspected at commit
`f814368100aaa61ace39b306c7d664fb33584e3b`:

* [create_async_runtime](https://github.com/supabase/wrappers/blob/f814368100aaa61ace39b306c7d664fb33584e3b/supabase-wrappers/src/utils.rs#L353)
  uses `Builder::new_current_thread().enable_all().build()` inside its pgrx-based
  extension.
* [Airtable FDW](https://github.com/supabase/wrappers/blob/f814368100aaa61ace39b306c7d664fb33584e3b/wrappers/src/fdw/airtable_fdw/airtable_fdw.rs)
  uses reqwest and invokes async requests from synchronous callbacks with
  `rt.block_on(...)`. It collects response text and rows before returning them.
* [Wasm HTTP host](https://github.com/supabase/wrappers/blob/f814368100aaa61ace39b306c7d664fb33584e3b/wrappers/src/fdw/wasm_fdw/host/http.rs)
  uses reqwest, retry middleware, and `resp.text()`.

We use the same runtime arrangement, with a binary streaming interface and
explicit cancellation. The Wrappers framework is not a dependency. Its retry
middleware is not suitable as a default for a partially executed PXF upload.

## Contract

* HTTP/1.1 GET downloads and chunked POST uploads. POST includes
  `Expect: 100-continue`; interim responses are handled by the HTTP library.
* Only HTTP 200 means success, matching the current PXF transport. Redirects
  and request retries are disabled. No transparent content decompression is
  enabled; the body remains bytes. HTTP transfer framing is decoded normally.
* Header values are raw database bytes. `X-GP-*` values use the existing PXF
  percent encoding. An absent header is distinct from an empty header. Callers
  can select Content-Type/Accept; transport owns HTTP framing headers.
* Upload acceptance is not a durability acknowledgement. `finish()` closes the
  producer stream and validates the entire response. Drop/abort/cancellation
  closes the transfer without intentionally sending EOF. Failed transfers retain
  their error and cannot be resumed. No automatic retry follows an error.
* A one-slot channel limits queued application data. Each application chunk is
  at most `chunk_bytes` (default 64 KiB). Downloads also retain the caller's
  current chunk and at most the HTTP chunk being split. HTTP/TLS and kernel
  buffers are additional; `chunk_bytes` is not a process memory limit. The
  implementation never collects an entire successful response or upload.
* Error bodies have a separate limit (default 16 KiB). Non-200 JSON, HTML and
  text diagnostics retain bounded original bytes and a truncation flag. JSON
  message/hint/trace are available to the future database error adapter.
* Cancellation checks run outside Tokio between waits of at most 100 ms
  (default 50 ms). Callbacks must return a flag and must not raise PostgreSQL
  errors. The database adapter must first release transport resources, then
  propagate PostgreSQL cancellation/error. All network tasks own only Rust data.
* The current-thread scheduler runs async tasks on the calling backend thread.
  System DNS can use Tokio's blocking worker pool. Runtime shutdown does not
  wait for an uninterruptible DNS lookup; such a worker has no database pointers.
  No runtime or connection may be initialized in the postmaster before fork.
* Connect timeout defaults to 30 seconds. The optional whole-transfer timeout
  counts from opening the handle, including pauses by the producer/consumer.
  No total timeout is imposed by default on large streaming queries.

`Read` and `Write` adapters translate transport errors to `io::Error` while
retaining the typed underlying error. `Write::flush()` checks transfer state but
does not acknowledge delivery; only `Upload::finish()` completes HTTP.

## Arrow IPC

The supported transport shape for a future Arrow experiment is **IPC Streaming
Format inside an ordinary HTTP body**, with
`Content-Type: application/vnd.apache.arrow.stream`. The HTTP client does not
need to understand schemas or record batches. `StreamReader` consumes `Read`;
`StreamWriter` produces bytes through `Write`. HTTP chunks may split any IPC
message. Arrow File Format has different seek requirements; Arrow Flight is a
separate RPC protocol and is outside this transport contract.

Tests use Arrow 54.3.1 as a dev dependency only. They encode/decode actual batches
through HTTP GET and POST, including NULLs, Unicode and embedded NULs. Download
tests split HTTP bodies into three-byte chunks. They also show why the decoder's
Arrow end-of-stream marker is insufficient: the caller must consume HTTP EOF to
detect a truncated response. Writers must finish both IPC and then HTTP.

This proves transport compatibility, not PXF server support or bounded Arrow
batch allocation. Format negotiation, Java-side IPC support, dictionary/schema
handling, PostgreSQL types and batch memory limits belong to the next Arrow
implementation stage. See the [Arrow format](https://arrow.apache.org/docs/format/Columnar.html)
and [Rust IPC API](https://docs.rs/arrow-ipc/54.3.1/arrow_ipc/).

## Database integration

Both extensions use this transport. Query-owned memory-context destructors close
unfinished downloads/uploads; normal writes finish explicitly and validate the
server response. Live Cloudberry tests cover SELECT, rescan, COPY/INSERT, early
LIMIT, statement_timeout, executor errors, HTTP status errors and same-session
recovery. Java PXF CSV/Parquet roundtrips and a local C/Rust scan comparison run
alongside deterministic HTTP tests. Arrow-specific server work remains separate.
Production endpoints preserve the existing local HTTP protocol. TLS-specific
certificate deployment and real-world DNS failure behavior have not been qualified.
