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

# No DROP/CREATE of extensions in a user's existing database. createdb failing
# (including a name collision) exits before installing any cleanup trap.
test_db="pxf_rust_smoke_${$}"
createdb "$test_db"
trap 'dropdb "$test_db"' EXIT
psql -X --set ON_ERROR_STOP=1 --dbname "$test_db" <<'SQL'
CREATE EXTENSION pxf;
CREATE EXTENSION pxf_fdw;
DO $$
BEGIN
    IF pxf_rust_api_version() <> pxf_fdw_rust_api_version() THEN
        RAISE EXCEPTION 'libraries disagree on PXF API version';
    END IF;
    IF pxf_rust_postgres_major() <> current_setting('server_version_num')::int / 10000
       OR pxf_fdw_rust_postgres_major() <> pxf_rust_postgres_major() THEN
        RAISE EXCEPTION 'libraries built against the wrong database kernel';
    END IF;
    IF NOT pxf_rust_validate_location(convert_to('pxf://data?PROFILE=s3:text', 'UTF8'), false)
       OR NOT pxf_fdw_rust_validate_location(convert_to('pxf://data?ACCESSOR=a&RESOLVER=r', 'UTF8'), true)
       OR pxf_rust_validate_location(convert_to('pxf://data?ACCESSOR=a&RESOLVER=r', 'UTF8'), false)
       OR pxf_fdw_rust_validate_location(convert_to('pxf://data?PROFILE=a&profile=b', 'UTF8'), false) THEN
        RAISE EXCEPTION 'shared location validation failed';
    END IF;
END $$;

-- VOLATILE wrappers force evaluation on the segments instead of constant
-- folding the immutable bootstrap functions on the coordinator.
CREATE FUNCTION pxf_rust_segment_probe() RETURNS text
AS '$libdir/pxf_rust', 'pxf_rust_api_version_wrapper' LANGUAGE C VOLATILE;
CREATE FUNCTION pxf_fdw_rust_segment_probe() RETURNS text
AS '$libdir/pxf_fdw_rust', 'pxf_fdw_rust_api_version_wrapper' LANGUAGE C VOLATILE;

CREATE TEMP TABLE segment_probes AS
SELECT gp_segment_id AS segment_id,
       pxf_rust_segment_probe() AS external_version,
       pxf_fdw_rust_segment_probe() AS fdw_version
FROM gp_dist_random('gp_id') DISTRIBUTED BY (segment_id);
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM segment_probes)
       OR EXISTS (SELECT FROM segment_probes
                  WHERE external_version IS DISTINCT FROM pxf_rust_api_version()
                     OR fdw_version IS DISTINCT FROM pxf_fdw_rust_api_version()) THEN
        RAISE EXCEPTION 'segment library loading failed';
    END IF;
    IF (SELECT count(*) FROM segment_probes) <>
       (SELECT count(*) FROM gp_segment_configuration WHERE role = 'p' AND content >= 0) THEN
        RAISE EXCEPTION 'not every primary segment executed the probes';
    END IF;
END $$;
SELECT * FROM segment_probes ORDER BY segment_id;
SQL
