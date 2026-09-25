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

//! Common database adapter, statically linked into each extension library.
//! There is intentionally no module magic or SQL entry point in this crate.

pub mod abi;
pub mod context;
pub mod external;
pub mod fdw;
pub mod options;
pub mod predicate;
// pg_guard on custom extern blocks uses this pgrx boundary helper.
pub use pgrx::pg_sys::ffi;

#[cfg(not(any(feature = "pg14", feature = "pg16")))]
compile_error!("select exactly one Cloudberry kernel feature: pg14 or pg16");
#[cfg(all(feature = "pg14", feature = "pg16"))]
compile_error!("pg14 and pg16 must be built separately against their own pg_config");

pub fn api_version() -> &'static str {
    pxf_core::API_VERSION
}

/// A small compatibility probe used by installation smoke tests.
pub fn postgres_major() -> i32 {
    (pgrx::pg_sys::PG_VERSION_NUM / 10_000) as i32
}

pub fn validate_location(
    location: &[u8],
    writable: bool,
) -> Result<(), pxf_core::uri::LocationError> {
    use pxf_core::uri::{Direction, Location};
    Location::parse(location)?.validate(if writable {
        Direction::Write
    } else {
        Direction::Read
    })
}

pub mod formatter;
