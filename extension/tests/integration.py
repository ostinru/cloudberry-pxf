#!/usr/bin/env python3
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

"""Run against a disposable Cloudberry cluster with local Java PXF running."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import shutil
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("fixture", ROOT / "http_fixture.py")
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)

def run(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, **kwargs)

with tempfile.TemporaryDirectory(prefix="pxf-rust-fixture-") as directory:
    directory = Path(directory)
    (directory / "read.csv").write_text("1,one\n2,two\n")
    server = fixture.ThreadingHTTPServer(("127.0.0.1", 0), fixture.Handler)
    server.directory = directory
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    database = f"pxf_rust_integration_{os.getpid()}"
    run("createdb", "--template=template0", "--encoding=UTF8", "--locale=C", database)
    try:
        run("psql", "-X", "-v", "ON_ERROR_STOP=1", "-v", f"fixture_port={server.server_port}", "-d", database, "-f", str(ROOT / "integration.sql"))
        # File profiles point at this directory in the disposable PXF server config.
        live_root = Path(os.environ.get("PXF_RUST_DATA", "/tmp/pxf-fixture"))
        live = Path(tempfile.mkdtemp(prefix="integration-", dir=live_root))
        try:
            (live / "delimited.txt").write_text('"1"☃"один"\n"2"☃""\n"3"☃"a""b"\n')
            (live / "latin.csv").write_bytes(b"1,caf\xe9\n")
            variables = []
            for name, path, profile in [("binary","binary","parquet"),("fdw","fdw","parquet"),("bulk","bulk","parquet"),("csv","csv","csv"),("delimited","delimited.txt","text"),("latin","latin.csv","csv")]:
                resource = live.name + "/" + path
                variables.extend(["-v", f"{name}_resource={resource}", "-v", f"{name}_uri=pxf://{resource}?PROFILE=file:{profile}&SERVER=rust_pxf_live"])
            run("psql", "-X", "-v", "ON_ERROR_STOP=1", "-d", database, *variables, "-f", str(ROOT / "live.sql"))
        finally:
            shutil.rmtree(live)
        time.sleep(.2)
        records = [json.loads(line) for line in (directory / "requests.jsonl").read_text().splitlines()]
        requests = [item for item in records if "method" in item]
        assert len({r["x-gp-segment-id"] for r in requests}) >= 1, "segment identities"
        assert all(r["x-gp-user"] and r["x-gp-session-id"] and r["x-gp-command-count"] for r in requests)
        assert any(r.get("projection") == ["0", "1"] for r in requests), "repeated projection headers"
        assert any(r.get("projection") == ["1"] for r in requests), "narrow projection"
        assert any(r.get("x-gp-has-filter") == "1" for r in requests), "predicate pushdown"
        assert any(r.get("event") == "stall_closed" and r.get("closed") for r in records), "cancel closes socket"
        assert any(r.get("event") == "stream_closed" for r in records), "LIMIT closes socket"
        posts = [r for r in requests if r["method"] == "POST"]
        body = "".join(r["body"] for r in posts)
        assert "3,three\n" in body and '6,"six,quoted"\n' in body, "INSERT and COPY bodies"
        assert all("aborted" not in r["body"] for r in posts), "failed write must not finish"
        for encoding in ("SQL_ASCII", "LATIN1"):
            encoded_db = database + "_" + encoding.lower()
            created = subprocess.run(["createdb", "--template=template0", f"--encoding={encoding}", "--locale=C", encoded_db], text=True, capture_output=True)
            if created.returncode and encoding == "SQL_ASCII" and "server encoding 'SQL_ASCII' is not supported" in created.stderr:
                print("SKIP SQL_ASCII database: Cloudberry rejects this encoding; raw-byte core tests still run")
                continue
            created.check_returncode()
            try:
                sql = f"""
CREATE EXTENSION pxf_fdw;
CREATE SERVER raw FOREIGN DATA WRAPPER file_pxf_fdw OPTIONS(pxf_port '{server.server_port}');
CREATE USER MAPPING FOR CURRENT_USER SERVER raw;
CREATE FOREIGN TABLE raw_data(id int, name text) SERVER raw OPTIONS(resource '/nonutf8',format 'csv');
DO $$BEGIN
 IF (SELECT array_agg(encode(convert_to(name,'{encoding}'),'hex') ORDER BY id) FROM raw_data) <> ARRAY['ff','e9'] THEN RAISE EXCEPTION 'encoding roundtrip'; END IF;
 EXECUTE format('ALTER FOREIGN TABLE raw_data RENAME name TO %I',convert_from(decode('ff','hex'),'{encoding}'));
END $$;
SELECT count(*) FROM raw_data;
"""
                run("psql", "-X", "-v", "ON_ERROR_STOP=1", "-d", encoded_db, input=sql)
            finally:
                run("dropdb", encoded_db)
        print(f"PASS: {len(requests)} HTTP requests, FDW lifecycle, pushdown, SREH and activity")
    finally:
        run("dropdb", database)
        server.shutdown()
        server.server_close()
