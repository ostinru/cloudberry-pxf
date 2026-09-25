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
//! GPDBWritable v1/v2 framing. All offsets are relative to the current record.
use std::fmt;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Error(pub String);
impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}
impl std::error::Error for Error {}
fn invalid(message: &str) -> Error {
    Error(message.into())
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum Type {
    Int8,
    Bool,
    Float8,
    Int4,
    Float4,
    Int2,
    Bytea,
    Text,
}
impl Type {
    pub fn from_oid(oid: u32) -> Self {
        match oid {
            20 => Self::Int8,
            16 => Self::Bool,
            701 => Self::Float8,
            23 => Self::Int4,
            700 => Self::Float4,
            21 => Self::Int2,
            17 => Self::Bytea,
            _ => Self::Text,
        }
    }
    fn from_byte(value: u8) -> Result<Self, Error> {
        [
            Self::Int8,
            Self::Bool,
            Self::Float8,
            Self::Int4,
            Self::Float4,
            Self::Int2,
            Self::Bytea,
            Self::Text,
        ]
        .get(value as usize)
        .copied()
        .ok_or_else(|| invalid("unknown GPDBWritable column type"))
    }
    fn width(self) -> Option<usize> {
        match self {
            Self::Int8 | Self::Float8 => Some(8),
            Self::Bool => Some(1),
            Self::Int4 | Self::Float4 => Some(4),
            Self::Int2 => Some(2),
            _ => None,
        }
    }
    fn alignment(self) -> usize {
        self.width().unwrap_or(4)
    }
}
fn align(offset: usize, alignment: usize) -> usize {
    (offset + alignment - 1) & !(alignment - 1)
}

#[derive(Debug, PartialEq)]
pub struct Record<'a> {
    pub types: Vec<Type>,
    pub values: Vec<Option<&'a [u8]>>,
    pub consumed: usize,
}
/// The database limit also bounds incomplete frames, before allocating their body.
pub const MAX_RECORD: usize = 0x3fff_ffff;
pub fn frame_length(input: &[u8]) -> Result<Option<usize>, Error> {
    if input.len() < 4 {
        return Ok(None);
    }
    let len = u32::from_be_bytes(input[..4].try_into().unwrap()) as usize;
    if !(8..=MAX_RECORD).contains(&len) {
        return Err(invalid("invalid GPDBWritable record length"));
    }
    Ok(Some(len))
}
pub fn decode(input: &[u8]) -> Result<Option<Record<'_>>, Error> {
    let Some(len) = frame_length(input)? else {
        return Ok(None);
    };
    if input.len() < len {
        return Ok(None);
    }
    let input = &input[..len];
    let mut cursor = Cursor { input, offset: 4 };
    let version = u16::from_be_bytes(cursor.take(2)?.try_into().unwrap());
    let error = match version {
        1 => false,
        2 => cursor.take(1)?[0] != 0,
        _ => return Err(invalid("unsupported GPDBWritable version")),
    };
    let count = u16::from_be_bytes(cursor.take(2)?.try_into().unwrap()) as usize;
    let types = cursor
        .take(count)?
        .iter()
        .map(|&t| Type::from_byte(t))
        .collect::<Result<Vec<_>, _>>()?;
    let nulls = cursor.take(count.div_ceil(8))?;
    let mut values = Vec::with_capacity(count);
    for (index, &kind) in types.iter().enumerate() {
        if nulls[index / 8] & (1 << (7 - index % 8)) != 0 {
            values.push(None);
            continue;
        }
        cursor.offset = align(cursor.offset, kind.alignment());
        let size = match kind.width() {
            Some(n) => n,
            None => u32::from_be_bytes(cursor.take(4)?.try_into().unwrap()) as usize,
        };
        let value = cursor.take(size)?;
        if kind == Type::Text && value.last() != Some(&0) {
            return Err(invalid("GPDBWritable text is not NUL terminated"));
        }
        values.push(Some(value));
    }
    if align(cursor.offset, 8) != len {
        return Err(invalid(
            "GPDBWritable record length does not match its fields",
        ));
    }
    if error {
        let message = values
            .first()
            .and_then(|v| *v)
            .unwrap_or(b"PXF data error\0");
        return Err(Error(
            String::from_utf8_lossy(message.split(|&b| b == 0).next().unwrap()).into_owned(),
        ));
    }
    Ok(Some(Record {
        types,
        values,
        consumed: len,
    }))
}
struct Cursor<'a> {
    input: &'a [u8],
    offset: usize,
}
impl<'a> Cursor<'a> {
    fn take(&mut self, length: usize) -> Result<&'a [u8], Error> {
        let end = self
            .offset
            .checked_add(length)
            .ok_or_else(|| invalid("GPDBWritable field length overflow"))?;
        let value = self
            .input
            .get(self.offset..end)
            .ok_or_else(|| invalid("GPDBWritable field extends beyond record"))?;
        self.offset = end;
        Ok(value)
    }
}

