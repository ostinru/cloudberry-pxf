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

use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::Arc;
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use arrow_array::{Int32Array, RecordBatch, StringArray};
use arrow_ipc::{reader::StreamReader, writer::StreamWriter};
use arrow_schema::{DataType, Field, Schema};
use pxf_core::transport::{Config, Download, Request, TransportError, Upload};

fn config() -> Config {
    Config {
        chunk_bytes: 4096,
        poll_interval: Duration::from_millis(5),
        transfer_timeout: Some(Duration::from_secs(5)),
        ..Config::default()
    }
}

fn server<T: Send + 'static>(
    serve: impl FnOnce(TcpStream) -> T + Send + 'static,
) -> (String, JoinHandle<T>) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    listener.set_nonblocking(true).unwrap();
    let url = format!("http://{}/pxf/v16/test", listener.local_addr().unwrap());
    let task = thread::spawn(move || {
        let started = Instant::now();
        let stream = loop {
            match listener.accept() {
                Ok((stream, _)) => break stream,
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                    assert!(
                        started.elapsed() < Duration::from_secs(6),
                        "client did not connect"
                    );
                    thread::sleep(Duration::from_millis(5));
                }
                Err(e) => panic!("accept: {e}"),
            }
        };
        stream
            .set_read_timeout(Some(Duration::from_secs(6)))
            .unwrap();
        stream
            .set_write_timeout(Some(Duration::from_secs(6)))
            .unwrap();
        serve(stream)
    });
    (url, task)
}

fn headers(reader: &mut BufReader<TcpStream>) -> String {
    let mut result = String::new();
    loop {
        let mut line = String::new();
        assert!(
            reader.read_line(&mut line).unwrap() > 0,
            "EOF before headers"
        );
        result.push_str(&line);
        if line == "\r\n" {
            return result;
        }
    }
}

fn reply(stream: &mut TcpStream, status: &str, body: &[u8]) {
    write!(
        stream,
        "HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        body.len()
    )
    .unwrap();
    stream.write_all(body).unwrap();
}

fn read_chunks(reader: &mut BufReader<TcpStream>) -> Vec<u8> {
    let mut body = Vec::new();
    loop {
        let mut line = String::new();
        assert!(
            reader.read_line(&mut line).unwrap() > 0,
            "missing chunk size"
        );
        let len = usize::from_str_radix(line.trim().split(';').next().unwrap(), 16).unwrap();
        if len == 0 {
            line.clear();
            reader.read_line(&mut line).unwrap();
            assert_eq!(line, "\r\n");
            return body;
        }
        let offset = body.len();
        body.resize(offset + len, 0);
        reader.read_exact(&mut body[offset..]).unwrap();
        let mut crlf = [0; 2];
        reader.read_exact(&mut crlf).unwrap();
        assert_eq!(&crlf, b"\r\n");
    }
}

fn download_bytes(request: Request, cfg: Config) -> Result<Vec<u8>, TransportError> {
    let mut download = Download::open(request, cfg)?;
    let mut result = Vec::new();
    let mut buffer = [0; 137];
    loop {
        let count = download.read(&mut buffer, &mut || false)?;
        if count == 0 {
            return Ok(result);
        }
        result.extend_from_slice(&buffer[..count]);
    }
}

#[test]
fn streams_binary_get_and_preserves_pxf_headers() {
    let expected: Vec<u8> = (0..600_000).map(|i| (i % 256) as u8).collect();
    let body = expected.clone();
    let (url, task) = server(move |stream| {
        let mut reader = BufReader::new(stream);
        let request = headers(&mut reader).to_ascii_lowercase();
        assert!(request.starts_with("get /pxf/v16/test http/1.1"));
        assert!(request.contains("x-gp-data-dir: a%20%ff%00%2b%2520\r\n"));
        assert!(request.contains("x-gp-empty: \r\n"));
        assert!(!request.contains("x-gp-absent:"));
        reply(reader.get_mut(), "200 OK", &body);
    });
    let mut request = Request::new(url).unwrap();
    request.header("X-GP-DATA-DIR", Some(b"old")).unwrap();
    request
        .header("x-gp-data-dir", Some(b"a \xff\0+%20"))
        .unwrap();
    request.header("X-GP-EMPTY", Some(b"")).unwrap();
    request.header("X-GP-ABSENT", Some(b"remove")).unwrap();
    request.header("X-GP-ABSENT", None).unwrap();
    assert_eq!(download_bytes(request, config()).unwrap(), expected);
    task.join().unwrap();
}

