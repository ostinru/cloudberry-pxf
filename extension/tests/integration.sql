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
CREATE EXTENSION pxf;
CREATE EXTENSION pxf_fdw;
CREATE FUNCTION test_assert(ok boolean, message text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN IF ok IS DISTINCT FROM true THEN RAISE EXCEPTION 'assertion failed: %', message; END IF; END $$;
CREATE SERVER rust_fixture FOREIGN DATA WRAPPER file_pxf_fdw OPTIONS(pxf_port :'fixture_port');
CREATE USER MAPPING FOR CURRENT_USER SERVER rust_fixture;
CREATE FOREIGN TABLE fixture(id int, name text) SERVER rust_fixture OPTIONS(resource '/data',format 'csv');
SELECT test_assert((SELECT array_agg(name ORDER BY id) FROM fixture)=ARRAY['one','two'], 'FDW read');
SELECT test_assert((SELECT count(*) FROM fixture WHERE id=2 AND reverse(name)='owt')=1, 'local unsupported qual');
SELECT test_assert((SELECT count(*) FROM fixture WHERE id=99 OR reverse(name)='owt')=1, 'unsupported OR must remain local');
SELECT name FROM fixture WHERE id IN(1,2) ORDER BY id;
SELECT name FROM fixture WHERE name IS NOT NULL ORDER BY name;
SELECT test_assert((SELECT count(*) FROM fixture WHERE NOT(id=1))=1, 'NOT');
SELECT test_assert((SELECT row_to_json(f) FROM fixture f LIMIT 1) IS NOT NULL, 'whole row');
PREPARE read_param(int) AS SELECT count(*) FROM fixture WHERE id=$1;
EXECUTE read_param(1);
EXECUTE read_param(2);
DEALLOCATE read_param;
SET optimizer=off;
SET enable_material=off;
SET enable_hashjoin=off;
SET enable_mergejoin=off;
SELECT test_assert((SELECT count(*) FROM fixture f JOIN generate_series(1,2) s(id) USING(id))=2, 'join/rescan');
RESET enable_material;
RESET enable_hashjoin;
RESET enable_mergejoin;
INSERT INTO fixture VALUES(3,'three'),(4,NULL);
COPY fixture FROM STDIN WITH(format csv);
5,five
6,"six,quoted"
\.
CREATE FOREIGN TABLE dropped(id int, obsolete int, name text) SERVER rust_fixture OPTIONS(resource '/data',format 'csv');
ALTER FOREIGN TABLE dropped DROP COLUMN obsolete;
SELECT test_assert((SELECT count(*) FROM dropped WHERE name='two')=1, 'dropped-column mapping');
CREATE FOREIGN TABLE badrows(id int,name text) SERVER rust_fixture OPTIONS(resource '/badrows',format 'csv',reject_limit '3',log_errors 'true');
SELECT test_assert((SELECT count(*) FROM badrows)=2, 'SREH ignore malformed rows');
SELECT test_assert((SELECT count(*) FROM gp_read_error_log('badrows'))=2, 'SREH error log');
-- The resource name must survive per-tuple memory resets and error recovery.
CREATE FOREIGN TABLE latebad(id int,name text) SERVER rust_fixture OPTIONS(resource '/latebad',format 'csv');
DO $$DECLARE context text; attempt int; BEGIN
    FOR attempt IN 1..2 LOOP
        BEGIN
            PERFORM * FROM latebad;
            RAISE EXCEPTION 'expected late conversion failure';
        EXCEPTION WHEN invalid_text_representation THEN
            GET STACKED DIAGNOSTICS context=PG_EXCEPTION_CONTEXT;
            IF context NOT LIKE '%Foreign table latebad, record 3 of /latebad, column id: "bad"%' THEN
                RAISE EXCEPTION 'unexpected COPY error context: %', context;
            END IF;
        END;
        PERFORM test_assert((SELECT count(*) FROM fixture)=2, 'same session after conversion error');
    END LOOP;
END $$;
CREATE FOREIGN TABLE marker(id int,name text) SERVER rust_fixture OPTIONS(resource '/marker',format 'csv');
DO $$DECLARE context text; BEGIN
    PERFORM * FROM marker;
    RAISE EXCEPTION 'expected PXF marker failure';
EXCEPTION WHEN OTHERS THEN
    GET STACKED DIAGNOSTICS context=PG_EXCEPTION_CONTEXT;
    IF SQLERRM NOT LIKE '%fixture data error%'
       OR context NOT LIKE '%Foreign table marker, resource /marker%'
       OR context LIKE '%Foreign table marker, record %' THEN RAISE; END IF;
END $$;
CREATE FOREIGN TABLE http_error(id int,name text) SERVER rust_fixture OPTIONS(resource '/error',format 'csv');
DO $$DECLARE hint text; context text; BEGIN
    PERFORM * FROM http_error;
    RAISE EXCEPTION 'expected HTTP failure';
EXCEPTION WHEN OTHERS THEN
    GET STACKED DIAGNOSTICS hint=PG_EXCEPTION_HINT, context=PG_EXCEPTION_CONTEXT;
    IF SQLSTATE <> '08000' OR SQLERRM NOT LIKE 'PXF server error%fixture error%'
       OR hint NOT LIKE '%fixture hint%'
       OR context NOT LIKE '%Foreign table http_error, resource /error%'
       OR context LIKE '%Foreign table http_error, record %' THEN RAISE; END IF;
END $$;
SET client_min_messages=log;
DO $$DECLARE detail text; BEGIN
    PERFORM * FROM http_error;
    RAISE EXCEPTION 'expected verbose HTTP failure';
EXCEPTION WHEN OTHERS THEN
    GET STACKED DIAGNOSTICS detail=PG_EXCEPTION_DETAIL;
    IF SQLSTATE <> '08000' OR SQLERRM NOT LIKE 'PXF server error(500) : fixture error%'
       OR detail <> 'fixture trace' THEN RAISE; END IF;
END $$;
RESET client_min_messages;
CREATE FOREIGN TABLE stalled(id int,name text) SERVER rust_fixture OPTIONS(resource '/stall',format 'csv');
SET statement_timeout='700ms';
DO $$BEGIN
    PERFORM * FROM stalled;
    RAISE EXCEPTION 'expected cancellation';
EXCEPTION WHEN query_canceled THEN NULL;
END $$;
RESET statement_timeout;
SELECT test_assert((SELECT count(*) FROM fixture)=2,'same session after cancellation');
CREATE FOREIGN TABLE streamed(id int,name text) SERVER rust_fixture OPTIONS(resource '/stream',format 'csv');
SELECT * FROM streamed LIMIT 1;
-- A failed write must abort its stream, never emit a successful final chunk.
CREATE FUNCTION fail_row(i int) RETURNS int LANGUAGE plpgsql VOLATILE AS $$
BEGIN IF i=3 THEN RAISE EXCEPTION 'intentional write failure'; END IF; RETURN i; END $$;
DO $$BEGIN
    INSERT INTO fixture SELECT fail_row(i), 'aborted' FROM generate_series(1,5) i;
    RAISE EXCEPTION 'expected write failure';
EXCEPTION WHEN OTHERS THEN
    IF SQLERRM NOT LIKE '%intentional write failure%' THEN RAISE; END IF;
END $$;
SELECT test_assert((SELECT count(*) FROM fixture)=2,'same session after write error');
-- Segment-dispatched JSON activity and ACLs are part of the external extension.
SELECT test_assert((SELECT count(*) FROM pxf_stat_activity_raw())=(SELECT count(*) FROM gp_segment_configuration WHERE role='p' AND content>=0),'activity fanout');
SELECT test_assert(pxf_cancel_backend(-999)=0,'cancel inactive session');
SELECT test_assert(pxf_interrupt_backend(-999)=0,'interrupt inactive session');
SELECT test_assert(NOT EXISTS(SELECT 1 FROM pg_proc p,LATERAL aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a WHERE p.proname IN('pxf_stat_activity_raw','pxf_cancel_backend_raw','pxf_interrupt_backend_raw','pxf_cancel_backend','pxf_interrupt_backend') AND a.grantee=0 AND a.privilege_type='EXECUTE'),'activity ACL');
