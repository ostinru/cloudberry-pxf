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

\set ON_ERROR_STOP on
CREATE EXTENSION pxf VERSION '2.2';
CREATE EXTENSION pxf_fdw VERSION '2.0';
CREATE SERVER rust_pxf_live FOREIGN DATA WRAPPER file_pxf_fdw;
CREATE USER MAPPING FOR CURRENT_USER SERVER rust_pxf_live;
CREATE FOREIGN TABLE before_fdw(id int,name text) SERVER rust_pxf_live OPTIONS(resource 'read.csv',format 'csv');
CREATE EXTERNAL TABLE before_ext(id int,name text) LOCATION('pxf://read.csv?PROFILE=file:csv&SERVER=rust_pxf_live') FORMAT 'CSV';
CREATE VIEW dependent AS SELECT * FROM before_fdw;
GRANT SELECT ON before_fdw,before_ext,dependent TO PUBLIC;
GRANT EXECUTE ON FUNCTION pxf_stat_activity_raw() TO PUBLIC;
CREATE TEMP TABLE identity_before AS
SELECT oid,proname,proacl FROM pg_proc WHERE proname IN('pxf_fdw_handler','pxf_fdw_validator','pxf_read','pxf_write','pxf_validate','pxfwritable_import','pxfwritable_export','pxfdelimited_import','pxf_stat_activity_raw','pxf_cancel_backend_raw','pxf_interrupt_backend_raw');
CREATE TEMP TABLE objects_before AS
SELECT oid,relname,relacl FROM pg_class WHERE relname IN('before_fdw','before_ext','dependent');
-- Load both old libraries before UPDATE, so this also tests in-process coexistence.
SELECT * FROM before_fdw ORDER BY id;
SELECT * FROM before_ext ORDER BY id;
SELECT * FROM pxf_stat_activity_raw();
ALTER EXTENSION pxf UPDATE TO '3.0';
ALTER EXTENSION pxf_fdw UPDATE TO '3.0';
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM identity_before b LEFT JOIN pg_proc p ON p.oid=b.oid WHERE p.oid IS NULL OR p.proname<>b.proname OR p.proacl IS DISTINCT FROM b.proacl) THEN RAISE EXCEPTION 'function identity or grant changed'; END IF;
 IF EXISTS(SELECT 1 FROM objects_before b LEFT JOIN pg_class c ON c.oid=b.oid WHERE c.oid IS NULL OR c.relname<>b.relname OR c.relacl IS DISTINCT FROM b.relacl) THEN RAISE EXCEPTION 'relation identity or grant changed'; END IF;
 IF (SELECT count(*) FROM dependent)<>2 OR (SELECT count(*) FROM before_ext)<>2 THEN RAISE EXCEPTION 'upgraded table is unreadable'; END IF;
 IF EXISTS(SELECT 1 FROM pg_proc p JOIN identity_before b USING(oid) WHERE p.probin NOT LIKE '%_rust') THEN RAISE EXCEPTION 'function still uses C library'; END IF;
END $$;
SELECT extname,extversion FROM pg_extension WHERE extname IN('pxf','pxf_fdw') ORDER BY extname;