#[test]
fn streams_chunked_post_and_requires_explicit_finish() {
    let expected: Vec<u8> = (0..600_000).map(|i| (i % 256) as u8).collect();
    let (url, task) = server(|stream| {
        let mut reader = BufReader::new(stream);
        let request = headers(&mut reader).to_ascii_lowercase();
        assert!(request.starts_with("post "));
        assert!(request.contains("transfer-encoding: chunked\r\n"));
        assert!(request.contains("expect: 100-continue\r\n"));
        assert!(!request.contains("content-length:"));
        reader
            .get_mut()
            .write_all(b"HTTP/1.1 100 Continue\r\n\r\n")
            .unwrap();
        let body = read_chunks(&mut reader);
        reply(reader.get_mut(), "200 OK", b"ack");
        body
    });
    let mut upload = Upload::open(Request::new(url).unwrap(), config()).unwrap();
    upload.writer(&mut || false).write_all(&expected).unwrap();
    upload.finish(&mut || false).unwrap();
    upload.finish(&mut || false).unwrap();
    assert_eq!(
        upload.write(b"late", &mut || false),
        Err(TransportError::Closed)
    );
    assert_eq!(task.join().unwrap(), expected);
}

#[test]
fn reports_json_html_empty_and_bounded_error_bodies() {
    for (status, body, limit) in [
        (
            "500 Error",
            br#"{"message":"failure\nprivate detail","hint":"try config","trace":"stack"}"#
                .as_slice(),
            4096,
        ),
        (
            "500 Error",
            b"<html><body><p><b>Error</b> failure</p></body></html>".as_slice(),
            4096,
        ),
        ("404 Missing", b"missing".as_slice(), 4096),
        ("503 Unavailable", b"".as_slice(), 4096),
        ("500 Error", b"0123456789abcdef".as_slice(), 7),
    ] {
        let (url, task) = server(move |stream| {
            let mut reader = BufReader::new(stream);
            headers(&mut reader);
            reply(reader.get_mut(), status, body);
        });
        let mut cfg = config();
        cfg.error_body_bytes = limit;
        let error = download_bytes(Request::new(url).unwrap(), cfg).unwrap_err();
        let TransportError::Http(error) = error else {
            panic!("unexpected {error:?}")
        };
        assert_eq!(error.body, body[..body.len().min(limit)]);
        assert_eq!(error.truncated, body.len() > limit);
        if body.starts_with(b"{") {
            assert_eq!(error.message, "failure");
            assert_eq!(error.hint.as_deref(), Some("try config"));
            assert_eq!(error.trace.as_deref(), Some("stack"));
        }
        task.join().unwrap();
    }
}

#[test]
fn does_not_follow_redirects() {
    let (url, task) = server(|stream| {
        let mut reader = BufReader::new(stream);
        headers(&mut reader);
        reader
            .get_mut()
            .write_all(
                b"HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:1/\r\nContent-Length: 0\r\n\r\n",
            )
            .unwrap();
    });
    assert!(
        matches!(download_bytes(Request::new(url).unwrap(), config()), Err(TransportError::Http(e)) if e.status == 302)
    );
    task.join().unwrap();
}

#[test]
fn detects_truncated_successful_http_body() {
    let (url, task) = server(|stream| {
        let mut reader = BufReader::new(stream);
        headers(&mut reader);
        reader
            .get_mut()
            .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\nshort")
            .unwrap();
    });
    assert!(matches!(
        download_bytes(Request::new(url).unwrap(), config()),
        Err(TransportError::Network(_))
    ));
    task.join().unwrap();
}

#[test]
fn cancellation_and_timeout_close_stalled_socket_and_errors_are_sticky() {
    for cancel in [true, false] {
        let (url, task) = server(|stream| {
            let mut reader = BufReader::new(stream);
            headers(&mut reader);
            let mut buf = [0; 1];
            assert_eq!(
                reader.read(&mut buf).unwrap(),
                0,
                "client must close socket"
            );
        });
        let mut cfg = config();
        cfg.transfer_timeout = Some(Duration::from_millis(300));
        let mut download = Download::open(Request::new(url).unwrap(), cfg).unwrap();
        let started = Instant::now();
        let error = download
            .read(&mut [0; 8], &mut || {
                assert!(tokio::runtime::Handle::try_current().is_err());
                cancel && started.elapsed() > Duration::from_millis(100)
            })
            .unwrap_err();
        assert_eq!(
            error,
            if cancel {
                TransportError::Cancelled
            } else {
                TransportError::Timeout
            }
        );
        assert!(started.elapsed() < Duration::from_secs(2));
        assert_eq!(
            download.read(&mut [0; 8], &mut || false).unwrap_err(),
            error
        );
        task.join().unwrap();
    }
}

