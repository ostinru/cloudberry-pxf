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
//! Incremental multibyte-delimiter records used by pxfdelimited_import.
use crate::writable::Error;
#[derive(Debug, Clone)]
pub struct Config {
    pub delimiter: Vec<u8>,
    pub newline: Vec<u8>,
    pub quote: Option<u8>,
    pub escape: Option<u8>,
}
#[derive(Debug, PartialEq)]
pub struct Record {
    pub fields: Vec<Option<Vec<u8>>>,
    pub consumed: usize,
}
impl Config {
    pub fn validate(&self) -> Result<(), Error> {
        if self.delimiter.is_empty() {
            return Err(Error("missing delimiter option".into()));
        }
        if ![&b"\n"[..], &b"\r"[..], &b"\r\n"[..]].contains(&self.newline.as_slice()) {
            return Err(Error("NEWLINE can only be LF, CRLF, or CR".into()));
        }
        Ok(())
    }
    pub fn decode(&self, data: &[u8]) -> Result<Option<Record>, Error> {
        self.validate()?;
        let mut fields = Vec::new();
        let mut field = Vec::new();
        let mut i = 0;
        let mut opening = self.quote.is_some();
        let mut closed = false;
        while i < data.len() {
            if opening {
                if Some(data[i]) != self.quote {
                    return Err(Error("Missing quote before column".into()));
                }
                opening = false;
                closed = false;
                i += 1;
                continue;
            }
            if self.quote.is_none() || closed {
                if data[i..].starts_with(&self.newline) {
                    fields.push(if field.is_empty() { None } else { Some(field) });
                    return Ok(Some(Record {
                        fields,
                        consumed: i + self.newline.len(),
                    }));
                }
                if data[i..].starts_with(&self.delimiter) {
                    fields.push(if field.is_empty() {
                        None
                    } else {
                        Some(std::mem::take(&mut field))
                    });
                    i += self.delimiter.len();
                    opening = self.quote.is_some();
                    continue;
                }
                if closed {
                    if self.newline.starts_with(&data[i..])
                        || self.delimiter.starts_with(&data[i..])
                    {
                        return Ok(None);
                    }
                    return Err(Error(
                        "Expected delimiter or newline after closing quote".into(),
                    ));
                }
            }
            if Some(data[i]) == self.escape {
                let Some(&next) = data.get(i + 1) else {
                    return Ok(None);
                };
                if Some(next) == self.escape || (self.quote.is_some() && Some(next) == self.quote) {
                    field.push(next);
                    i += 2;
                    continue;
                }
                if self.quote.is_none() {
                    if data[i + 1..].starts_with(&self.newline) {
                        field.extend_from_slice(&self.newline);
                        i += 1 + self.newline.len();
                        continue;
                    }
                    if data[i + 1..].starts_with(&self.delimiter) {
                        field.extend_from_slice(&self.delimiter);
                        i += 1 + self.delimiter.len();
                        continue;
                    }
                    if self.newline.starts_with(&data[i + 1..])
                        || self.delimiter.starts_with(&data[i + 1..])
                    {
                        return Ok(None);
                    }
                }
            }
            if Some(data[i]) == self.quote {
                closed = true;
                i += 1;
                continue;
            }
            field.push(data[i]);
            i += 1;
        }
        Ok(None)
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn quoted_multiline_and_fragmented_input() {
        let config = Config {
            delimiter: b"||".to_vec(),
            newline: b"\r\n".to_vec(),
            quote: Some(b'"'),
            escape: Some(b'"'),
        };
        let data = b"\"a\"\"b\r\nc\"||\"\"||\"z\"\r\n";
        for end in 0..data.len() {
            assert_eq!(config.decode(&data[..end]).unwrap(), None, "boundary {end}");
        }
        assert_eq!(
            config.decode(data).unwrap().unwrap().fields,
            vec![Some(b"a\"b\r\nc".to_vec()), None, Some(b"z".to_vec())]
        );
    }
    #[test]
    fn unquoted_escaping_empty_and_trailing_nulls() {
        let config = Config {
            delimiter: b"@!".to_vec(),
            newline: b"\n".to_vec(),
            quote: None,
            escape: Some(b'\\'),
        };
        let data = b"@!a\\@!b@!\\\n@!\n";
        for end in 0..data.len() {
            assert!(config.decode(&data[..end]).unwrap().is_none());
        }
        assert_eq!(
            config.decode(data).unwrap().unwrap().fields,
            vec![None, Some(b"a@!b".to_vec()), Some(b"\n".to_vec()), None]
        );
    }
    #[test]
    fn invalid_quotes_and_options() {
        let mut c = Config {
            delimiter: vec![],
            newline: b"\n".to_vec(),
            quote: Some(b'"'),
            escape: Some(b'\\'),
        };
        assert!(c.decode(b"a").is_err());
        c.delimiter = b"||".to_vec();
        assert!(c.decode(b"a").is_err());
        assert!(c.decode(b"\"a\"x").is_err());
    }
}
