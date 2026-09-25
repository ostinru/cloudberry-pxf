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

use std::fmt;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InvalidHeader;

impl fmt::Display for InvalidHeader {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("invalid HTTP header name or unencoded value")
    }
}

impl std::error::Error for InvalidHeader {}

/// The curl header representation. A missing value suppresses the header;
/// an empty value is distinct and retains the legacy `name: ` representation.
pub fn render(name: &str, value: Option<&[u8]>) -> Result<String, InvalidHeader> {
    if name.is_empty() || !name.bytes().all(is_token) {
        return Err(InvalidHeader);
    }
    let Some(value) = value else {
        return Ok(name.to_owned());
    };
    let encoded = if name
        .get(..5)
        .is_some_and(|p| p.eq_ignore_ascii_case("X-GP-"))
    {
        percent_encode(value)
    } else {
        if value.iter().any(|b| *b < 32 && *b != b'\t' || *b == 127) {
            return Err(InvalidHeader);
        }
        std::str::from_utf8(value)
            .map_err(|_| InvalidHeader)?
            .to_owned()
    };
    Ok(format!("{name}: {encoded}"))
}

pub fn option_name(name: &[u8]) -> Result<String, InvalidHeader> {
    if name.is_empty() || !name.iter().copied().all(is_token) {
        return Err(InvalidHeader);
    }
    // is_token accepts ASCII only.
    let name: String = name
        .iter()
        .map(|b| char::from(b.to_ascii_uppercase()))
        .collect();
    Ok(format!("X-GP-OPTIONS-{name}"))
}

fn is_token(byte: u8) -> bool {
    byte.is_ascii_alphanumeric() || b"!#$%&'*+-.^_`|~".contains(&byte)
}

/// Matches curl_easy_escape: RFC3986 unreserved ASCII remains literal; all
/// other BYTES become uppercase escapes. This is not form encoding ('+').
fn percent_encode(value: &[u8]) -> String {
    const HEX: &[u8] = b"0123456789ABCDEF";
    let mut result = String::with_capacity(value.len());
    for &byte in value {
        if byte.is_ascii_alphanumeric() || b"-._~".contains(&byte) {
            result.push(char::from(byte));
        } else {
            result.push('%');
            result.push(char::from(HEX[usize::from(byte >> 4)]));
            result.push(char::from(HEX[usize::from(byte & 15)]));
        }
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn custom_headers_match_curl_escape_including_existing_escapes() {
        assert_eq!(
            render("X-GP-DATA-DIR", Some(b"a b/+%20?\xff")).unwrap(),
            "X-GP-DATA-DIR: a%20b%2F%2B%2520%3F%FF"
        );
        assert_eq!(
            render("x-gp-user", Some("имя".as_bytes())).unwrap(),
            "x-gp-user: %D0%B8%D0%BC%D1%8F"
        );
    }

    #[test]
    fn ordinary_headers_are_not_escaped() {
        assert_eq!(
            render("Content-Type", Some(b"application/octet-stream")).unwrap(),
            "Content-Type: application/octet-stream"
        );
        assert!(render("Connection", Some(b"close\r\nInjected: yes")).is_err());
        assert!(render("Bad:Name", Some(b"x")).is_err());
    }

    #[test]
    fn absent_and_empty_values_are_distinct() {
        assert_eq!(render("X-GP-FILTER", None).unwrap(), "X-GP-FILTER");
        assert_eq!(render("X-GP-FILTER", Some(b"")).unwrap(), "X-GP-FILTER: ");
        assert_eq!(option_name(b"profile").unwrap(), "X-GP-OPTIONS-PROFILE");
        assert!(option_name(b"profile\r\n").is_err());
    }
}