#[test]
fn cancellation_before_io_never_starts_a_request() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    listener.set_nonblocking(true).unwrap();
    let url = format!("http://{}/", listener.local_addr().unwrap());
    let mut download = Download::open(Request::new(&url).unwrap(), config()).unwrap();
    assert_eq!(
        download.read(&mut [0; 1], &mut || true),
        Err(TransportError::Cancelled)
    );
    let mut upload = Upload::open(Request::new(url).unwrap(), config()).unwrap();
    assert_eq!(
        upload.write(b"x", &mut || true),
        Err(TransportError::Cancelled)
    );
    assert_eq!(
        listener.accept().unwrap_err().kind(),
        std::io::ErrorKind::WouldBlock
    );
}

#[test]
fn interrupted_upload_is_not_retried() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    listener.set_nonblocking(true).unwrap();
    let url = format!("http://{}/", listener.local_addr().unwrap());
    let server = thread::spawn(move || {
        let started = Instant::now();
        let stream = loop {
            match listener.accept() {
                Ok((stream, _)) => break stream,
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                    assert!(started.elapsed() < Duration::from_secs(6));
                    thread::sleep(Duration::from_millis(5));
                }
                Err(e) => panic!("accept: {e}"),
            }
        };
        stream
            .set_read_timeout(Some(Duration::from_secs(5)))
            .unwrap();
        let mut reader = BufReader::new(stream);
        headers(&mut reader);
        reader
            .get_mut()
            .write_all(b"HTTP/1.1 100 Continue\r\n\r\n")
            .unwrap();
        assert!(!read_chunks(&mut reader).is_empty());
        drop(reader); // Data accepted, acknowledgement lost.
        thread::sleep(Duration::from_millis(150));
        assert_eq!(
            listener.accept().unwrap_err().kind(),
            std::io::ErrorKind::WouldBlock
        );
    });
    let mut upload = Upload::open(Request::new(url).unwrap(), config()).unwrap();
    upload
        .writer(&mut || false)
        .write_all(b"do not duplicate this row")
        .unwrap();
    let error = upload.finish(&mut || false).unwrap_err();
    assert!(matches!(error, TransportError::Network(_)));
    assert_eq!(upload.finish(&mut || false).unwrap_err(), error);
    server.join().unwrap();
}

#[test]
fn rejects_early_upload_success() {
    let (url, task) = server(|stream| {
        let mut reader = BufReader::new(stream);
        headers(&mut reader);
        reply(reader.get_mut(), "200 OK", b"");
        let mut rest = Vec::new();
        let _ = reader.read_to_end(&mut rest);
    });
    let mut upload = Upload::open(Request::new(url).unwrap(), config()).unwrap();
    let error = loop {
        if let Err(error) = upload.write(&[42; 4096], &mut || false) {
            break error;
        }
    };
    assert_eq!(error, TransportError::PrematureResponse);
    assert_eq!(upload.finish(&mut || false).unwrap_err(), error);
    task.join().unwrap();
}

#[test]
fn validates_upload_acknowledgement_after_end_of_stream() {
    for status in ["200 OK", "500 Error"] {
        let (url, task) = server(move |stream| {
            let mut reader = BufReader::new(stream);
            headers(&mut reader);
            reader
                .get_mut()
                .write_all(b"HTTP/1.1 100 Continue\r\n\r\n")
                .unwrap();
            assert_eq!(read_chunks(&mut reader), b"row");
            write!(
                reader.get_mut(),
                "HTTP/1.1 {status}\r\nContent-Length: 100\r\n\r\nshort"
            )
            .unwrap();
        });
        let mut upload = Upload::open(Request::new(url).unwrap(), config()).unwrap();
        upload.writer(&mut || false).write_all(b"row").unwrap();
        let error = upload.finish(&mut || false).unwrap_err();
        if status.starts_with("200") {
            assert!(matches!(error, TransportError::Network(_)));
        } else {
            assert!(matches!(error, TransportError::Http(e) if e.status == 500 && e.truncated));
        }
        task.join().unwrap();
    }
}

