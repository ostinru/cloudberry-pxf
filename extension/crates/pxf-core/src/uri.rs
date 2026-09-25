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
pub struct LocationOption {
    pub name: Vec<u8>,
    pub value: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Location {
    pub original: Vec<u8>,
    pub resource: Vec<u8>,
    pub options: Vec<LocationOption>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LocationError {
    InvalidProtocol,
    MissingOptions,
    InvalidOptions,
    MissingEquals,
    MissingKey,
    MissingValue,
    EmbeddedNul,
    DuplicateOptions(Vec<Vec<u8>>),
    MissingCoreOptions(Vec<&'static str>),
}

impl fmt::Display for LocationError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidProtocol => f.write_str("unsupported or missing PXF protocol"),
            Self::MissingOptions => f.write_str("missing options section"),
            Self::InvalidOptions => f.write_str("invalid option after '?'"),
            Self::MissingEquals => f.write_str("option missing '='"),
            Self::MissingKey => f.write_str("option missing key before '='"),
            Self::MissingValue => f.write_str("option missing value after '='"),
            Self::EmbeddedNul => f.write_str("location contains a NUL byte"),
            Self::DuplicateOptions(keys) => {
                f.write_str("Duplicate option(s): ")?;
                for (i, key) in keys.iter().enumerate() {
                    if i != 0 {
                        f.write_str(", ")?;
                    }
                    write!(f, "{}", String::from_utf8_lossy(key))?;
                }
                Ok(())
            }
            Self::MissingCoreOptions(keys) => {
                write!(f, "{} option(s) missing", keys.join(" and "))
            }
        }
    }
}

impl LocationError {
    /// Include the offending option and URI, matching the legacy SQL diagnostic.
    pub fn describe(&self, input: &[u8]) -> String {
        let uri = String::from_utf8_lossy(input);
        let detail = match self {
            Self::InvalidProtocol => {
                return match uri.find("://") {
                    Some(end) => {
                        format!("invalid URI {uri}: unsupported protocol '{}'", &uri[..end])
                    }
                    None => format!("invalid URI {uri}"),
                };
            }
            Self::MissingEquals | Self::MissingKey | Self::MissingValue => {
                let options = input.rsplit(|&b| b == b'?').next().unwrap_or(input);
                let pair = options
                    .split(|&b| b == b'&')
                    .find(|pair| {
                        !pair.is_empty()
                            && match pair.iter().position(|&b| b == b'=') {
                                None => true,
                                Some(index) => index == 0 || index + 1 == pair.len(),
                            }
                    })
                    .unwrap_or(b"");
                format!(
                    "option '{}' {}",
                    String::from_utf8_lossy(pair),
                    self.to_string().strip_prefix("option ").unwrap()
                )
            }
            _ => self.to_string(),
        };
        format!("invalid URI {uri}: {detail}")
    }
}

impl std::error::Error for LocationError {}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Direction {
    Read,
    Write,
}

impl Location {
    /// A PXF location is not an HTTP URL. Everything before the LAST '?' is
    /// the resource, including slashes, percent escapes and earlier '?'.
    /// Options are not URL-decoded. Empty '&' fields are skipped, like strtok_r.
    pub fn parse(input: &[u8]) -> Result<Self, LocationError> {
        if input.contains(&0) {
            return Err(LocationError::EmbeddedNul);
        }
        if !input
            .get(..6)
            .is_some_and(|p| p.eq_ignore_ascii_case(b"pxf://"))
        {
            return Err(LocationError::InvalidProtocol);
        }
        let body = &input[6..];
        let separator = body
            .iter()
            .rposition(|b| *b == b'?')
            .ok_or(LocationError::MissingOptions)?;
        let option_bytes = &body[separator + 1..];
        if option_bytes.len() < 2 {
            return Err(LocationError::InvalidOptions);
        }
        let mut options = Vec::new();
        for pair in option_bytes.split(|b| *b == b'&').filter(|p| !p.is_empty()) {
            let equals = pair
                .iter()
                .position(|b| *b == b'=')
                .ok_or(LocationError::MissingEquals)?;
            if equals == 0 {
                return Err(LocationError::MissingKey);
            }
            if equals + 1 == pair.len() {
                return Err(LocationError::MissingValue);
            }
            options.push(LocationOption {
                name: pair[..equals].to_vec(),
                value: pair[equals + 1..].to_vec(),
            });
        }
        Ok(Self {
            original: input.to_vec(),
            resource: body[..separator].to_vec(),
            options,
        })
    }

