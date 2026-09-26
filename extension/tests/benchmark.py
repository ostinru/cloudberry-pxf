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

"""Small repeatable C/Rust FDW scan comparison using the same Java PXF server."""
import os
from pathlib import Path
import shutil
import statistics
import subprocess
import tempfile
import time

rows = int(os.environ.get("PXF_BENCH_ROWS", "100000"))
iterations = int(os.environ.get("PXF_BENCH_ITERATIONS", "3"))
root = Path(os.environ.get("PXF_RUST_DATA", "/tmp/pxf-fixture"))
directory = Path(tempfile.mkdtemp(prefix="benchmark-", dir=root))
database = f"pxf_rust_benchmark_{os.getpid()}"

def sql(statement):
    return subprocess.check_output(["psql", "-XAt", "-v", "ON_ERROR_STOP=1", "-d", database, "-c", statement], text=True).strip()

subprocess.run(["createdb", database], check=True)
try:
    with (directory / "data.csv").open("w") as stream:
        for i in range(rows):
            stream.write(f"{i},payload number {i}\n")
    resource = directory.name + "/data.csv"
    sql("CREATE EXTENSION pxf_fdw; CREATE SERVER rust_pxf_live FOREIGN DATA WRAPPER file_pxf_fdw; CREATE USER MAPPING FOR CURRENT_USER SERVER rust_pxf_live;")
    sql(f"CREATE FOREIGN TABLE rust_data(id int,name text) SERVER rust_pxf_live OPTIONS(resource '{resource}',format 'csv');")
    sql("CREATE FUNCTION c_handler() RETURNS fdw_handler AS '$libdir/pxf_fdw','pxf_fdw_handler' LANGUAGE C STRICT; CREATE FOREIGN DATA WRAPPER c_pxf HANDLER c_handler OPTIONS(protocol 'file',mpp_execute 'all segments');")
    # PXF resolves the server directory by PostgreSQL server name.
    sql("ALTER SERVER rust_pxf_live RENAME TO benchmark_rust;")
    # Both table definitions use the same server name at execution, one at a time.
    times = {}
    for implementation in ("rust", "c"):
        if implementation == "rust":
            sql("ALTER SERVER benchmark_rust RENAME TO rust_pxf_live;")
        else:
            sql("ALTER SERVER rust_pxf_live RENAME TO benchmark_rust; CREATE SERVER rust_pxf_live FOREIGN DATA WRAPPER c_pxf; CREATE USER MAPPING FOR CURRENT_USER SERVER rust_pxf_live;")
            sql(f"CREATE FOREIGN TABLE c_data(id int,name text) SERVER rust_pxf_live OPTIONS(resource '{resource}',format 'csv');")
        statement = f"SELECT count(*) FROM {implementation}_data"
        assert int(sql(statement)) == rows
        samples = []
        for _ in range(iterations):
            started = time.monotonic()
            assert int(sql(statement)) == rows
            samples.append(time.monotonic() - started)
        times[implementation] = statistics.median(samples)
    print(f"rows={rows} iterations={iterations} median_seconds={times} rust_over_c={times['rust']/times['c']:.3f}")
finally:
    subprocess.run(["dropdb", database], check=True)
    shutil.rmtree(directory)
