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
use crate::context::{bytes, list, pstr};
use pgrx::{pg_sys, PgSqlErrorCode};
use pxf_core::request::Options;
use std::ptr::null_mut;

const COPY: &[&[u8]] = &[
    b"header",
    b"delimiter",
    b"quote",
    b"escape",
    b"null",
    b"encoding",
    b"newline",
    b"fill_missing_fields",
    b"force_not_null",
    b"force_null",
];

pub fn fail(code: PgSqlErrorCode, message: impl AsRef<str>) -> ! {
    pgrx::ereport!(pgrx::PgLogLevel::ERROR, code, message.as_ref());
    unreachable!()
}

fn invalid(message: impl AsRef<str>) -> ! {
    fail(PgSqlErrorCode::ERRCODE_FDW_INVALID_STRING_FORMAT, message)
}

fn integer(value: &[u8]) -> Option<i32> {
    // Preserve the legacy strtol/atoi prefix handling without C overflow UB.
    let value = value.trim_ascii_start();
    let sign = usize::from(value.first().is_some_and(|b| *b == b'+' || *b == b'-'));
    let len = sign
        + value[sign..]
            .iter()
            .take_while(|b| b.is_ascii_digit())
            .count();
    if len == sign {
        return None;
    }
    std::str::from_utf8(&value[..len]).ok()?.parse().ok()
}

/// # Safety
/// Options must be a live PostgreSQL DefElem list and catalog a valid catalog OID.
/// Call on the backend thread inside a transaction.
pub unsafe fn validate(options: *mut pg_sys::List, catalog: pg_sys::Oid) {
    unsafe {
        let mut protocol = None;
        let mut resource = None;
        let mut limit = -1;
        let mut rows = true;
        let mut log_errors = false;
        let mut copy = null_mut();
        let mut force_seen = Vec::new();
        for def in list::<pg_sys::DefElem>(options) {
            let name = bytes((*def).defname);
            let value = bytes(pg_sys::defGetString(def));
            let required = match name.as_slice() {
                b"protocol" => Some((
                    pg_sys::ForeignDataWrapperRelationId,
                    "pg_foreign_data_wrapper",
                )),
                b"resource" | b"format" | b"reject_limit" | b"reject_limit_type"
                | b"log_errors" => Some((pg_sys::ForeignTableRelationId, "pg_foreign_table")),
                b"config" => Some((pg_sys::ForeignServerRelationId, "pg_foreign_server")),
                _ => None,
            };
            if let Some((required, label)) = required {
                if catalog != required {
                    fail(
                        PgSqlErrorCode::ERRCODE_FDW_INVALID_OPTION_NAME,
                        format!(
                            "the {} option can only be defined at the {label} level",
                            String::from_utf8_lossy(&name)
                        ),
                    );
                }
            }
            match name.as_slice() {
                b"protocol" => protocol = Some(value),
                b"resource" => resource = Some(value),
                b"mpp_execute" if catalog == pg_sys::UserMappingRelationId => fail(
                    PgSqlErrorCode::ERRCODE_FDW_INVALID_OPTION_NAME,
                    "the mpp_execute option cannot be defined at the user mapping level",
                ),
                b"disable_ppd" => {
                    pg_sys::defGetBoolean(def);
                    if catalog == pg_sys::UserMappingRelationId
                        || catalog == pg_sys::ForeignDataWrapperRelationId
                    {
                        fail(
                            PgSqlErrorCode::ERRCODE_FDW_INVALID_OPTION_NAME,
                            if catalog == pg_sys::UserMappingRelationId {
                                "the disable_ppd option cannot be defined at the user mapping level"
                            } else {
                                "the disable_ppd option cannot be defined at the foreign-data wrapper level"
                            },
                        );
                    }
                }
                b"pxf_port" => {
                    let port = integer(&value).unwrap_or(0);
                    if !(1024..=65535).contains(&port) {
                        invalid(format!(
                            "invalid port number: {port}. valid port numbers are 1024 to 65535"
                        ));
                    }
                }
                b"reject_limit" => {
                    limit = integer(&value).unwrap_or(-1);
                    if limit < 1 {
                        invalid(format!(
                            "invalid reject_limit value '{}', should be a positive integer",
                            String::from_utf8_lossy(&value)
                        ));
                    }
                }
                b"reject_limit_type" => {
                    rows = value.eq_ignore_ascii_case(b"rows");
                    if !rows && !value.eq_ignore_ascii_case(b"percent") {
                        invalid("invalid reject_limit_type value, only 'rows' and 'percent' are supported");
                    }
                }
                b"log_errors" => {
                    pg_sys::defGetBoolean(def);
                    log_errors = true;
                }
                b"format" => {
                    if value.eq_ignore_ascii_case(b"text") || value.eq_ignore_ascii_case(b"csv") {
                        copy = pg_sys::lappend(copy, def.cast());
                    }
                }
                _ if COPY.contains(&name.as_slice()) => {
                    let force = name == b"force_not_null" || name == b"force_null";
                    let expected = if force {
                        pg_sys::AttributeRelationId
                    } else {
                        pg_sys::ForeignTableRelationId
                    };
                    if catalog != expected {
                        fail(
                            PgSqlErrorCode::ERRCODE_FDW_INVALID_OPTION_NAME,
                            format!("invalid option \"{}\"", String::from_utf8_lossy(&name)),
                        );
                    }
                    if force {
                        if force_seen.contains(&name) {
                            fail(
                                PgSqlErrorCode::ERRCODE_SYNTAX_ERROR,
                                "conflicting or redundant options",
                            );
                        }
                        force_seen.push(name);
                        pg_sys::defGetBoolean(def);
                    } else {
                        copy = pg_sys::lappend(copy, def.cast());
                    }
                }
                _ => {}
            }
        }
        if catalog == pg_sys::ForeignDataWrapperRelationId
            && protocol.as_ref().is_none_or(Vec::is_empty)
        {
            fail(
                PgSqlErrorCode::ERRCODE_FDW_DYNAMIC_PARAMETER_VALUE_NEEDED,
                "the protocol option must be defined for PXF foreign-data wrappers",
            );
        }
        if catalog == pg_sys::ForeignTableRelationId && resource.as_ref().is_none_or(Vec::is_empty)
        {
            fail(
                PgSqlErrorCode::ERRCODE_FDW_DYNAMIC_PARAMETER_VALUE_NEEDED,
                "the resource option must be defined at the foreign table level",
            );
        }
        if limit >= 0 {
            if rows && limit < 2 {
                invalid(format!(
                    "invalid (ROWS) reject_limit value '{limit}', valid values are 2 or larger"
                ));
            }
            if !rows && !(1..=100).contains(&limit) {
                invalid(format!(
                    "invalid (PERCENT) reject_limit value '{limit}', valid values are 1 to 100"
                ));
            }
        } else if log_errors {
            invalid("the log_errors option cannot be set without reject_limit");
        }
        pg_sys::ProcessCopyOptions(null_mut(), null_mut(), true, copy, pg_sys::InvalidOid);
    }
}