#[test]
fn drop_and_cancel_abort_upload_without_sending_end_of_stream() {
    use std::sync::atomic::{AtomicBool, Ordering};
    for cancel in [false, true] {
        let received_headers = Arc::new(AtomicBool::new(false));
        let started_on_server = received_headers.clone();
        let (url, task) = server(move |stream| {
            let mut reader = BufReader::new(stream);
            headers(&mut reader);
            started_on_server.store(true, Ordering::SeqCst);
            // Stall the consumer to exercise upload backpressure and cancellation.
            thread::sleep(Duration::from_millis(300));
            let mut partial = Vec::new();
            let _ = reader.read_to_end(&mut partial); // EOF or connection reset.
            assert!(
                !partial.ends_with(b"0\r\n\r\n"),
                "abort must not finish HTTP"
            );
        });
        let mut upload = Upload::open(Request::new(url).unwrap(), config()).unwrap();
        let started = Instant::now();
        loop {
            let result = upload.write(&[b'x'; 8192], &mut || {
                cancel
                    && received_headers.load(Ordering::SeqCst)
                    && started.elapsed() >= Duration::from_millis(100)
            });
            if cancel {
                if let Err(error) = result {
                    assert_eq!(error, TransportError::Cancelled);
                    break;
                }
            } else {
                result.unwrap();
                if received_headers.load(Ordering::SeqCst) {
                    break;
                }
            }
        }
        drop(upload);
        assert!(started.elapsed() < Duration::from_secs(2));
        task.join().unwrap();
    }
}

#[test]
fn arrow_end_marker_does_not_hide_a_truncated_http_response() {
    let mut ipc = Vec::new();
    let batch = batch();
    {
        let mut writer = StreamWriter::try_new(&mut ipc, batch.schema().as_ref()).unwrap();
        writer.write(&batch).unwrap();
        writer.finish().unwrap();
    }
    let (url, task) = server(move |stream| {
        let mut reader = BufReader::new(stream);
        headers(&mut reader);
        write!(
            reader.get_mut(),
            "HTTP/1.1 200 OK\r\nContent-Length: {}\r\n\r\n",
            ipc.len() + 1
        )
        .unwrap();
        reader.get_mut().write_all(&ipc).unwrap();
    });
    let mut download = Download::open(Request::new(url).unwrap(), config()).unwrap();
    {
        let mut cancelled = || false;
        let mut decoder = StreamReader::try_new(download.reader(&mut cancelled), None).unwrap();
        assert_eq!(decoder.next().unwrap().unwrap(), batch);
        assert!(decoder.next().is_none());
    }
    assert!(matches!(
        download.read(&mut [0; 1], &mut || false),
        Err(TransportError::Network(_))
    ));
    task.join().unwrap();
}

fn batch() -> RecordBatch {
    let schema = Arc::new(Schema::new(vec![
        Field::new("id", DataType::Int32, true),
        Field::new("name", DataType::Utf8, true),
    ]));
    RecordBatch::try_new(
        schema,
        vec![
            Arc::new(Int32Array::from(vec![Some(1), None, Some(-3)])),
            Arc::new(StringArray::from(vec![
                Some("привет\0arrow"),
                Some(""),
                None,
            ])),
        ],
    )
    .unwrap()
}

#[test]
fn arrow_ipc_decodes_http_chunks_that_split_message_headers_and_buffers() {
    let expected = batch();
    let mut ipc = Vec::new();
    {
        let mut writer = StreamWriter::try_new(&mut ipc, expected.schema().as_ref()).unwrap();
        writer.write(&expected).unwrap();
        writer.write(&expected).unwrap();
        writer.finish().unwrap();
    }
    let (url, task) = server(move |stream| {
        let mut reader = BufReader::new(stream);
        headers(&mut reader);
        let stream = reader.get_mut();
        stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apache.arrow.stream\r\nTransfer-Encoding: chunked\r\n\r\n").unwrap();
        // Deliberately unrelated to IPC message boundaries, including 4-byte lengths.
        for part in ipc.chunks(3) {
            write!(stream, "{:x}\r\n", part.len()).unwrap();
            stream.write_all(part).unwrap();
            stream.write_all(b"\r\n").unwrap();
        }
        stream.write_all(b"0\r\n\r\n").unwrap();
    });
    let mut download = Download::open(Request::new(url).unwrap(), config()).unwrap();
    let mut cancelled = || false;
    {
        let mut reader = StreamReader::try_new(download.reader(&mut cancelled), None).unwrap();
        assert_eq!(reader.next().unwrap().unwrap(), expected);
        assert_eq!(reader.next().unwrap().unwrap(), expected);
        assert!(reader.next().is_none());
    }
    // Arrow EOS and HTTP EOF are separate; validate the latter as well.
    let mut tail = Vec::new();
    download
        .reader(&mut cancelled)
        .read_to_end(&mut tail)
        .unwrap();
    assert!(tail.is_empty());
    task.join().unwrap();
}

