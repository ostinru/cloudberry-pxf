-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements.  See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership.  The ASF licenses this file
-- to you under the Apache License, Version 2.0 (the
-- "License"); you may not use this file except in compliance
-- with the License.  You may obtain a copy of the License at
--
--   http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing,
-- software distributed under the License is distributed on an
-- "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
-- KIND, either express or implied.  See the License for the
-- specific language governing permissions and limitations
-- under the License.

/* fdw/pxf_fdw--2.0.sql */

-- complain if script is sourced in psql, rather than via CREATE EXTENSION
\echo Use "CREATE EXTENSION pxf_fdw" to load this file. \quit

CREATE FUNCTION pxf_fdw_handler()
RETURNS fdw_handler
AS 'MODULE_PATHNAME', 'pxf_fdw_handler_wrapper'
LANGUAGE C STRICT;

CREATE FUNCTION pxf_fdw_validator(text[], oid)
RETURNS void
AS 'MODULE_PATHNAME', 'pxf_fdw_validator_wrapper'
LANGUAGE C STRICT;

CREATE FOREIGN DATA WRAPPER jdbc_pxf_fdw
    HANDLER pxf_fdw_handler
    VALIDATOR pxf_fdw_validator
    OPTIONS ( protocol 'jdbc', mpp_execute 'all segments' );

CREATE FOREIGN DATA WRAPPER hdfs_pxf_fdw
    HANDLER pxf_fdw_handler
    VALIDATOR pxf_fdw_validator
    OPTIONS ( protocol 'hdfs', mpp_execute 'all segments' );

CREATE FOREIGN DATA WRAPPER hive_pxf_fdw
    HANDLER pxf_fdw_handler
    VALIDATOR pxf_fdw_validator
    OPTIONS ( protocol 'hive', mpp_execute 'all segments' );

CREATE FOREIGN DATA WRAPPER hbase_pxf_fdw
    HANDLER pxf_fdw_handler
    VALIDATOR pxf_fdw_validator
    OPTIONS ( protocol 'hbase', mpp_execute 'all segments' );

CREATE FOREIGN DATA WRAPPER s3_pxf_fdw
    HANDLER pxf_fdw_handler
    VALIDATOR pxf_fdw_validator
    OPTIONS ( protocol 's3', mpp_execute 'all segments' );

CREATE FOREIGN DATA WRAPPER gs_pxf_fdw
    HANDLER pxf_fdw_handler
    VALIDATOR pxf_fdw_validator
    OPTIONS ( protocol 'gs', mpp_execute 'all segments' );

CREATE FOREIGN DATA WRAPPER abfss_pxf_fdw
    HANDLER pxf_fdw_handler
    VALIDATOR pxf_fdw_validator
    OPTIONS ( protocol 'abfss', mpp_execute 'all segments' );

CREATE FOREIGN DATA WRAPPER wasbs_pxf_fdw
    HANDLER pxf_fdw_handler
    VALIDATOR pxf_fdw_validator
    OPTIONS ( protocol 'wasbs', mpp_execute 'all segments' );

CREATE FOREIGN DATA WRAPPER file_pxf_fdw
    HANDLER pxf_fdw_handler
    VALIDATOR pxf_fdw_validator
    OPTIONS ( protocol 'file', mpp_execute 'all segments' );

-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements. See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership. The ASF licenses this file
-- to you under the Apache License, Version 2.0 (the
-- "License"); you may not use this file except in compliance
-- with the License. You may obtain a copy of the License at
--
--   http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing,
-- software distributed under the License is distributed on an
-- "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
-- KIND, either express or implied. See the License for the
-- specific language governing permissions and limitations
-- under the License.

-- Diagnostic probes share the same code as the production callbacks.
CREATE FUNCTION pxf_fdw_rust_api_version() RETURNS text
AS 'MODULE_PATHNAME', 'pxf_fdw_rust_api_version_wrapper'
LANGUAGE C IMMUTABLE STRICT;

CREATE FUNCTION pxf_fdw_rust_postgres_major() RETURNS integer
AS 'MODULE_PATHNAME', 'pxf_fdw_rust_postgres_major_wrapper'
LANGUAGE C IMMUTABLE STRICT;

CREATE FUNCTION pxf_fdw_rust_validate_location(bytea, boolean) RETURNS boolean
AS 'MODULE_PATHNAME', 'pxf_fdw_rust_validate_location_wrapper'
LANGUAGE C IMMUTABLE STRICT;
