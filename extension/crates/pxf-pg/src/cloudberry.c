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
/* ABI helpers for Cloudberry headers/macros absent from pgrx's public bindings.
 * No HTTP, options, query planning or PXF serialization lives here. */
#include "postgres.h"
#include "access/url.h"
#include "access/external.h"
#include "extension/gp_exttable_fdw/extaccess.h"
#include "cdb/cdbvars.h"
#include "cdb/cdbsreh.h"
#include "commands/copy.h"
#include "commands/copyfrom_internal.h"
#include "commands/copyto_internal.h"
#include "executor/tuptable.h"
#include "mb/pg_wchar.h"
#include "utils/lsyscache.h"
#include "utils/memutils.h"
#include "utils/pg_locale.h"

typedef struct PxfEnvironment {
    char *user;
    char *segment_id;
    char *segment_count;
    char *transaction_id;
    int session_id;
    int command_count;
} PxfEnvironment;

void pxf_cb_environment(PxfEnvironment *out) {
    extvar_t ev;
    external_set_env_vars(&ev, "pxf", false, NULL, NULL, false, 0);
    out->user = pstrdup(ev.GP_USER ? ev.GP_USER : "");
    out->segment_id = pstrdup(ev.GP_SEGMENT_ID);
    out->segment_count = pstrdup(ev.GP_SEGMENT_COUNT);
    out->transaction_id = pstrdup(ev.GP_XID);
    out->session_id = gp_session_id;
    out->command_count = gp_command_count;
}

bool pxf_cb_is_dispatcher(void) { return Gp_role == GP_ROLE_DISPATCH; }
const char *pxf_cb_string_value(Node *node) { return strVal(node); }
bool pxf_cb_collation_is_c(Oid collation) { return lc_collate_is_c(collation); }
void pxf_cb_clear_slot(TupleTableSlot *slot) { ExecClearTuple(slot); }

void pxf_cb_copy_from_setup(CopyFromState state, Relation rel, int limit,
                            bool rows, bool log_errors, const char *resource) {
    if (limit < 0) {
        state->cdbsreh = NULL;
        state->errMode = ALL_OR_NOTHING;
    } else {
        state->errMode = log_errors ? SREH_LOG : SREH_IGNORE;
        state->cdbsreh = makeCdbSreh(limit, rows, (char *) resource, (char *) state->cur_relname,
                                   log_errors ? LOG_ERRORS_ENABLE : LOG_ERRORS_DISABLE);
        state->cdbsreh->relid = RelationGetRelid(rel);
    }
    state->fe_msgbuf = makeStringInfo();
    state->rowcontext = AllocSetContextCreate(CurrentMemoryContext, "Pxf Rust COPY row",
                                             ALLOCSET_DEFAULT_SIZES);
}

void pxf_cb_copy_from_count(CopyFromState state) {
    if (state->cdbsreh) state->cdbsreh->processed++;
}

CopyToState pxf_cb_copy_to_begin(Relation rel, List *options) {
    CopyToState state = BeginCopy(NULL, rel, NULL, rel->rd_id, NIL, options, NULL);
    TupleDesc desc = RelationGetDescr(rel);
    ListCell *cell;
    state->dispatch_mode = COPY_DIRECT;
    state->copy_dest = COPY_CALLBACK;
    state->opts.null_print_client = state->opts.null_print;
    if (state->need_transcoding)
        state->opts.null_print_client = pg_server_to_any(state->opts.null_print,
                state->opts.null_print_len, state->opts.file_encoding);
    state->out_functions = palloc0(desc->natts * sizeof(FmgrInfo));
    foreach(cell, state->attnumlist) {
        int number = lfirst_int(cell);
        Oid output;
        bool varlena;
        getTypeOutputInfo(TupleDescAttr(desc, number - 1)->atttypid, &output, &varlena);
        fmgr_info(output, &state->out_functions[number - 1]);
    }
    state->fe_msgbuf = makeStringInfo();
    state->rowcontext = AllocSetContextCreate(CurrentMemoryContext, "Pxf Rust COPY row",
                                             ALLOCSET_DEFAULT_SIZES);
    return state;
}

StringInfo pxf_cb_copy_to_row(CopyToState state, TupleTableSlot *slot) {
    resetStringInfo(state->fe_msgbuf);
    slot_getallattrs(slot);
    CopyOneRowTo(state, slot);
    CopySendEndOfRow(state);
    return state->fe_msgbuf;
}

void pxf_cb_copy_to_end(CopyToState state) {
    MemoryContextDelete(state->copycontext);
    pfree(state);
}

typedef struct PxfProtocolCall {
    Relation relation;
    const char *url;
    char *buffer;
    int length;
    void **user_context;
    bool last_call;
    List *quals;
    List *target;
} PxfProtocolCall;

bool pxf_cb_protocol_call(FunctionCallInfo fcinfo, PxfProtocolCall *out) {
    ExtProtocolData *data;
    if (!CALLED_AS_EXTPROTOCOL(fcinfo)) return false;
    data = (ExtProtocolData *) fcinfo->context;
    out->relation = data->prot_relation;
    out->url = data->prot_url;
    out->buffer = data->prot_databuf;
    out->length = data->prot_maxbytes;
    out->user_context = &data->prot_user_ctx;
    out->last_call = data->prot_last_call;
    out->quals = data->desc ? data->desc->filter_quals : NIL;
    out->target = data->desc && data->desc->projInfo ? (List *) data->desc->projInfo->pi_state.expr : NIL;
    return true;
}

