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
CREATE SERVER rust_pxf_live FOREIGN DATA WRAPPER file_pxf_fdw;
CREATE USER MAPPING FOR CURRENT_USER SERVER rust_pxf_live;
CREATE TEMP TABLE expected(id int,b bool,s smallint,l bigint,f real,d double precision,v numeric(10,3),name text,bin bytea,day date) DISTRIBUTED BY(id);
INSERT INTO expected VALUES(1,true,-2,9876543210,1.5,2.25,123.456,'кириллица','\x00ff','2026-01-02'),(2,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL);
CREATE WRITABLE EXTERNAL TABLE binary_write(LIKE expected) LOCATION(:'binary_uri') FORMAT 'CUSTOM'(FORMATTER='pxfwritable_export') ENCODING 'UTF8' DISTRIBUTED BY(id);
INSERT INTO binary_write SELECT * FROM expected;
CREATE EXTERNAL TABLE binary_read(LIKE expected) LOCATION(:'binary_uri') FORMAT 'CUSTOM'(FORMATTER='pxfwritable_import') ENCODING 'UTF8';
SELECT test_assert(NOT EXISTS((SELECT * FROM expected EXCEPT ALL SELECT * FROM binary_read) UNION ALL (SELECT * FROM binary_read EXCEPT ALL SELECT * FROM expected)), 'GPDBWritable Parquet roundtrip');
SELECT test_assert((SELECT name FROM binary_read WHERE id=1)='кириллица','binary pushdown and projection');
CREATE FOREIGN TABLE binary_fdw(LIKE expected) SERVER rust_pxf_live OPTIONS(resource :'binary_resource',format 'parquet');
SELECT test_assert(NOT EXISTS((SELECT * FROM expected EXCEPT ALL SELECT * FROM binary_fdw) UNION ALL (SELECT * FROM binary_fdw EXCEPT ALL SELECT * FROM expected)), 'external writer to FDW reader');
CREATE FOREIGN TABLE parquet_write(LIKE expected) SERVER rust_pxf_live OPTIONS(resource :'fdw_resource',format 'parquet');
INSERT INTO parquet_write SELECT * FROM expected;
CREATE EXTERNAL TABLE parquet_read(LIKE expected) LOCATION(:'fdw_uri') FORMAT 'CUSTOM'(FORMATTER='pxfwritable_import') ENCODING 'UTF8';
SELECT test_assert(NOT EXISTS((SELECT * FROM expected EXCEPT ALL SELECT * FROM parquet_read) UNION ALL (SELECT * FROM parquet_read EXCEPT ALL SELECT * FROM expected)), 'FDW writer to binary reader');
CREATE WRITABLE EXTERNAL TABLE csv_write(id int,name text) LOCATION(:'csv_uri') FORMAT 'CSV' DISTRIBUTED BY(id);
INSERT INTO csv_write VALUES(1,'comma,and "quote"'),(2,NULL),(3,'перенос
строки');
CREATE EXTERNAL TABLE csv_read(id int,name text) LOCATION(:'csv_uri') FORMAT 'CSV';
SELECT test_assert((SELECT array_agg(name ORDER BY id) FROM csv_read)=ARRAY['comma,and "quote"',NULL,'перенос
строки'], 'CSV UTF8/NULL/quote/multiline');
CREATE EXTERNAL TABLE delimited_read(id int,name text) LOCATION(:'delimited_uri') FORMAT 'CUSTOM'(FORMATTER='pxfdelimited_import',DELIMITER='☃',QUOTE='"') ENCODING 'UTF8';
SELECT test_assert((SELECT array_agg(name ORDER BY id) FROM delimited_read)=ARRAY['один',NULL,'a"b'], 'multibyte delimiter and escaped quotes');
CREATE EXTERNAL TABLE latin_read(id int,name text) LOCATION(:'latin_uri') FORMAT 'CSV' ENCODING 'LATIN1';
SELECT test_assert((SELECT name FROM latin_read)='café','external LATIN1 to UTF8');
CREATE FOREIGN TABLE latin_fdw(id int,name text) SERVER rust_pxf_live OPTIONS(resource :'latin_resource',format 'csv',encoding 'LATIN1');
SELECT test_assert((SELECT name FROM latin_fdw)='café','FDW LATIN1 to UTF8');
-- Several HTTP buffers and formatter calls; catches context/reset regressions.
CREATE WRITABLE EXTERNAL TABLE bulk_write(id int,name text) LOCATION(:'bulk_uri') FORMAT 'CUSTOM'(FORMATTER='pxfwritable_export') ENCODING 'UTF8' DISTRIBUTED BY(id);
INSERT INTO bulk_write SELECT i,repeat('stream ',20) FROM generate_series(1,20000) i;
CREATE EXTERNAL TABLE bulk_read(id int,name text) LOCATION(:'bulk_uri') FORMAT 'CUSTOM'(FORMATTER='pxfwritable_import') ENCODING 'UTF8';
SELECT test_assert((SELECT count(*)=20000 AND sum(id)=200010000 FROM bulk_read),'binary streaming 20k rows');
SELECT test_assert((SELECT count(*) FROM bulk_read WHERE id BETWEEN 100 AND 199)=100,'binary predicate correctness');
SELECT * FROM bulk_read LIMIT 1;
SELECT test_assert((SELECT count(*) FROM binary_read)=2,'external scan after early LIMIT');
