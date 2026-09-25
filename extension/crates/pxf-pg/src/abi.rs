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
use pgrx::pg_sys;
use std::ffi::{c_char, c_int, c_void};

#[repr(C)]
pub struct ProtocolCall {
    pub relation: pg_sys::Relation,
    pub url: *const c_char,
    pub buffer: *mut c_char,
    pub length: c_int,
    pub user_context: *mut *mut c_void,
    pub last_call: bool,
    pub quals: *mut pg_sys::List,
    pub target: *mut pg_sys::List,
}

#[repr(C)]
pub struct Environment {
    pub user: *mut c_char,
    pub segment_id: *mut c_char,
    pub segment_count: *mut c_char,
    pub transaction_id: *mut c_char,
    pub session_id: c_int,
    pub command_count: c_int,
}

#[pgrx::pg_guard]
extern "C" {
    pub fn pxf_cb_environment(out: *mut Environment);
    pub fn pxf_cb_is_dispatcher() -> bool;
    pub fn pxf_cb_string_value(node: *mut pg_sys::Node) -> *const c_char;
    pub fn pxf_cb_collation_is_c(collation: pg_sys::Oid) -> bool;
    pub fn pxf_cb_clear_slot(slot: *mut pg_sys::TupleTableSlot);
    pub fn pxf_cb_copy_from_setup(
        state: pg_sys::CopyFromState,
        rel: pg_sys::Relation,
        limit: c_int,
        rows: bool,
        log_errors: bool,
        resource: *const c_char,
    );
    pub fn pxf_cb_copy_from_count(state: pg_sys::CopyFromState);
    pub fn pxf_cb_copy_to_begin(
        rel: pg_sys::Relation,
        options: *mut pg_sys::List,
    ) -> pg_sys::CopyToState;
    pub fn pxf_cb_copy_to_row(
        state: pg_sys::CopyToState,
        slot: *mut pg_sys::TupleTableSlot,
    ) -> pg_sys::StringInfo;
    pub fn pxf_cb_copy_to_end(state: pg_sys::CopyToState);
    pub fn pxf_cb_protocol_call(fcinfo: pg_sys::FunctionCallInfo, out: *mut ProtocolCall) -> bool;
    pub fn pxf_cb_protocol_validate(
        fcinfo: pg_sys::FunctionCallInfo,
        writable: *mut bool,
    ) -> *const c_char;
    pub fn pxf_cb_external_options(
        relation: pg_sys::Relation,
        format: *mut c_char,
        encoding: *mut c_int,
    ) -> *mut pg_sys::List;
}

#[repr(C)]
pub struct FormatterCall {
    pub relation: pg_sys::Relation,
    pub descriptor: pg_sys::TupleDesc,
    pub data: *const c_char,
    pub length: c_int,
    pub eof: bool,
    pub row_context: pg_sys::MemoryContext,
    pub user_context: *mut *mut c_void,
    pub options: *mut pg_sys::List,
    pub encoding: c_int,
}
#[pgrx::pg_guard]
extern "C" {
    pub fn pxf_cb_formatter_call(fcinfo: pg_sys::FunctionCallInfo, out: *mut FormatterCall);
    pub fn pxf_cb_formatter_bad_row(fcinfo: pg_sys::FunctionCallInfo, length: c_int);
    pub fn pxf_cb_formatter_result(
        fcinfo: pg_sys::FunctionCallInfo,
        consumed: c_int,
        tuple: pg_sys::HeapTuple,
    ) -> pg_sys::Datum;
    pub fn pxf_cb_formatter_deform(
        fcinfo: pg_sys::FunctionCallInfo,
        descriptor: pg_sys::TupleDesc,
        values: *mut pg_sys::Datum,
        nulls: *mut bool,
    );
    pub fn pxf_cb_form_tuple(
        descriptor: pg_sys::TupleDesc,
        values: *mut pg_sys::Datum,
        nulls: *mut bool,
    ) -> pg_sys::HeapTuple;
    pub fn pxf_cb_next_copy(
        state: pg_sys::CopyFromState,
        resource: *const c_char,
        slot: *mut pg_sys::TupleTableSlot,
    ) -> bool;
}
