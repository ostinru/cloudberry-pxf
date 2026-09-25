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
unsafe fn pxfprotocol_import(fcinfo: pg_sys::FunctionCallInfo) -> i32 {
    unsafe { pxf_pg::external::transfer(fcinfo, false) }
}

#[pg_extern]
unsafe fn pxfprotocol_export(fcinfo: pg_sys::FunctionCallInfo) -> i32 {
    unsafe { pxf_pg::external::transfer(fcinfo, true) }
}

#[pg_extern]
unsafe fn pxfprotocol_validate_urls(fcinfo: pg_sys::FunctionCallInfo) {
    unsafe { pxf_pg::external::validate(fcinfo) }
}

#[pg_extern]
fn pxf_stat_activity_raw() -> SetOfIterator<'static, String> {
    SetOfIterator::new(std::iter::once(pxf_pg::external::activity(
        "stat_activity",
        None,
    )))
}

#[pg_extern]
fn pxf_cancel_backend_raw(session_id: i32) -> SetOfIterator<'static, String> {
    SetOfIterator::new(std::iter::once(pxf_pg::external::activity(
        "cancel_backend",
        Some(session_id),
    )))
}

#[pg_extern]
fn pxf_interrupt_backend_raw(session_id: i32) -> SetOfIterator<'static, String> {
    SetOfIterator::new(std::iter::once(pxf_pg::external::activity(
        "interrupt_backend",
        Some(session_id),
    )))
}

// SQL diagnostics for installation and wire-compatibility checks.
#[pg_extern]
fn pxf_rust_api_version() -> &'static str {
    pxf_pg::api_version()
}

#[pg_extern]
fn pxf_rust_postgres_major() -> i32 {
    pxf_pg::postgres_major()
}

#[pg_extern]
fn pxf_rust_validate_location(location: &[u8], writable: bool) -> bool {
    pxf_pg::validate_location(location, writable).is_ok()
}

#[pg_extern]
unsafe fn gpdbwritableformatter_export(fcinfo: pg_sys::FunctionCallInfo) -> Vec<u8> {
    unsafe { pxf_pg::formatter::export(fcinfo) }
}

// Formatter callbacks return an opaque record Datum through Cloudberry's
// formatter manager. They cannot use pg_extern's ordinary SQL tuple conversion.
macro_rules! formatter_entry {
    ($entry:ident, $info:ident, $delimited:expr) => {
        /// # Safety
        /// Called only by Cloudberry with a live FormatterData context and descriptor.
        #[no_mangle]
        #[pgrx::pg_guard]
        pub unsafe extern "C" fn $entry(fcinfo: pg_sys::FunctionCallInfo) -> pg_sys::Datum {
            unsafe {
                pxf_pg::formatter::import(fcinfo, $delimited).unwrap_or(pg_sys::Datum::from(0usize))
            }
        }
        #[no_mangle]
        pub extern "C" fn $info() -> *const pg_sys::Pg_finfo_record {
            static INFO: pg_sys::Pg_finfo_record = pg_sys::Pg_finfo_record { api_version: 1 };
            &INFO
        }
    };
}
formatter_entry!(
    gpdbwritableformatter_import_wrapper,
    pg_finfo_gpdbwritableformatter_import_wrapper,
    false
);
formatter_entry!(
    pxfdelimited_import_wrapper,
    pg_finfo_pxfdelimited_import_wrapper,
    true
);