    pub fn option(&self, name: &[u8]) -> Option<&[u8]> {
        self.options
            .iter()
            .find(|opt| opt.name.eq_ignore_ascii_case(name))
            .map(|opt| opt.value.as_slice())
    }

    /// Kept separate from parsing to match protocol validation in the C client.
    pub fn validate(&self, direction: Direction) -> Result<(), LocationError> {
        let mut seen: Vec<Vec<u8>> = Vec::new();
        let mut duplicates = Vec::new();
        for option in &self.options {
            let key = option.name.to_ascii_uppercase();
            if seen.contains(&key) {
                if !duplicates.contains(&key) {
                    duplicates.push(key);
                }
            } else {
                seen.push(key);
            }
        }
        if !duplicates.is_empty() {
            return Err(LocationError::DuplicateOptions(duplicates));
        }
        if self.option(b"PROFILE").is_some() {
            return Ok(());
        }
        let mut required = vec!["ACCESSOR", "RESOLVER"];
        if direction == Direction::Read {
            required.insert(0, "FRAGMENTER");
        }
        let missing: Vec<_> = required
            .into_iter()
            .filter(|name| self.option(name.as_bytes()).is_none())
            .collect();
        if missing.is_empty() {
            Ok(())
        } else {
            Err(LocationError::MissingCoreOptions(missing))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn legacy_location_preserves_resource_and_options() {
        let raw = b"PXF://bucket/a?b%20c?PROFILE=s3:text&&SERVER=a=b&";
        let location = Location::parse(raw).unwrap();
        assert_eq!(location.original, raw);
        assert_eq!(location.resource, b"bucket/a?b%20c");
        assert_eq!(location.option(b"profile"), Some(b"s3:text".as_slice()));
        assert_eq!(location.option(b"server"), Some(b"a=b".as_slice()));
        location.validate(Direction::Read).unwrap();
    }

    #[test]
    fn non_utf8_resource_and_option_are_not_reencoded() {
        let location = Location::parse(b"pxf://\xff?PROFILE=x&name=\xfe").unwrap();
        assert_eq!(location.resource, b"\xff");
        assert_eq!(location.option(b"name"), Some(b"\xfe".as_slice()));
    }

    #[test]
    fn validation_rejects_case_insensitive_duplicates_even_with_profile() {
        let location = Location::parse(b"pxf://x?PROFILE=a&profile=b&Profile=c").unwrap();
        assert_eq!(
            location.validate(Direction::Read),
            Err(LocationError::DuplicateOptions(vec![b"PROFILE".to_vec()]))
        );
    }

    #[test]
    fn fragmenter_is_required_only_for_read_without_profile() {
        let location = Location::parse(b"pxf://x?ACCESSOR=a&RESOLVER=r").unwrap();
        location.validate(Direction::Write).unwrap();
        assert_eq!(
            location.validate(Direction::Read),
            Err(LocationError::MissingCoreOptions(vec!["FRAGMENTER"]))
        );
    }

    #[test]
    fn legacy_syntax_errors() {
        for (input, expected) in [
            (
                b"http://x?PROFILE=a".as_slice(),
                LocationError::InvalidProtocol,
            ),
            (b"pxf://x", LocationError::MissingOptions),
            (b"pxf://x?", LocationError::InvalidOptions),
            (b"pxf://x?FRAGMENTER", LocationError::MissingEquals),
            (b"pxf://x?=x", LocationError::MissingKey),
            (b"pxf://x?PROFILE=", LocationError::MissingValue),
            (b"pxf://x?PROFILE=a\0b", LocationError::EmbeddedNul),
        ] {
            assert_eq!(Location::parse(input), Err(expected));
        }
    }
}
