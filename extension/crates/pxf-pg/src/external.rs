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
    context::{self, bytes, list, transport_result},
};
use pgrx::pg_sys;
use pxf_core::{
    request::Metadata,
    transport::{Config, Download, Request, Upload},
    uri::{Direction, Location},
};
use std::ptr::null_mut;

pub fn address(operation: &str) -> String {
    let host = std::env::var("PXF_HOST").unwrap_or_else(|_| "localhost".into());
    let port = std::env::var("PXF_PORT").unwrap_or_else(|_| "5888".into());
    format!("http://{host}:{port}/pxf/{operation}")
}

/// # Safety
/// fcinfo must be a live external protocol validator invocation on the backend thread.
pub unsafe fn validate(fcinfo: pg_sys::FunctionCallInfo) {
    unsafe {
        let mut writable = false;
        let location = bytes(abi::pxf_cb_protocol_validate(fcinfo, &mut writable));
        checked_location(&location, writable);
    }
}

fn checked_location(input: &[u8], writable: bool) -> Location {
    let fail = |e: pxf_core::uri::LocationError| -> ! {
        crate::options::fail(
            pgrx::PgSqlErrorCode::ERRCODE_SYNTAX_ERROR,
            e.describe(input),
        )
    };
    let location = Location::parse(input).unwrap_or_else(|e| fail(e));
    location
        .validate(if writable {
            Direction::Write
        } else {
            Direction::Read
        })
        .unwrap_or_else(|e| fail(e));
    location
}

enum Transfer {
    Read(Download),
    Write(Upload),
}

unsafe fn metadata(call: &abi::ProtocolCall, location: Location) -> Metadata {
    unsafe {
        let relation = call.relation;
        let mut format = 0;
        let mut encoding = 0;
        let copy_options = abi::pxf_cb_external_options(relation, &mut format, &mut encoding);
        let format = format.to_ne_bytes()[0];
        let mut options = Vec::new();
        let mut wire_format = b"TEXT".to_vec();
        for def in list::<pg_sys::DefElem>(copy_options) {
            let name = bytes((*def).defname);
            let value = bytes(pg_sys::defGetString(def));
            if format == b'b' && name == b"formatter" {
                if value
                    .windows(b"pxfwritable_".len())
                    .any(|w| w == b"pxfwritable_")
                {
                    wire_format = b"GPDBWritable".to_vec();
                }
                if value
                    .windows(b"pxfdelimited_import".len())
                    .any(|w| w == b"pxfdelimited_import")
                {
                    let profile = location
                        .options
                        .iter()
                        .find(|o| o.name.eq_ignore_ascii_case(b"profile"));
                    if !profile.is_some_and(|p| {
                        p.value.windows(5).any(|w| w == b":text")
                            || p.value.windows(4).any(|w| w == b":csv")
                    }) {
                        pgrx::error!("The \"pxfdelimited_import\" formatter only works with *:text or *:csv profiles.");
                    }
                }
            }
            if matches!(format, b't' | b'c') && name != b"encoding" {
                options.push((name, value));
            }
        }
        options.extend(location.options.into_iter().map(|o| (o.name, o.value)));
        Metadata {
            context: context::environment(),
            host: std::env::var("PXF_HOST")
                .unwrap_or_else(|_| "localhost".into())
                .into_bytes(),
            port: std::env::var("PXF_PORT")
                .unwrap_or_else(|_| "5888".into())
                .into_bytes(),
            resource: location.resource,
            table: Some(bytes((*(*relation).rd_rel).relname.data.as_ptr())),
            schema: Some(bytes(pg_sys::get_namespace_name(
                (*(*relation).rd_rel).relnamespace,
            ))),
            data_encoding: Some(bytes(pg_sys::pg_encoding_to_char(encoding))),
            wire_format,
            columns: context::columns(relation, true),
            projection: crate::predicate::external_projection(call.target, call.quals, relation),
            filter: crate::predicate::serialize(call.quals, None, relation),
            options,
            original_uri: Some(location.original),
        }
    }
}

/// # Safety
/// fcinfo must be a live external protocol invocation; its protocol memory context
/// must remain valid until the final callback or abort.
pub unsafe fn transfer(fcinfo: pg_sys::FunctionCallInfo, writable: bool) -> i32 {
    unsafe {
        let mut call: abi::ProtocolCall = std::mem::zeroed();
        if !abi::pxf_cb_protocol_call(fcinfo, &mut call) {
            pgrx::error!("PXF function not called by external protocol manager");
        }
        let mut owner = (*call.user_context).cast::<Option<Transfer>>();
        if call.last_call {
            if !owner.is_null() {
                if let Some(Transfer::Write(mut upload)) = (*owner).take() {
                    transport_result(upload.finish(&mut context::cancelled));
                }
            }
            *call.user_context = null_mut();
            return 0;
        }
        if owner.is_null() {
            let location = checked_location(&bytes(call.url), writable);
            let metadata = metadata(&call, location);
            let mut endpoint = address(if writable { "write" } else { "read" });
            if pg_sys::LOG as i32 >= pg_sys::log_min_messages
                || pg_sys::LOG as i32 >= pg_sys::client_min_messages
            {
                endpoint.push_str("?trace=true");
            }
            let request = transport_result(metadata.request(&endpoint));
            let transfer = if writable {
                Transfer::Write(transport_result(Upload::open(request, Config::default())))
            } else {
                Transfer::Read(transport_result(Download::open(request, Config::default())))
            };
            owner = context::managed(pg_sys::CurrentMemoryContext, transfer);
            *call.user_context = owner.cast();
        }
        if call.length < 0 {
            pgrx::error!("negative PXF protocol buffer length");
        }
        if call.length == 0 {
            return 0;
        }
        let buffer = std::slice::from_raw_parts_mut(call.buffer.cast::<u8>(), call.length as usize);
        let mut count = 0;
        match (*owner).as_mut().expect("active protocol transfer") {
            Transfer::Read(download) => {
                while count < buffer.len() {
                    let length = transport_result(
                        download.read(&mut buffer[count..], &mut context::cancelled),
                    );
                    if length == 0 {
                        break;
                    }
                    count += length;
                }
            }
            Transfer::Write(upload) => {
                while count < buffer.len() {
                    count +=
                        transport_result(upload.write(&buffer[count..], &mut context::cancelled));
                }
            }
        }
        count as i32
    }
}

pub fn activity(operation: &str, session: Option<i32>) -> String {
    let environment = unsafe { context::environment() };
    let mut request = transport_result(Request::new(address(operation)));
    transport_result(request.header("X-GP-SEGMENT-ID", Some(&environment.segment_id)));
    transport_result(request.header("Accept", Some(b"application/json")));
    if let Some(session) = session {
        transport_result(request.header("X-GP-SESSION-ID", Some(session.to_string().as_bytes())));
    }
    let mut download = transport_result(Download::open(request, Config::default()));
    let mut output = Vec::new();
    let mut buffer = [0; 8192];
    loop {
        let count = transport_result(download.read(&mut buffer, &mut context::cancelled));
        if count == 0 {
            break;
        }
        output.extend_from_slice(&buffer[..count]);
    }
    String::from_utf8(output).unwrap_or_else(|_| pgrx::error!("PXF returned invalid UTF-8 JSON"))
}
