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

//! Byte streaming over HTTP, independent of both PostgreSQL and wire format.
//!
//! Async network tasks run on a private current-thread runtime, driven only by
//! calls to read/write/finish. Cancellation callbacks run OUTSIDE that runtime
//! and must return a flag, not raise PostgreSQL errors. Drop aborts an upload;
//! only explicit finish sends EOF and checks the server's acknowledgement.

mod error;
pub use error::{ServerError, TransportError};

use std::future::Future;
use std::io;
use std::pin::Pin;
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc,
};
use std::task::{Context, Poll};
use std::time::{Duration, Instant};

use bytes::Bytes;
use futures_core::Stream;
use reqwest::header::{HeaderMap, HeaderName, HeaderValue};
use tokio::runtime::{Builder, Runtime};
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

type Result<T> = std::result::Result<T, TransportError>;

#[derive(Debug, Clone)]
pub struct Config {
    /// Maximum application chunk size; the channel holds at most one chunk.
    /// HTTP/TLS and OS socket buffers have their own, separate bounds.
    pub chunk_bytes: usize,
    /// Maximum diagnostic body retained on a non-200 response.
    pub error_body_bytes: usize,
    pub connect_timeout: Duration,
    pub transfer_timeout: Option<Duration>,
    /// Cancellation is checked between network waits (1..=100 ms).
    pub poll_interval: Duration,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            chunk_bytes: 64 * 1024,
            error_body_bytes: 16 * 1024,
            connect_timeout: Duration::from_secs(30),
            transfer_timeout: None,
            poll_interval: Duration::from_millis(50),
        }
    }
}

impl Config {
    fn validate(&self) -> Result<()> {
        if self.chunk_bytes == 0
            || self.error_body_bytes == 0
            || self.connect_timeout < Duration::from_millis(1)
            || self
                .transfer_timeout
                .is_some_and(|d| d < Duration::from_millis(1))
            || !(Duration::from_millis(1)..=Duration::from_millis(100))
                .contains(&self.poll_interval)
        {
            return Err(TransportError::InvalidRequest(
                "invalid buffer size or timeout".into(),
            ));
        }
        Ok(())
    }
}

#[derive(Debug, Clone)]
pub struct Request {
    url: reqwest::Url,
    headers: HeaderMap,
}

impl Request {
    pub fn new(url: impl AsRef<str>) -> Result<Self> {
        let url = reqwest::Url::parse(url.as_ref())
            .map_err(|_| TransportError::InvalidRequest("invalid PXF endpoint URL".into()))?;
        if !matches!(url.scheme(), "http" | "https") || url.host_str().is_none() {
            return Err(TransportError::InvalidRequest(
                "expected an HTTP(S) PXF endpoint".into(),
            ));
        }
        Ok(Self {
            url,
            headers: HeaderMap::new(),
        })
    }

    /// Raw database bytes, not pre-escaped strings. None removes a header;
    /// Some(b"") sends an empty value. Names are replaced case-insensitively.
    /// Content-Type and Accept can select Arrow IPC without changing transport.
    pub fn header(&mut self, name: &str, value: Option<&[u8]>) -> Result<&mut Self> {
        self.set_header(name, value, false)
    }

    /// Preserve repeated header fields, notably X-GP-ATTRS-PROJ-IDX.
    pub fn append_header(&mut self, name: &str, value: &[u8]) -> Result<&mut Self> {
        self.set_header(name, Some(value), true)
    }

    fn set_header(&mut self, name: &str, value: Option<&[u8]>, append: bool) -> Result<&mut Self> {
        if [
            "Content-Length",
            "Transfer-Encoding",
            "Expect",
            "Connection",
        ]
        .iter()
        .any(|reserved| name.eq_ignore_ascii_case(reserved))
        {
            return Err(TransportError::InvalidRequest(format!(
                "{name} is managed by the transport"
            )));
        }
        let rendered = crate::headers::render(name, value)
            .map_err(|e| TransportError::InvalidRequest(e.to_string()))?;
        let key = HeaderName::from_bytes(name.as_bytes())
            .map_err(|e| TransportError::InvalidRequest(e.to_string()))?;
        if value.is_some() {
            let value = HeaderValue::from_str(&rendered[name.len() + 2..])
                .map_err(|e| TransportError::InvalidRequest(e.to_string()))?;
            if append {
                self.headers.append(key, value);
            } else {
                self.headers.insert(key, value);
            }
        } else {
            self.headers.remove(key);
        }
        Ok(self)
    }