#[test]
fn arrow_ipc_encodes_directly_into_chunked_http_upload() {
    let expected = batch();
    let (url, task) = server(|stream| {
        let mut reader = BufReader::new(stream);
        let request = headers(&mut reader).to_ascii_lowercase();
        assert!(request.contains("content-type: application/vnd.apache.arrow.stream\r\n"));
        reader
            .get_mut()
            .write_all(b"HTTP/1.1 100 Continue\r\n\r\n")
            .unwrap();
        let ipc = read_chunks(&mut reader);
        let batches: Vec<_> = StreamReader::try_new(ipc.as_slice(), None)
            .unwrap()
            .map(Result::unwrap)
            .collect();
        reply(reader.get_mut(), "200 OK", b"");
        batches
    });
    let mut request = Request::new(url).unwrap();
    request
        .header("Content-Type", Some(b"application/vnd.apache.arrow.stream"))
        .unwrap();
    let mut upload = Upload::open(request, config()).unwrap();
    let mut cancelled = || false;
    {
        let mut writer =
            StreamWriter::try_new(upload.writer(&mut cancelled), expected.schema().as_ref())
                .unwrap();
        writer.write(&expected).unwrap();
        writer.finish().unwrap();
    }
    upload.finish(&mut cancelled).unwrap();
    assert_eq!(task.join().unwrap(), vec![expected]);
}

#[test]
fn complete_metadata_preserves_projection_and_database_bytes() {
    use pxf_core::request::{Column, Context, Metadata};
    let (url, task) = server(|stream| {
        let mut reader = BufReader::new(stream);
        let request = headers(&mut reader).to_ascii_lowercase();
        assert_eq!(request.matches("x-gp-attrs-proj-idx:").count(), 2);
        for expected in [
            "x-gp-attrs-proj-idx: 0\r\n",
            "x-gp-attrs-proj-idx: 1\r\n",
            "x-gp-attr-name0: %ff\r\n",
            "x-gp-attr-typemod1-0: 10\r\n",
            "x-gp-attr-typemod1-1: 3\r\n",
            "x-gp-has-filter: 1\r\n",
            "x-gp-filter: \r\n",
            "x-gp-options-profile: file%3acsv\r\n",
            "x-gp-data-dir: a%20%ff\r\n",
        ] {
            assert!(request.contains(expected), "{expected}: {request}");
        }
        reply(reader.get_mut(), "200 OK", b"");
    });
    let mut metadata = Metadata {
        context: Context {
            user: b"u".to_vec(),
            segment_id: b"0".to_vec(),
            segment_count: b"3".to_vec(),
            transaction_id: b"42".to_vec(),
            session_id: 7,
            command_count: 2,
            database_encoding: b"SQL_ASCII".to_vec(),
            alignment: 8,
        },
        host: b"localhost".to_vec(),
        port: b"5888".to_vec(),
        resource: b"a \xff".to_vec(),
        table: None,
        schema: None,
        data_encoding: None,
        wire_format: b"TEXT".to_vec(),
        columns: vec![
            Column {
                name: vec![255],
                type_oid: 23,
                type_name: b"int4".to_vec(),
                modifiers: vec![],
            },
            Column {
                name: b"amount".to_vec(),
                type_oid: 1700,
                type_name: b"numeric".to_vec(),
                modifiers: vec![10, 3],
            },
        ],
        projection: Some(vec![0, 1]),
        filter: Some(vec![]),
        options: vec![(b"profile".to_vec(), b"file:csv".to_vec())],
        original_uri: None,
    };
    assert!(download_bytes(metadata.request(&url).unwrap(), config())
        .unwrap()
        .is_empty());
    task.join().unwrap();
    metadata.projection = Some(vec![2]);
    assert!(metadata.request(&url).is_err());
    metadata.projection = None;
    metadata.context.user.clear();
    assert!(metadata.request(&url).is_err());
}
