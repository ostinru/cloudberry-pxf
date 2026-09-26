// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

use pgrx::prelude::*;

pgrx::pg_module_magic!();

#[pg_extern]
fn pxf_fdw_handler() -> PgBox<pg_sys::FdwRoutine> {
    pxf_pg::fdw::handler()
}

#[pg_extern]
unsafe fn pxf_fdw_validator(fcinfo: pg_sys::FunctionCallInfo) {
    unsafe {
        let arguments = (*fcinfo).args.as_slice(2);
        let options = pg_sys::untransformRelOptions(arguments[0].value);
        let catalog = pg_sys::Oid::from(arguments[1].value.value() as u32);
        pxf_pg::options::validate(options, catalog);
    }
}

// SQL diagnostics for installation and wire-compatibility checks.
#[pg_extern]
fn pxf_fdw_rust_api_version() -> &'static str {
    pxf_pg::api_version()
}

#[pg_extern]
fn pxf_fdw_rust_postgres_major() -> i32 {
    pxf_pg::postgres_major()
}

#[pg_extern]
fn pxf_fdw_rust_validate_location(location: &[u8], writable: bool) -> bool {
    pxf_pg::validate_location(location, writable).is_ok()
}