    fn build(self, config: &Config, upload: bool) -> Result<reqwest::RequestBuilder> {
        let client = reqwest::Client::builder()
            .http1_only()
            .redirect(reqwest::redirect::Policy::none())
            .retry(reqwest::retry::never())
            .no_gzip()
            .no_brotli()
            .no_deflate()
            .no_zstd()
            .connect_timeout(config.connect_timeout)
            .pool_max_idle_per_host(0)
            .build()?;
        let mut builder = if upload {
            client.post(self.url)
        } else {
            client.get(self.url)
        }
        .headers(self.headers)
        .header("Connection", "close");
        if let Some(timeout) = config.transfer_timeout {
            builder = builder.timeout(timeout);
        }
        Ok(builder)
    }
}

/// Runtime destruction must not wait for a blocking system DNS lookup. Any DNS
/// worker owns Rust data only and never accesses PostgreSQL or extension state.
struct Driver {
    runtime: Option<Runtime>,
    config: Config,
    started: Instant,
}

impl Driver {
    fn new(config: Config) -> Result<Self> {
        config.validate()?;
        let runtime = Builder::new_current_thread()
            .enable_all()
            .build()
            .map_err(|e| TransportError::Runtime(e.to_string()))?;
        Ok(Self {
            runtime: Some(runtime),
            config,
            started: Instant::now(),
        })
    }

    fn runtime(&self) -> &Runtime {
        self.runtime.as_ref().expect("live driver")
    }

    fn check(&self, cancelled: &mut impl FnMut() -> bool) -> Result<()> {
        if cancelled() {
            return Err(TransportError::Cancelled);
        }
        if self
            .config
            .transfer_timeout
            .is_some_and(|d| self.started.elapsed() >= d)
        {
            return Err(TransportError::Timeout);
        }
        Ok(())
    }

    fn run<T>(
        &self,
        future: impl Future<Output = Result<T>>,
        cancelled: &mut impl FnMut() -> bool,
    ) -> Result<T> {
        let mut future = std::pin::pin!(future);
        loop {
            self.check(cancelled)?;
            // Retain the same future across timer ticks. Recreating a request
            // here would retry a potentially partially executed write.
            let result = self.runtime().block_on(async {
                tokio::time::timeout(self.config.poll_interval, future.as_mut()).await
            });
            self.check(cancelled)?;
            if let Ok(result) = result {
                return result;
            }
        }
    }
}

impl Drop for Driver {
    fn drop(&mut self) {
        if let Some(runtime) = self.runtime.take() {
            runtime.shutdown_background();
        }
    }
}

fn task_result(result: std::result::Result<Result<()>, tokio::task::JoinError>) -> Result<()> {
    result.map_err(|e| TransportError::Runtime(e.to_string()))?
}

async fn check_response(response: &mut reqwest::Response, limit: usize) -> Result<()> {
    let status = u32::from(response.status().as_u16());
    if status == 200 {
        return Ok(());
    }
    let mut body = Vec::new();
    let mut truncated = false;
    // Preserve HTTP diagnostics even if the non-200 body itself is broken.
    loop {
        match response.chunk().await {
            Ok(Some(chunk)) => {
                let remaining = limit - body.len();
                body.extend_from_slice(&chunk[..chunk.len().min(remaining)]);
                if chunk.len() > remaining {
                    truncated = true;
                    break;
                }
            }
            Ok(None) => break,
            Err(_) => {
                truncated = true;
                break;
            }
        }
    }
    Err(TransportError::Http(Box::new(ServerError::new(
        status, body, truncated,
    ))))
}

pub struct Download {
    driver: Option<Driver>,
    task: JoinHandle<Result<()>>,
    receiver: mpsc::Receiver<Bytes>,
    pending: Bytes,
    completed: bool,
    failure: Option<TransportError>,
}

