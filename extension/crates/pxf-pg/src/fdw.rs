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
    context::{self, bytes, pstr, transport_result},
    options::{self, FdwOptions},
};
use pgrx::{pg_sys, PgBox};
use pxf_core::{
    request::Metadata,
    transport::{Config, Download, Upload},
};
use std::{ffi::c_void, ptr::null_mut};

pub fn handler() -> PgBox<pg_sys::FdwRoutine> {
    unsafe {
        let mut routine = PgBox::<pg_sys::FdwRoutine>::alloc_node(pg_sys::NodeTag::T_FdwRoutine);
        routine.GetForeignRelSize = Some(rel_size);
        routine.GetForeignPaths = Some(paths);
        routine.GetForeignPlan = Some(plan);
        routine.BeginForeignScan = Some(begin_scan);
        routine.IterateForeignScan = Some(iterate_scan);
        routine.ReScanForeignScan = Some(rescan);
        routine.EndForeignScan = Some(end_scan);
        routine.BeginForeignInsert = Some(begin_insert);
        routine.BeginForeignModify = Some(begin_modify);
        routine.ExecForeignInsert = Some(insert);
        routine.EndForeignInsert = Some(end_insert);
        routine.EndForeignModify = Some(end_insert);
        routine.IsForeignRelUpdatable = Some(updatable);
        routine.into_pg_boxed()
    }
}

#[pgrx::pg_guard]
unsafe extern "C" fn rel_size(
    _root: *mut pg_sys::PlannerInfo,
    rel: *mut pg_sys::RelOptInfo,
    _oid: pg_sys::Oid,
) {
    unsafe {
        (*rel).rows = 1000.0;
    }
}

#[pgrx::pg_guard]
unsafe extern "C" fn paths(
    root: *mut pg_sys::PlannerInfo,
    rel: *mut pg_sys::RelOptInfo,
    _oid: pg_sys::Oid,
) {
    unsafe {
        let path = pg_sys::create_foreignscan_path(
            root,
            rel,
            null_mut(),
            (*rel).rows,
            50000.0,
            51000.0,
            null_mut(),
            null_mut(),
            null_mut(),
            null_mut(),
        );
        pg_sys::add_path(rel, path.cast(), root);
    }
}

#[pgrx::pg_guard]
unsafe extern "C" fn plan(
    _root: *mut pg_sys::PlannerInfo,
    rel: *mut pg_sys::RelOptInfo,
    oid: pg_sys::Oid,
    _path: *mut pg_sys::ForeignPath,
    target: *mut pg_sys::List,
    clauses: *mut pg_sys::List,
    outer: *mut pg_sys::Plan,
) -> *mut pg_sys::ForeignScan {
    unsafe {
        let quals = pg_sys::extract_actual_clauses(clauses, false);
        let relation = pg_sys::table_open(oid, pg_sys::NoLock as i32);
        let options = options::get(oid);
        let filter = if options.disable_ppd {
            None
        } else {
            crate::predicate::serialize(quals, Some((*rel).relid), relation)
        };
        let projection = crate::predicate::projection(target, quals, (*rel).relid, relation);
        pg_sys::table_close(relation, pg_sys::NoLock as i32);
        let mut indexes = null_mut();
        if let Some(projection) = projection {
            for index in projection {
                indexes = pg_sys::lappend_int(indexes, index as i32);
            }
        }
        // Only PostgreSQL nodes cross Cloudberry's coordinator/segment dispatch.
        let private = pg_sys::lappend(
            null_mut(),
            pg_sys::makeString(pstr(filter.as_deref().unwrap_or(b""))).cast(),
        );
        let private = pg_sys::lappend(private, indexes.cast());
        pg_sys::make_foreignscan(
            target,
            quals,
            (*rel).relid,
            null_mut(),
            private,
            null_mut(),
            null_mut(),
            outer,
        )
    }
}

unsafe fn metadata(relation: pg_sys::Relation, options: &FdwOptions) -> Metadata {
    unsafe {
        let mut headers = vec![
            (b"profile".to_vec(), options.profile.clone()),
            (b"server".to_vec(), options.server.clone()),
        ];
        headers.extend(options.headers.clone());
        Metadata {
            context: context::environment(),
            host: options.host.clone(),
            port: options.port.to_string().into_bytes(),
            resource: options.resource.clone(),
            table: Some(bytes((*(*relation).rd_rel).relname.data.as_ptr())),
            schema: Some(bytes(pg_sys::get_namespace_name(
                (*(*relation).rd_rel).relnamespace,
            ))),
            data_encoding: Some(options.data_encoding.clone()),
            wire_format: b"TEXT".to_vec(),
            columns: context::columns(relation, false),
            projection: None,
            filter: None,
            options: headers,
            original_uri: None,
        }
    }
}

