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
use crate::{
    abi,
    context::{self, bytes, pstr},
};
use pgrx::{pg_sys, PgMemoryContexts};
use pxf_core::{
    delimited,
    writable::{self, Type},
};
use std::ptr::null_mut;

struct Column {
    index: usize,
    kind: Type,
    io: pg_sys::FmgrInfo,
    parameter: pg_sys::Oid,
    modifier: i32,
}
struct State {
    encoding: i32,
    columns: Vec<Column>,
    delimited: Option<delimited::Config>,
}

unsafe fn state(call: &abi::FormatterCall, export: bool, delimited: bool) -> *mut State {
    unsafe {
        let mut owner = (*call.user_context).cast::<Option<State>>();
        if owner.is_null() {
            if !delimited && call.encoding != pg_sys::pg_enc::PG_UTF8 as i32 {
                pgrx::error!("gpdbwritable formatter can only {} UTF8 formatted data. Define the external table with ENCODING UTF8", if export {"export"} else {"import"});
            }
            let mut columns = Vec::new();
            for index in 0..(*call.descriptor).natts as usize {
                let attr = &*(*call.descriptor).attrs.as_ptr().add(index);
                if attr.attisdropped {
                    continue;
                }
                let kind = Type::from_oid(attr.atttypid.as_u32());
                let mut function = pg_sys::InvalidOid;
                let mut parameter = pg_sys::InvalidOid;
                let mut varlena = false;
                if export {
                    if kind == Type::Text {
                        pg_sys::getTypeOutputInfo(attr.atttypid, &mut function, &mut varlena);
                    } else {
                        pg_sys::getTypeBinaryOutputInfo(attr.atttypid, &mut function, &mut varlena);
                    }
                } else if kind == Type::Text || delimited {
                    pg_sys::getTypeInputInfo(attr.atttypid, &mut function, &mut parameter);
                } else {
                    pg_sys::getTypeBinaryInputInfo(attr.atttypid, &mut function, &mut parameter);
                }
                let mut io = std::mem::zeroed();
                pg_sys::fmgr_info(function, &mut io);
                columns.push(Column {
                    index,
                    kind,
                    io,
                    parameter,
                    modifier: attr.atttypmod,
                });
            }
            let config = delimited.then(|| delimited_config(call));
            owner = context::managed(
                pg_sys::CurrentMemoryContext,
                State {
                    encoding: call.encoding,
                    columns,
                    delimited: config,
                },
            );
            *call.user_context = owner.cast();
        }
        (*owner).as_mut().unwrap() as *mut State
    }
}
unsafe fn delimited_config(call: &abi::FormatterCall) -> delimited::Config {
    unsafe {
        let mut config = delimited::Config {
            delimiter: Vec::new(),
            newline: b"\n".to_vec(),
            quote: None,
            escape: None,
        };
        for def in context::list::<pg_sys::DefElem>(call.options) {
            let name = bytes((*def).defname);
            let value = bytes(pg_sys::defGetString(def));
            match name.as_slice() {
                b"delimiter" => {
                    let input = pstr(&value);
                    config.delimiter = bytes(pg_sys::pg_server_to_any(
                        input,
                        value.len() as i32,
                        call.encoding,
                    ));
                }
                b"newline" => {
                    config.newline = if value.eq_ignore_ascii_case(b"lf") {
                        b"\n".to_vec()
                    } else if value.eq_ignore_ascii_case(b"cr") {
                        b"\r".to_vec()
                    } else if value.eq_ignore_ascii_case(b"crlf") {
                        b"\r\n".to_vec()
                    } else {
                        pgrx::error!("NEWLINE can only be LF, CRLF, or CR")
                    };
                }
                b"quote" | b"escape" => {
                    if value.len() != 1 {
                        pgrx::error!(
                            "{} must be a single one-byte character",
                            String::from_utf8_lossy(&name)
                        );
                    }
                    if name == b"quote" {
                        config.quote = Some(value[0]);
                    } else {
                        config.escape = Some(value[0]);
                    }
                }
                _ => {}
            }
        }
        if config.escape.is_none() {
            config.escape = config.quote;
        }
        config.validate().unwrap_or_else(|e| pgrx::error!("{}", e));
        config
    }
}
unsafe fn call(fcinfo: pg_sys::FunctionCallInfo) -> abi::FormatterCall {
    unsafe {
        let mut call = std::mem::zeroed();
        abi::pxf_cb_formatter_call(fcinfo, &mut call);
        call
    }
}
fn format_error(error: impl std::fmt::Display) -> ! {
    pgrx::ereport!(
        pgrx::PgLogLevel::ERROR,
        pgrx::PgSqlErrorCode::ERRCODE_DATA_EXCEPTION,
        error.to_string()
    );
    unreachable!()
}