impl Download {
    pub fn open(request: Request, config: Config) -> Result<Self> {
        let driver = Driver::new(config)?;
        let (sender, receiver) = mpsc::channel(1);
        let chunk_size = driver.config.chunk_bytes;
        let error_limit = driver.config.error_body_bytes;
        let request = request.build(&driver.config, false)?;
        let task = driver.runtime().spawn(async move {
            let mut response = request.send().await?;
            check_response(&mut response, error_limit).await?;
            while let Some(chunk) = response.chunk().await? {
                for part in chunk.chunks(chunk_size) {
                    // Copy only after reserving queue capacity. Slicing Bytes
                    // would retain a possibly much larger HTTP allocation.
                    let permit = sender.reserve().await.map_err(|_| TransportError::Closed)?;
                    permit.send(Bytes::copy_from_slice(part));
                }
            }
            Ok(())
        });
        Ok(Self {
            driver: Some(driver),
            task,
            receiver,
            pending: Bytes::new(),
            completed: false,
            failure: None,
        })
    }

    fn fail<T>(&mut self, error: TransportError) -> Result<T> {
        self.task.abort();
        self.driver.take();
        self.receiver.close();
        self.pending = Bytes::new();
        self.failure = Some(error.clone());
        Err(error)
    }

    pub fn read(
        &mut self,
        output: &mut [u8],
        cancelled: &mut impl FnMut() -> bool,
    ) -> Result<usize> {
        if let Some(error) = &self.failure {
            return Err(error.clone());
        }
        if let Some(driver) = &self.driver {
            if let Err(error) = driver.check(cancelled) {
                return self.fail(error);
            }
        }
        if output.is_empty() {
            return Ok(0);
        }
        if self.pending.is_empty() && !self.completed {
            let result = self.driver.as_ref().expect("active download").run(
                async {
                    match self.receiver.recv().await {
                        Some(chunk) => Ok(Some(chunk)),
                        None => {
                            task_result((&mut self.task).await)?;
                            Ok(None)
                        }
                    }
                },
                cancelled,
            );
            match result {
                Ok(Some(chunk)) => self.pending = chunk,
                Ok(None) => {
                    self.completed = true;
                    self.driver.take();
                }
                Err(error) => return self.fail(error),
            }
        }
        let count = output.len().min(self.pending.len());
        output[..count].copy_from_slice(&self.pending.split_to(count));
        Ok(count)
    }

    /// Adapt to std::io::Read for decoders such as Arrow IPC StreamReader.
    /// Consume HTTP EOF too: a format EOS marker alone does not validate HTTP.
    pub fn reader<'a, C: FnMut() -> bool>(
        &'a mut self,
        cancelled: &'a mut C,
    ) -> DownloadReader<'a, C> {
        DownloadReader {
            transfer: self,
            cancelled,
        }
    }

    pub fn abort(&mut self) {
        let _: Result<()> = self.fail(TransportError::Cancelled);
    }
}

impl Drop for Download {
    fn drop(&mut self) {
        self.task.abort();
    }
}

pub struct DownloadReader<'a, C> {
    transfer: &'a mut Download,
    cancelled: &'a mut C,
}

impl<C: FnMut() -> bool> io::Read for DownloadReader<'_, C> {
    fn read(&mut self, output: &mut [u8]) -> io::Result<usize> {
        self.transfer
            .read(output, self.cancelled)
            .map_err(io::Error::other)
    }
}

struct UploadBody {
    receiver: mpsc::Receiver<Bytes>,
    eof: Arc<AtomicBool>,
}

impl Stream for UploadBody {
    type Item = io::Result<Bytes>;
    fn poll_next(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        match self.receiver.poll_recv(cx) {
            Poll::Ready(None) => {
                self.eof.store(true, Ordering::Relaxed);
                Poll::Ready(None)
            }
            Poll::Ready(Some(bytes)) => Poll::Ready(Some(Ok(bytes))),
            Poll::Pending => Poll::Pending,
        }
    }
}

pub struct Upload {
    driver: Option<Driver>,
    task: JoinHandle<Result<()>>,
    sender: Option<mpsc::Sender<Bytes>>,
    completed: bool,
    failure: Option<TransportError>,
}