fn endpoint(options: &FdwOptions, operation: &str) -> String {
    let host = std::str::from_utf8(&options.host)
        .unwrap_or_else(|_| pgrx::error!("PXF host must be valid UTF-8"));
    format!("http://{host}:{}/pxf/{operation}", options.port)
}

struct Scan {
    relation: pg_sys::Relation,
    options: FdwOptions,
    metadata: Metadata,
    download: Option<Download>,
    copy: pg_sys::CopyFromState,
}

unsafe fn start_scan(state: *mut Scan) {
    unsafe {
        let state_ref = &mut *state;
        let request = transport_result(
            state_ref
                .metadata
                .request(&endpoint(&state_ref.options, "read")),
        );
        state_ref.download = Some(transport_result(Download::open(request, Config::default())));
        let relation = state_ref.relation;
        let options = state_ref.options.copy;
        // COPY can invoke read(), so no Rust reference to Scan may span this call.
        let copy = pg_sys::BeginCopyFrom(
            null_mut(),
            relation,
            null_mut(),
            std::ptr::null(),
            false,
            Some(read),
            state.cast(),
            null_mut(),
            options,
        );
        let state_ref = &mut *state;
        state_ref.copy = copy;
        abi::pxf_cb_copy_from_setup(
            copy,
            state_ref.relation,
            state_ref.options.reject_limit,
            state_ref.options.reject_rows,
            state_ref.options.log_errors,
            pstr(&state_ref.options.resource),
        );
    }
}

#[pgrx::pg_guard]
unsafe extern "C" fn read(
    output: *mut c_void,
    minimum: i32,
    maximum: i32,
    extra: *mut c_void,
) -> i32 {
    unsafe {
        let state = &mut *extra.cast::<Scan>();
        let buffer = std::slice::from_raw_parts_mut(output.cast::<u8>(), maximum as usize);
        let mut count = 0;
        while count < minimum as usize {
            let result = state
                .download
                .as_mut()
                .expect("active PXF scan")
                .read(&mut buffer[count..], &mut context::cancelled);
            let length = transport_result(result);
            if length == 0 {
                break;
            }
            count += length;
        }
        count as i32
    }
}

#[pgrx::pg_guard]
unsafe extern "C" fn begin_scan(node: *mut pg_sys::ForeignScanState, flags: i32) {
    unsafe {
        if flags & pg_sys::EXEC_FLAG_EXPLAIN_ONLY as i32 != 0 {
            return;
        }
        let relation = (*node).ss.ss_currentRelation;
        let options = options::get((*relation).rd_id);
        if options.all_segments && abi::pxf_cb_is_dispatcher() {
            return;
        }
        let mut metadata = metadata(relation, &options);
        let plan = (*node).ss.ps.plan.cast::<pg_sys::ForeignScan>();
        let private = context::list::<pg_sys::Node>((*plan).fdw_private);
        let filter = bytes(abi::pxf_cb_string_value(private[0]));
        if !filter.is_empty() {
            metadata.filter = Some(filter);
        }
        let indexes = private[1].cast::<pg_sys::List>();
        if !indexes.is_null() {
            metadata.projection = Some(
                (0..(*indexes).length as usize)
                    .map(|i| (*(*indexes).elements.add(i)).int_value as usize)
                    .collect(),
            );
        }
        let owner = context::managed(
            (*(*node).ss.ps.state).es_query_cxt,
            Scan {
                relation,
                options,
                metadata,
                download: None,
                copy: null_mut(),
            },
        );
        (*node).fdw_state = owner.cast();
        start_scan((*owner).as_mut().unwrap());
    }
}