const char *pxf_cb_protocol_validate(FunctionCallInfo fcinfo, bool *writable) {
    if (!CALLED_AS_EXTPROTOCOL_VALIDATOR(fcinfo))
        elog(ERROR, "cannot execute pxfprotocol_validate_urls outside protocol manager");
    if (EXTPROTOCOL_VALIDATOR_GET_NUM_URLS(fcinfo) != 1)
        ereport(ERROR, (errcode(ERRCODE_PROTOCOL_VIOLATION), errmsg("number of URLs must be one")));
    *writable = EXTPROTOCOL_VALIDATOR_GET_DIRECTION(fcinfo) == EXT_VALIDATE_WRITE;
    return EXTPROTOCOL_VALIDATOR_GET_NTH_URL(fcinfo, 1);
}

List *pxf_cb_external_options(Relation relation, char *format, int *encoding) {
    ExtTableEntry *entry = GetExtTableEntry(relation->rd_id);
    *format = entry->fmtcode;
    *encoding = entry->encoding;
    return entry->options;
}

#include "access/formatter.h"
#include "funcapi.h"

typedef struct PxfFormatterCall
{
    Relation relation;
    TupleDesc descriptor;
    const char *data;
    int length;
    bool eof;
    MemoryContext row_context;
    void **user_context;
    List *options;
    int encoding;
} PxfFormatterCall;

void
pxf_cb_formatter_call(FunctionCallInfo fcinfo, PxfFormatterCall *out)
{
    if (!CALLED_AS_FORMATTER(fcinfo))
        ereport(ERROR, (errmsg("cannot execute PXF formatter outside format manager")));
    FormatterData *fmt = (FormatterData *) fcinfo->context;
    out->relation = fmt->fmt_relation;
    out->descriptor = fmt->fmt_tupDesc;
    out->data = fmt->fmt_databuf.data + fmt->fmt_databuf.cursor;
    out->length = fmt->fmt_databuf.len - fmt->fmt_databuf.cursor;
    out->eof = fmt->fmt_saw_eof;
    out->row_context = fmt->fmt_perrow_ctx;
    out->user_context = &fmt->fmt_user_ctx;
    out->options = fmt->fmt_args;
    out->encoding = fmt->fmt_user_ctx ? -1 : GetExtTableEntry(fmt->fmt_relation->rd_id)->encoding;
}

void
pxf_cb_formatter_bad_row(FunctionCallInfo fcinfo, int length)
{
    FormatterData *fmt = (FormatterData *) fcinfo->context;
    FORMATTER_SET_BAD_ROW_DATA(fcinfo, fmt->fmt_databuf.data + fmt->fmt_databuf.cursor, length);
}

Datum
pxf_cb_formatter_result(FunctionCallInfo fcinfo, int consumed, HeapTuple tuple)
{
    FormatterData *fmt = (FormatterData *) fcinfo->context;
    if (tuple == NULL)
    {
        fmt->fmt_notification = FMT_NEED_MORE_DATA;
        PG_RETURN_NULL();
    }
    fmt->fmt_databuf.cursor += consumed;
    fmt->fmt_tuple = tuple;
    return HeapTupleGetDatum(tuple);
}

void
pxf_cb_formatter_deform(FunctionCallInfo fcinfo, TupleDesc descriptor, Datum *values, bool *nulls)
{
    HeapTupleHeader record = PG_GETARG_HEAPTUPLEHEADER(0);
    HeapTupleData tuple;
    tuple.t_len = HeapTupleHeaderGetDatumLength(record);
    ItemPointerSetInvalid(&tuple.t_self);
    tuple.t_data = record;
    heap_deform_tuple(&tuple, descriptor, values, nulls);
}

HeapTuple
pxf_cb_form_tuple(TupleDesc descriptor, Datum *values, bool *nulls)
{
    return heap_form_tuple(descriptor, values, nulls);
}

/* COPY owns these fields. Keep error reporting within PostgreSQL's error stack,
 * including SREH errors caught internally by NextCopyFrom. */
typedef struct PxfCopyErrorContext {
    CopyFromState state;
    const char *resource;
} PxfCopyErrorContext;

static void
pxf_cb_copy_error(void *arg)
{
    PxfCopyErrorContext *context = arg;
    CopyFromState state = context->state;
    const char *message = NULL;
    if (!state->cur_attname && state->line_buf.data)
        message = strstr(state->line_buf.data, "PXFERRMSG> ");
    if (message)
        errmsg("%s", message + strlen("PXFERRMSG> "));
    if (state->cur_attname && state->cur_attval)
    {
        char *value = limit_printout_length(state->cur_attval);
        errcontext("Foreign table %s, record " UINT64_FORMAT " of %s, column %s: \"%s\"",
                   state->cur_relname, state->cur_lineno, context->resource, state->cur_attname, value);
        pfree(value);
    }
    else if (state->cur_attname)
        errcontext("Foreign table %s, record " UINT64_FORMAT " of %s, column %s: null input",
                   state->cur_relname, state->cur_lineno, context->resource, state->cur_attname);
    else
        errcontext("Foreign table %s, record " UINT64_FORMAT " of %s",
                   state->cur_relname, state->cur_lineno, context->resource);
}

bool
pxf_cb_next_copy(CopyFromState state, const char *resource, TupleTableSlot *slot)
{
    PxfCopyErrorContext context = { state, resource };
    ErrorContextCallback callback = {0};
    bool found = false;
    callback.callback = pxf_cb_copy_error;
    callback.arg = &context;
    callback.previous = error_context_stack;
    error_context_stack = &callback;
    PG_TRY();
    {
        found = NextCopyFrom(state, NULL, slot->tts_values, slot->tts_isnull);
    }
    PG_FINALLY();
    {
        error_context_stack = callback.previous;
    }
    PG_END_TRY();
    return found;
}