pub struct FdwOptions {
    pub host: Vec<u8>,
    pub port: i32,
    pub resource: Vec<u8>,
    pub profile: Vec<u8>,
    pub server: Vec<u8>,
    pub data_encoding: Vec<u8>,
    pub copy: *mut pg_sys::List,
    pub headers: Options,
    pub disable_ppd: bool,
    pub reject_limit: i32,
    pub reject_rows: bool,
    pub log_errors: bool,
    pub all_segments: bool,
}

/// # Safety
/// The table must be a foreign table locked for the current transaction.
/// Returned COPY options borrow the current PostgreSQL memory context.
pub unsafe fn get(table_oid: pg_sys::Oid) -> FdwOptions {
    unsafe {
        let table = pg_sys::GetForeignTable(table_oid);
        let server = pg_sys::GetForeignServer((*table).serverid);
        let user = pg_sys::GetUserMapping(pg_sys::GetUserId(), (*server).serverid);
        let wrapper = pg_sys::GetForeignDataWrapper((*server).fdwid);
        let mut result = FdwOptions {
            host: b"localhost".to_vec(),
            port: 5888,
            resource: Vec::new(),
            profile: Vec::new(),
            server: bytes((*server).servername),
            data_encoding: bytes(pg_sys::GetDatabaseEncodingName()),
            copy: null_mut(),
            headers: Vec::new(),
            disable_ppd: false,
            reject_limit: -1,
            reject_rows: true,
            log_errors: false,
            all_segments: (*table).exec_location as u8 == b's',
        };
        let mut format = None;
        let mut other_names = Vec::new();
        // Preserve C's ordering: ordinary PXF options take the first value;
        // recognized connection/control options are assigned on each occurrence.
        for options in [
            (*table).options,
            (*user).options,
            (*server).options,
            (*wrapper).options,
        ] {
            for def in list::<pg_sys::DefElem>(options) {
                let name = bytes((*def).defname);
                let value = bytes(pg_sys::defGetString(def));
                match name.as_slice() {
                    b"pxf_host" => result.host = value,
                    b"pxf_port" => {
                        result.port = integer(&value).filter(|n| *n != 0).unwrap_or(5888)
                    }
                    b"pxf_protocol" => {} // C constructs http:// URLs irrespective of this option.
                    b"protocol" => result.profile = value,
                    b"resource" => result.resource = value,
                    b"format" => format = Some(value),
                    b"reject_limit" => result.reject_limit = integer(&value).unwrap_or(-1),
                    b"reject_limit_type" => {
                        result.reject_rows = value.eq_ignore_ascii_case(b"rows")
                    }
                    b"log_errors" => result.log_errors = pg_sys::defGetBoolean(def),
                    b"disable_ppd" => result.disable_ppd = pg_sys::defGetBoolean(def),
                    _ if COPY.contains(&name.as_slice()) => {
                        if name == b"encoding" {
                            result.data_encoding = value.clone();
                        }
                        result.copy = pg_sys::lappend(result.copy, def.cast());
                        result.headers.push((name, value));
                    }
                    _ => {
                        if !other_names.contains(&name) {
                            other_names.push(name.clone());
                            result.headers.push((name, value));
                        }
                    }
                }
            }
        }
        let wire = if format
            .as_ref()
            .is_some_and(|v| v.eq_ignore_ascii_case(b"text"))
        {
            b"text".as_slice()
        } else {
            b"csv"
        };
        if let Some(format) = format {
            result.profile.push(b':');
            result.profile.extend_from_slice(&format);
        }
        let def = pg_sys::makeDefElem(pstr(b"format"), pg_sys::makeString(pstr(wire)).cast(), -1);
        result.copy = pg_sys::lappend(result.copy, def.cast());
        result.headers.push((b"format".to_vec(), wire.to_vec()));
        result
    }
}