#[pgrx::pg_guard]
unsafe extern "C" fn iterate_scan(
    node: *mut pg_sys::ForeignScanState,
) -> *mut pg_sys::TupleTableSlot {
    unsafe {
        let slot = (*node).ss.ss_ScanTupleSlot;
        abi::pxf_cb_clear_slot(slot);
        if (*node).fdw_state.is_null() {
            return slot;
        }
        let state = (*(*node).fdw_state.cast::<Option<Scan>>())
            .as_mut()
            .unwrap();
        let copy = state.copy;
        let resource = pstr(&state.options.resource);
        // The COPY callback re-enters Scan through its raw pointer.
        let found = abi::pxf_cb_next_copy(copy, resource, slot);
        pg_sys::pfree(resource.cast());
        if found {
            abi::pxf_cb_copy_from_count(copy);
            pg_sys::ExecStoreVirtualTuple(slot);
        }
        slot
    }
}

#[pgrx::pg_guard]
unsafe extern "C" fn rescan(node: *mut pg_sys::ForeignScanState) {
    unsafe {
        if (*node).fdw_state.is_null() {
            return;
        }
        let state = (*(*node).fdw_state.cast::<Option<Scan>>())
            .as_mut()
            .unwrap();
        pg_sys::EndCopyFrom(state.copy);
        state.copy = null_mut();
        state.download.take();
        let state = state as *mut Scan;
        start_scan(state);
    }
}

#[pgrx::pg_guard]
unsafe extern "C" fn end_scan(node: *mut pg_sys::ForeignScanState) {
    unsafe {
        if (*node).fdw_state.is_null() {
            return;
        }
        if let Some(mut state) = (*(*node).fdw_state.cast::<Option<Scan>>()).take() {
            state.download.take();
            if !state.copy.is_null() {
                pg_sys::EndCopyFrom(state.copy);
            }
        }
        (*node).fdw_state = null_mut();
    }
}

struct Modify {
    upload: Upload,
    copy: pg_sys::CopyToState,
}

#[pgrx::pg_guard]
unsafe extern "C" fn begin_insert(
    _state: *mut pg_sys::ModifyTableState,
    _info: *mut pg_sys::ResultRelInfo,
) {
}
#[pgrx::pg_guard]
unsafe extern "C" fn begin_modify(
    _state: *mut pg_sys::ModifyTableState,
    _info: *mut pg_sys::ResultRelInfo,
    _private: *mut pg_sys::List,
    _index: i32,
    _flags: i32,
) {
}

#[pgrx::pg_guard]
unsafe extern "C" fn insert(
    estate: *mut pg_sys::EState,
    info: *mut pg_sys::ResultRelInfo,
    slot: *mut pg_sys::TupleTableSlot,
    _plan_slot: *mut pg_sys::TupleTableSlot,
) -> *mut pg_sys::TupleTableSlot {
    unsafe {
        if (*info).ri_FdwState.is_null() {
            let relation = (*info).ri_RelationDesc;
            let options = options::get((*relation).rd_id);
            if options.all_segments && abi::pxf_cb_is_dispatcher() {
                return slot;
            }
            let metadata = metadata(relation, &options);
            let request = transport_result(metadata.request(&endpoint(&options, "write")));
            let upload = transport_result(Upload::open(request, Config::default()));
            let owner = context::managed(
                (*estate).es_query_cxt,
                Modify {
                    upload,
                    copy: null_mut(),
                },
            );
            (*info).ri_FdwState = owner.cast();
            (*owner).as_mut().unwrap().copy = abi::pxf_cb_copy_to_begin(relation, options.copy);
        }
        let state = (*(*info).ri_FdwState.cast::<Option<Modify>>())
            .as_mut()
            .unwrap();
        let buffer = abi::pxf_cb_copy_to_row(state.copy, slot);
        let data = std::slice::from_raw_parts((*buffer).data.cast::<u8>(), (*buffer).len as usize);
        let mut count = 0;
        while count < data.len() {
            count += transport_result(state.upload.write(&data[count..], &mut context::cancelled));
        }
        slot
    }
}

#[pgrx::pg_guard]
unsafe extern "C" fn end_insert(_estate: *mut pg_sys::EState, info: *mut pg_sys::ResultRelInfo) {
    unsafe {
        if (*info).ri_FdwState.is_null() {
            return;
        }
        if let Some(mut state) = (*(*info).ri_FdwState.cast::<Option<Modify>>()).take() {
            let result = state.upload.finish(&mut context::cancelled);
            transport_result(result);
            abi::pxf_cb_copy_to_end(state.copy);
        }
        (*info).ri_FdwState = null_mut();
    }
}

#[pgrx::pg_guard]
unsafe extern "C" fn updatable(_rel: pg_sys::Relation) -> i32 {
    1 << pg_sys::CmdType::CMD_INSERT as i32
}
