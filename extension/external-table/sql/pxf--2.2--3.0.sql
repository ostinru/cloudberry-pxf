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


-- Replace implementation in place; preserve OIDs, dependencies and ACLs.
CREATE OR REPLACE FUNCTION pg_catalog.pxf_write() RETURNS integer
AS 'MODULE_PATHNAME', 'pxfprotocol_export_wrapper'
LANGUAGE C STABLE;

CREATE OR REPLACE FUNCTION pg_catalog.pxf_read() RETURNS integer
AS 'MODULE_PATHNAME', 'pxfprotocol_import_wrapper'
LANGUAGE C STABLE;

CREATE OR REPLACE FUNCTION pg_catalog.pxf_validate() RETURNS void
AS 'MODULE_PATHNAME', 'pxfprotocol_validate_urls_wrapper'
LANGUAGE C STABLE;

CREATE OR REPLACE FUNCTION pg_catalog.pxfwritable_import() RETURNS record
AS 'MODULE_PATHNAME', 'gpdbwritableformatter_import_wrapper'
LANGUAGE C STABLE;

CREATE OR REPLACE FUNCTION pg_catalog.pxfwritable_export(record) RETURNS bytea
AS 'MODULE_PATHNAME', 'gpdbwritableformatter_export_wrapper'
LANGUAGE C STABLE;

CREATE OR REPLACE FUNCTION pg_catalog.pxfdelimited_import() RETURNS record
AS 'MODULE_PATHNAME', 'pxfdelimited_import_wrapper'
LANGUAGE C STABLE;

CREATE OR REPLACE FUNCTION pxf_stat_activity_raw() RETURNS SETOF text
AS 'MODULE_PATHNAME', 'pxf_stat_activity_raw_wrapper'
LANGUAGE C VOLATILE EXECUTE ON ALL SEGMENTS;

CREATE OR REPLACE FUNCTION pxf_cancel_backend_raw(session_id int) RETURNS SETOF text
AS 'MODULE_PATHNAME', 'pxf_cancel_backend_raw_wrapper'
LANGUAGE C VOLATILE STRICT EXECUTE ON ALL SEGMENTS;

CREATE OR REPLACE FUNCTION pxf_interrupt_backend_raw(session_id int) RETURNS SETOF text
AS 'MODULE_PATHNAME', 'pxf_interrupt_backend_raw_wrapper'
LANGUAGE C VOLATILE STRICT EXECUTE ON ALL SEGMENTS;