impl Upload {
    pub fn open(mut request: Request, config: Config) -> Result<Self> {
        let driver = Driver::new(config)?;
        let (sender, receiver) = mpsc::channel(1);
        let eof = Arc::new(AtomicBool::new(false));
        let body = UploadBody {
            receiver,
            eof: eof.clone(),
        };
        request
            .headers
            .entry("Content-Type")
            .or_insert(HeaderValue::from_static("application/octet-stream"));
        let request = request
            .build(&driver.config, true)?
            .header("Transfer-Encoding", "chunked")
            .header("Expect", "100-continue")
            .body(reqwest::Body::wrap_stream(body));
        let error_limit = driver.config.error_body_bytes;
        let task = driver.runtime().spawn(async move {
            let mut response = request.send().await?;
            check_response(&mut response, error_limit).await?;
            if !eof.load(Ordering::Relaxed) {
                return Err(TransportError::PrematureResponse);
            }
            // A 200 header is insufficient: validate the complete response.
            while response.chunk().await?.is_some() {}
            Ok(())
        });
        Ok(Self {
            driver: Some(driver),
            task,
            sender: Some(sender),
            completed: false,
            failure: None,
        })
    }

    fn fail<T>(&mut self, error: TransportError) -> Result<T> {
        self.task.abort();
        self.driver.take();
        self.sender.take();
        self.failure = Some(error.clone());
        Err(error)
    }

    /// Accept at most one bounded chunk. The caller must handle partial writes.
    /// Acceptance into the queue is NOT a server durability acknowledgement.
    pub fn write(&mut self, input: &[u8], cancelled: &mut impl FnMut() -> bool) -> Result<usize> {
        if let Some(error) = &self.failure {
            return Err(error.clone());
        }
        let Some(sender) = &self.sender else {
            return Err(TransportError::Closed);
        };
        let driver = self.driver.as_ref().expect("active upload");
        let count = input.len().min(driver.config.chunk_bytes);
        let result = driver.run(async {
            if count == 0 { return Ok(0); }
            tokio::select! {
                biased;
                result = &mut self.task => {
                    task_result(result)?;
                    Err(TransportError::PrematureResponse)
                },
                permit = sender.reserve() => {
                    match permit {
                        Ok(permit) => { permit.send(Bytes::copy_from_slice(&input[..count])); Ok(count) },
                        Err(_) => { task_result((&mut self.task).await)?; Err(TransportError::PrematureResponse) },
                    }
                },
            }
        }, cancelled);
        match result {
            Ok(count) => Ok(count),
            Err(error) => self.fail(error),
        }
    }

    pub fn finish(&mut self, cancelled: &mut impl FnMut() -> bool) -> Result<()> {
        if let Some(error) = &self.failure {
            return Err(error.clone());
        }
        if self.completed {
            return Ok(());
        }
        self.sender.take(); // The only operation that intentionally sends EOF.
        let result = self
            .driver
            .as_ref()
            .expect("active upload")
            .run(async { task_result((&mut self.task).await) }, cancelled);
        match result {
            Ok(()) => {
                self.completed = true;
                self.driver.take();
                Ok(())
            }
            Err(error) => self.fail(error),
        }
    }

    /// std::io::Write adapter for encoders such as Arrow IPC StreamWriter.
    /// flush does not finish HTTP. Call Upload::finish after the encoder's EOS.
    pub fn writer<'a, C: FnMut() -> bool>(
        &'a mut self,
        cancelled: &'a mut C,
    ) -> UploadWriter<'a, C> {
        UploadWriter {
            transfer: self,
            cancelled,
        }
    }

    pub fn abort(&mut self) {
        let _: Result<()> = self.fail(TransportError::Cancelled);
    }
}

impl Drop for Upload {
    fn drop(&mut self) {
        self.task.abort();
    }
}

pub struct UploadWriter<'a, C> {
    transfer: &'a mut Upload,
    cancelled: &'a mut C,
}

impl<C: FnMut() -> bool> io::Write for UploadWriter<'_, C> {
    fn write(&mut self, input: &[u8]) -> io::Result<usize> {
        self.transfer
            .write(input, self.cancelled)
            .map_err(io::Error::other)
    }
    fn flush(&mut self) -> io::Result<()> {
        self.transfer
            .write(&[], self.cancelled)
            .map(|_| ())
            .map_err(io::Error::other)
    }
}
