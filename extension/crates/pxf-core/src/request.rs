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

//! PXF metadata independent of database pointers and database encoding.

use crate::transport::{Request, TransportError};

pub type Options = Vec<(Vec<u8>, Vec<u8>)>;

#[derive(Debug, Clone)]
pub struct Column {
    pub name: Vec<u8>,
    pub type_oid: u32,
    pub type_name: Vec<u8>,
    pub modifiers: Vec<i32>,
}

#[derive(Debug, Clone)]
pub struct Context {
    pub user: Vec<u8>,
    pub segment_id: Vec<u8>,
    pub segment_count: Vec<u8>,
    pub transaction_id: Vec<u8>,
    pub session_id: i32,
    pub command_count: i32,
    pub database_encoding: Vec<u8>,
    pub alignment: usize,
}

#[derive(Debug, Clone)]
pub struct Metadata {
    pub context: Context,
    pub host: Vec<u8>,
    pub port: Vec<u8>,
    pub resource: Vec<u8>,
    pub table: Option<Vec<u8>>,
    pub schema: Option<Vec<u8>>,
    pub data_encoding: Option<Vec<u8>>,
    pub wire_format: Vec<u8>,
    /// Dense live-column list: dropped PostgreSQL attributes are omitted.
    pub columns: Vec<Column>,
    /// Zero-based indexes into columns, including columns required by local quals.
    pub projection: Option<Vec<usize>>,
    pub filter: Option<Vec<u8>>,
    pub options: Options,
    /// Only external tables send X-GP-URI.
    pub original_uri: Option<Vec<u8>>,
}

impl Metadata {
    pub fn request(&self, endpoint: &str) -> Result<Request, TransportError> {
        if self.context.user.is_empty() {
            return Err(TransportError::InvalidRequest(
                "user identity is unknown".into(),
            ));
        }
        let mut request = Request::new(endpoint)?;
        for (name, value) in [
            ("X-GP-ENCODED-HEADER-VALUES", b"true".as_slice()),
            ("X-GP-USER", &self.context.user),
            ("X-GP-SEGMENT-ID", &self.context.segment_id),
            ("X-GP-SEGMENT-COUNT", &self.context.segment_count),
            ("X-GP-XID", &self.context.transaction_id),
            ("X-GP-PXF-API-VERSION", crate::API_VERSION.as_bytes()),
            ("X-GP-DATABASE-ENCODING", &self.context.database_encoding),
            ("X-GP-URL-HOST", &self.host),
            ("X-GP-URL-PORT", &self.port),
            ("X-GP-FORMAT", &self.wire_format),
            ("X-GP-DATA-DIR", &self.resource),
        ] {
            request.header(name, Some(value))?;
        }
        for (name, value) in [
            ("X-GP-SESSION-ID", self.context.session_id.to_string()),
            ("X-GP-COMMAND-COUNT", self.context.command_count.to_string()),
            ("X-GP-ALIGNMENT", self.context.alignment.to_string()),
            ("X-GP-ATTRS", self.columns.len().to_string()),
        ] {
            request.header(name, Some(value.as_bytes()))?;
        }
        for (name, value) in [
            ("X-GP-TABLE-NAME", &self.table),
            ("X-GP-SCHEMA-NAME", &self.schema),
            ("X-GP-DATA-ENCODING", &self.data_encoding),
            ("X-GP-URI", &self.original_uri),
        ] {
            request.header(name, value.as_deref())?;
        }
        for (index, column) in self.columns.iter().enumerate() {
            request.header(&format!("X-GP-ATTR-NAME{index}"), Some(&column.name))?;
            request.header(
                &format!("X-GP-ATTR-TYPECODE{index}"),
                Some(column.type_oid.to_string().as_bytes()),
            )?;
            request.header(
                &format!("X-GP-ATTR-TYPENAME{index}"),
                Some(&column.type_name),
            )?;
            if !column.modifiers.is_empty() {
                request.header(
                    &format!("X-GP-ATTR-TYPEMOD{index}-COUNT"),
                    Some(column.modifiers.len().to_string().as_bytes()),
                )?;
                for (modifier_index, value) in column.modifiers.iter().enumerate() {
                    request.header(
                        &format!("X-GP-ATTR-TYPEMOD{index}-{modifier_index}"),
                        Some(value.to_string().as_bytes()),
                    )?;
                }
            }
        }
        if let Some(projection) = &self.projection {
            if !projection.is_empty() {
                request.header(
                    "X-GP-ATTRS-PROJ",
                    Some(projection.len().to_string().as_bytes()),
                )?;
                for index in projection {
                    if *index >= self.columns.len() {
                        return Err(TransportError::InvalidRequest(
                            "projection index outside tuple descriptor".into(),
                        ));
                    }
                    request.append_header("X-GP-ATTRS-PROJ-IDX", index.to_string().as_bytes())?;
                }
            }
        }
        for (name, value) in &self.options {
            let name = crate::headers::option_name(name)
                .map_err(|e| TransportError::InvalidRequest(e.to_string()))?;
            request.append_header(&name, value)?;
        }
        request.header(
            "X-GP-HAS-FILTER",
            Some(if self.filter.is_some() { b"1" } else { b"0" }),
        )?;
        request.header("X-GP-FILTER", self.filter.as_deref())?;
        Ok(request)
    }
}