/// # Safety
/// fcinfo must be a live Cloudberry import formatter invocation; its input buffer,
/// descriptor and per-row context must remain valid for this call.
pub unsafe fn import(fcinfo: pg_sys::FunctionCallInfo, delimited: bool) -> Option<pg_sys::Datum> {
    unsafe {
        let call = call(fcinfo);
        let state = &mut *state(&call, false, delimited);
        if call.length < 0 {
            format_error("negative formatter buffer length");
        }
        let input = if call.length == 0 {
            &[][..]
        } else {
            std::slice::from_raw_parts(call.data.cast::<u8>(), call.length as usize)
        };
        // Mark only this frame for SREH before calling any PostgreSQL input function.
        let (values, consumed) = if let Some(config) = &state.delimited {
            match config.decode(input) {
                Ok(Some(record)) => (record.fields, record.consumed),
                Ok(None) => return incomplete(fcinfo, &call),
                Err(e) => {
                    abi::pxf_cb_formatter_bad_row(fcinfo, call.length);
                    format_error(e)
                }
            }
        } else {
            if let Ok(Some(length)) = writable::frame_length(input) {
                abi::pxf_cb_formatter_bad_row(fcinfo, length.min(input.len()) as i32);
            }
            match writable::decode(input) {
                Ok(Some(record)) => {
                    if record.types.len() != state.columns.len() {
                        format_error(
                            "input data column count did not match the external table definition",
                        );
                    }
                    for (kind, column) in record.types.iter().zip(&state.columns) {
                        if *kind != column.kind {
                            format_error(format!(
                                "external table definition did not match input type in column {}",
                                column.index + 1
                            ));
                        }
                    }
                    (
                        record
                            .values
                            .into_iter()
                            .map(|v| v.map(<[u8]>::to_vec))
                            .collect(),
                        record.consumed,
                    )
                }
                Ok(None) => return incomplete(fcinfo, &call),
                Err(e) => format_error(e),
            }
        };
        abi::pxf_cb_formatter_bad_row(fcinfo, consumed as i32);
        if values.len() != state.columns.len() {
            format_error(format!(
                "Expected {} columns in row but found {}",
                state.columns.len(),
                values.len()
            ));
        }
        let (mut datums, mut nulls) = PgMemoryContexts::For(call.row_context).switch_to(|_| {
            let mut datums = vec![pg_sys::Datum::from(0usize); (*call.descriptor).natts as usize];
            let mut nulls = vec![true; datums.len()];
            for (column, value) in state.columns.iter_mut().zip(&values) {
                let Some(value) = value else { continue };
                datums[column.index] = if column.kind != Type::Text && !delimited {
                    let mut buffer = pg_sys::StringInfoData {
                        data: value.as_ptr().cast_mut().cast(),
                        len: value.len() as i32,
                        maxlen: value.len() as i32,
                        cursor: 0,
                    };
                    pg_sys::ReceiveFunctionCall(
                        &mut column.io,
                        &mut buffer,
                        column.parameter,
                        column.modifier,
                    )
                } else {
                    let raw = if delimited {
                        value.as_slice()
                    } else {
                        &value[..value.len() - 1]
                    };
                    if raw.contains(&0) {
                        format_error("NUL byte in text field");
                    }
                    let text = pstr(raw);
                    let converted =
                        pg_sys::pg_any_to_server(text, raw.len() as i32, state.encoding);
                    pg_sys::InputFunctionCall(
                        &mut column.io,
                        converted,
                        column.parameter,
                        column.modifier,
                    )
                };
                nulls[column.index] = false;
            }
            (datums, nulls)
        });
        // Cloudberry resets fmt_perrow_ctx before handing the tuple to the
        // executor. The formed tuple must belong to the caller context.
        let tuple =
            abi::pxf_cb_form_tuple(call.descriptor, datums.as_mut_ptr(), nulls.as_mut_ptr());
        Some(abi::pxf_cb_formatter_result(fcinfo, consumed as i32, tuple))
    }
}
unsafe fn incomplete(
    fcinfo: pg_sys::FunctionCallInfo,
    call: &abi::FormatterCall,
) -> Option<pg_sys::Datum> {
    unsafe {
        if call.eof && call.length > 0 {
            abi::pxf_cb_formatter_bad_row(fcinfo, call.length);
            format_error("unexpected end of file");
        }
        abi::pxf_cb_formatter_result(fcinfo, 0, null_mut());
        None
    }
}

/// # Safety
/// fcinfo must be a live Cloudberry export formatter invocation with a record
/// argument matching its tuple descriptor.
pub unsafe fn export(fcinfo: pg_sys::FunctionCallInfo) -> Vec<u8> {
    unsafe {
        let call = call(fcinfo);
        let state = &mut *state(&call, true, false);
        PgMemoryContexts::For(call.row_context).switch_to(|_| {
            let mut datums = vec![pg_sys::Datum::from(0usize); (*call.descriptor).natts as usize];
            let mut nulls = vec![true; datums.len()];
            abi::pxf_cb_formatter_deform(
                fcinfo,
                call.descriptor,
                datums.as_mut_ptr(),
                nulls.as_mut_ptr(),
            );
            let mut values = Vec::with_capacity(state.columns.len());
            let mut types = Vec::with_capacity(state.columns.len());
            for column in &mut state.columns {
                types.push(column.kind);
                if nulls[column.index] {
                    values.push(None);
                    continue;
                }
                let value = if column.kind == Type::Text {
                    let output = pg_sys::OutputFunctionCall(&mut column.io, datums[column.index]);
                    let length = std::ffi::CStr::from_ptr(output).to_bytes().len();
                    let converted = pg_sys::pg_server_to_any(output, length as i32, state.encoding);
                    let mut output = bytes(converted);
                    output.push(0);
                    output
                } else {
                    let output = pg_sys::SendFunctionCall(&mut column.io, datums[column.index]);
                    std::slice::from_raw_parts(
                        pgrx::vardata_any(output).cast::<u8>(),
                        pgrx::varsize_any_exhdr(output),
                    )
                    .to_vec()
                };
                values.push(Some(value));
            }
            writable::encode(&types, &values).unwrap_or_else(|e| format_error(e))
        })
    }
}