pub fn encode(types: &[Type], values: &[Option<Vec<u8>>]) -> Result<Vec<u8>, Error> {
    if types.len() != values.len() || types.len() > i16::MAX as usize {
        return Err(invalid("invalid GPDBWritable column count"));
    }
    let mut output = vec![0; 4];
    output.extend_from_slice(&2u16.to_be_bytes());
    output.push(0);
    output.extend_from_slice(&(types.len() as u16).to_be_bytes());
    output.extend(types.iter().map(|&t| t as u8));
    let null_offset = output.len();
    output.resize(null_offset + types.len().div_ceil(8), 0);
    for (index, (&kind, value)) in types.iter().zip(values).enumerate() {
        let Some(value) = value else {
            output[null_offset + index / 8] |= 1 << (7 - index % 8);
            continue;
        };
        output.resize(align(output.len(), kind.alignment()), 0);
        if let Some(width) = kind.width() {
            if value.len() != width {
                return Err(invalid("invalid fixed-width GPDBWritable value"));
            }
        } else {
            let length = u32::try_from(value.len())
                .map_err(|_| invalid("GPDBWritable value is too large"))?;
            output.extend_from_slice(&length.to_be_bytes());
        }
        if kind == Type::Text && value.last() != Some(&0) {
            return Err(invalid("GPDBWritable text is not NUL terminated"));
        }
        if output.len().saturating_add(value.len()) > MAX_RECORD {
            return Err(invalid("GPDBWritable record is too large"));
        }
        output.extend_from_slice(value);
    }
    output.resize(align(output.len(), 8), 0);
    let len = output.len() as u32;
    output[..4].copy_from_slice(&len.to_be_bytes());
    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn java_wire_fixture_and_every_chunk_boundary() {
        // Header, two type ordinals, null bitmap, aligned int4 then NUL-terminated text.
        let expected = b"\0\0\0\x18\0\x02\0\0\x02\x03\x07\0\0\0\0\x2a\0\0\0\x04abc\0";
        let types = [Type::Int4, Type::Text];
        let values = vec![Some(42i32.to_be_bytes().to_vec()), Some(b"abc\0".to_vec())];
        assert_eq!(encode(&types, &values).unwrap(), expected);
        for end in 0..expected.len() {
            assert!(decode(&expected[..end]).unwrap().is_none());
        }
        let row = decode(expected).unwrap().unwrap();
        assert_eq!(row.values[1], Some(&b"abc\0"[..]));
    }
    #[test]
    fn null_bitmap_and_all_types() {
        let types = [
            Type::Int8,
            Type::Bool,
            Type::Float8,
            Type::Int4,
            Type::Float4,
            Type::Int2,
            Type::Bytea,
            Type::Text,
            Type::Text,
        ];
        let values = vec![
            Some((-1i64).to_be_bytes().to_vec()),
            Some(vec![1]),
            Some(f64::NAN.to_be_bytes().to_vec()),
            None,
            Some(1f32.to_be_bytes().to_vec()),
            Some((-2i16).to_be_bytes().to_vec()),
            Some(vec![0, 255]),
            Some(b"\0".to_vec()),
            None,
        ];
        let encoded = encode(&types, &values).unwrap();
        let row = decode(&encoded).unwrap().unwrap();
        assert_eq!(
            row.values,
            values.iter().map(|v| v.as_deref()).collect::<Vec<_>>()
        );
        // Independent v1 record with a single NULL text column.
        assert_eq!(
            decode(b"\0\0\0\x10\0\x01\0\x01\x07\x80\0\0\0\0\0\0")
                .unwrap()
                .unwrap()
                .values,
            vec![None]
        );
    }
    #[test]
    fn rejects_invalid_frames_without_panicking() {
        for bytes in [&b"\xff\xff\xff\xff"[..], &b"\0\0\0\0"[..]] {
            assert!(decode(bytes).is_err());
        }
        let mut data = encode(&[Type::Text], &[Some(b"x\0".to_vec())]).unwrap();
        data[12..16].copy_from_slice(&u32::MAX.to_be_bytes());
        assert!(decode(&data).is_err());
        // Mutating every byte must never produce an out-of-bounds read or panic.
        for i in 0..data.len() {
            for byte in [0, 1, 127, 255] {
                let mut corrupt = data.clone();
                corrupt[i] = byte;
                let _ = decode(&corrupt);
            }
        }
    }
}
