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
pub struct ServerError {
    pub status: u32,
    /// Bounded, unmodified response bytes, even if they are not valid UTF-8.
    pub body: Vec<u8>,
    pub truncated: bool,
    pub message: String,
    pub hint: Option<String>,
    /// The database adapter decides whether to include this in diagnostics.
    pub trace: Option<String>,
}

impl ServerError {
    pub(super) fn new(status: u32, body: Vec<u8>, truncated: bool) -> Self {
        let text = String::from_utf8_lossy(&body);
        let mut message = text.to_string();
        let mut hint = None;
        let mut trace = None;
        if status == 404 {
            message = "PXF service could not be reached".to_owned();
        } else if body.is_empty() {
            message = format!("HTTP status code is {status} but HTTP response string is empty");
        } else if let Some(paragraph) =
            between(&text, "<body>", "</body>").and_then(|body| between(body, "<p>", "</p>"))
        {
            let mut in_tag = false;
            message = paragraph
                .chars()
                .filter_map(|c| match c {
                    '<' => {
                        in_tag = true;
                        Some(' ')
                    }
                    '>' => {
                        in_tag = false;
                        None
                    }
                    '\r' | '\n' => None,
                    _ if in_tag => None,
                    _ => Some(c),
                })
                .collect();
        } else if let Some(title) = between(&text, "<title>", "</title>") {
            message = title.to_owned();
        } else if let Ok(json) = serde_json::from_slice::<serde_json::Value>(&body) {
            if let Some(value) = json.get("message").and_then(|v| v.as_str()) {
                message = value.split('\n').next().unwrap_or(value).to_owned();
            }
            hint = json.get("hint").and_then(|v| v.as_str()).map(str::to_owned);
            trace = json
                .get("trace")
                .and_then(|v| v.as_str())
                .map(str::to_owned);
        }
        Self {
            status,
            body,
            truncated,
            message,
            hint,
            trace,
        }
    }
}

fn between<'a>(text: &'a str, begin: &str, end: &str) -> Option<&'a str> {
    let start = text.find(begin)? + begin.len();
    let length = text[start..].find(end)?;
    Some(&text[start..start + length])
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TransportError {
    InvalidRequest(String),
    Network(String),
    Runtime(String),
    Http(Box<ServerError>),
    Cancelled,
    Closed,
    /// A successful status is not an acknowledgement of an incomplete upload.
    PrematureResponse,
    Timeout,
}

impl From<reqwest::Error> for TransportError {
    fn from(value: reqwest::Error) -> Self {
        if value.is_timeout() {
            Self::Timeout
        } else {
            Self::Network(value.without_url().to_string())
        }
    }
}

impl fmt::Display for TransportError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidRequest(message) => write!(f, "invalid PXF request: {message}"),
            Self::Network(message) => write!(f, "PXF network error: {message}"),
            Self::Runtime(message) => write!(f, "PXF runtime error: {message}"),
            Self::Http(error) => {
                write!(f, "PXF server error ({}): {}", error.status, error.message)?;
                if error.truncated {
                    f.write_str(" [response body truncated]")?;
                }
                Ok(())
            }
            Self::Cancelled => f.write_str("PXF request cancelled"),
            Self::Closed => f.write_str("PXF transfer is closed"),
            Self::PrematureResponse => {
                f.write_str("PXF acknowledged an upload before its end of stream")
            }
            Self::Timeout => f.write_str("PXF transfer timed out"),
        }
    }
}

impl std::error::Error for TransportError {}
