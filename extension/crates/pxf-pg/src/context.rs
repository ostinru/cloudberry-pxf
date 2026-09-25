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
use crate::abi;
use pgrx::{pg_sys, PgMemoryContexts};
use pxf_core::request::{Column, Context};
use std::ffi::{c_char, CStr};

/// Copy database bytes without assuming UTF-8. Null denotes an absent value.
/// # Safety
/// `value` must be null or point to a live NUL-terminated string.
pub unsafe fn bytes(value: *const c_char) -> Vec<u8> {
    if value.is_null() {
        Vec::new()
    } else {
        unsafe { CStr::from_ptr(value).to_bytes().to_vec() }
    }
}

/// # Safety
/// Call on the backend thread with a live current PostgreSQL memory context.
pub unsafe fn pstr(value: &[u8]) -> *mut c_char {
    assert!(!value.contains(&0), "database string contains NUL");
    unsafe {
        let ptr = pg_sys::palloc(value.len() + 1).cast::<u8>();
        std::ptr::copy_nonoverlapping(value.as_ptr(), ptr, value.len());
        *ptr.add(value.len()) = 0;
        ptr.cast()
    }
}

/// # Safety
/// `list` must be NIL or a live pointer List whose elements are pointers to T.
pub unsafe fn list<T>(list: *mut pg_sys::List) -> Vec<*mut T> {
    if list.is_null() {
        return Vec::new();
    }
    unsafe {
        (0..(*list).length as usize)
            .map(|i| (*(*list).elements.add(i)).ptr_value.cast())
            .collect()
    }
}

/// # Safety
/// Call on the PostgreSQL backend thread during an active transaction.
pub unsafe fn environment() -> Context {
    unsafe {
        let mut env: abi::Environment = std::mem::zeroed();
        abi::pxf_cb_environment(&mut env);
        let result = Context {
            user: bytes(env.user),
            segment_id: bytes(env.segment_id),
            segment_count: bytes(env.segment_count),
            transaction_id: bytes(env.transaction_id),
            session_id: env.session_id,
            command_count: env.command_count,
            database_encoding: bytes(pg_sys::GetDatabaseEncodingName()),
            alignment: std::mem::size_of::<usize>(),
        };
        for ptr in [
            env.user,
            env.segment_id,
            env.segment_count,
            env.transaction_id,
        ] {
            pg_sys::pfree(ptr.cast());
        }
        result
    }
}

/// Only read cancellation flags here. PostgreSQL ERROR must be raised after
/// the HTTP future and runtime have returned and released their resources.
pub fn cancelled() -> bool {
    unsafe {
        std::ptr::read_volatile(std::ptr::addr_of!(pg_sys::QueryCancelPending)) != 0
            || std::ptr::read_volatile(std::ptr::addr_of!(pg_sys::ProcDiePending)) != 0
    }
}

pub fn transport_result<T>(result: Result<T, pxf_core::transport::TransportError>) -> T {
    match result {
        Ok(value) => value,
        Err(pxf_core::transport::TransportError::Cancelled) => {
            unsafe {
                pg_sys::ProcessInterrupts(c"pxf-rust".as_ptr(), line!() as i32);
            }
            pgrx::error!("PXF request cancelled");
        }
        Err(pxf_core::transport::TransportError::Http(error)) => {
            // Match the C extension's SQLSTATE and user-facing diagnostics.
            // HTTP status and Java stack traces appear only at LOG verbosity.
            let verbose = unsafe {
                pg_sys::LOG as i32 >= pg_sys::log_min_messages
                    || pg_sys::LOG as i32 >= pg_sys::client_min_messages
            };
            let prefix = if verbose {
                format!("PXF server error({})", error.status)
            } else {
                "PXF server error".to_owned()
            };
            let message = if error.status == 404 {
                format!("{prefix}: PXF service could not be reached. PXF is not running in the tomcat container")
            } else {
                format!("{prefix} : {}", error.message)
            };
            let mut report = pg_sys::panic::ErrorReport::new(
                pgrx::PgSqlErrorCode::ERRCODE_CONNECTION_EXCEPTION,
                message,
                "pxf",
            );
            if verbose {
                if let Some(trace) = &error.trace {
                    report = report.set_detail(trace);
                }
            }
            if let Some(hint) = &error.hint {
                report = report.set_hint(hint);
            }
            if error.truncated {
                report = report.set_detail("PXF error response was truncated");
            }
            report.report(pgrx::PgLogLevel::ERROR);
            unreachable!()
        }
        Err(error) => pgrx::error!("{}", error),
    }
}

/// Query-owned Rust state gets dropped even when executor ERROR or LIMIT skips
/// normal end callbacks. Take the Option on normal end, never free this Box.
/// # Safety
/// The context must be live and outlive all uses of the returned pointer.
/// T must be safe to drop when that context resets, without calling PostgreSQL.
pub unsafe fn managed<T: 'static>(context: pg_sys::MemoryContext, value: T) -> *mut Option<T> {
    PgMemoryContexts::For(context).leak_and_drop_on_delete(Some(value))
}

/// # Safety
/// The relation must be open and its tuple descriptor live on the backend thread.
pub unsafe fn columns(relation: pg_sys::Relation, arrays: bool) -> Vec<Column> {
    unsafe {
        let desc = (*relation).rd_att;
        let mut result = Vec::new();
        for attr in (*desc).attrs.as_slice((*desc).natts as usize) {
            if attr.attisdropped {
                continue;
            }
            let tuple = pg_sys::SearchSysCache1(
                pg_sys::SysCacheIdentifier::TYPEOID as i32,
                attr.atttypid.into(),
            );
            if tuple.is_null() {
                pgrx::error!("cache lookup failed for type {:?}", attr.atttypid);
            }
            let typ = pg_sys::GETSTRUCT(tuple).cast::<pg_sys::FormData_pg_type>();
            let type_name = bytes((*typ).typname.data.as_ptr());
            pg_sys::ReleaseSysCache(tuple);
            let mut oid = attr.atttypid;
            if arrays {
                let element = pg_sys::get_element_type(oid);
                if element != pg_sys::InvalidOid {
                    oid = element;
                }
            }
            let modifier = attr.atttypmod;
            let modifiers = if modifier < 0 {
                Vec::new()
            } else if oid == pg_sys::NUMERICOID {
                vec![(modifier >> 16) & 0xffff, (modifier - 4) & 0xffff]
            } else if [pg_sys::CHAROID, pg_sys::BPCHAROID, pg_sys::VARCHAROID].contains(&oid) {
                vec![modifier - 4]
            } else if [
                pg_sys::BITOID,
                pg_sys::VARBITOID,
                pg_sys::TIMESTAMPOID,
                pg_sys::TIMESTAMPTZOID,
                pg_sys::TIMEOID,
                pg_sys::TIMETZOID,
            ]
            .contains(&oid)
            {
                vec![modifier]
            } else if oid == pg_sys::INTERVALOID {
                vec![modifier & 0xffff]
            } else {
                Vec::new()
            };
            result.push(Column {
                name: bytes(attr.attname.data.as_ptr()),
                type_oid: attr.atttypid.as_u32(),
                type_name,
                modifiers,
            });
        }
        result
    }
}
