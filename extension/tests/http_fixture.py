#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.
"""Deterministic PXF HTTP peer for database lifecycle tests, not a PXF emulator."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import select
import threading
import time
from urllib.parse import unquote_to_bytes


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    lock = threading.Lock()

    def context(self):
        result = {key.lower(): unquote_to_bytes(value).decode("utf-8", "backslashreplace")
                  for key, value in self.headers.items()}
        result["projection"] = self.headers.get_all("X-GP-ATTRS-PROJ-IDX", [])
        result["method"] = self.command
        result["path"] = self.path
        return result

    def record(self, item):
        with self.lock:
            with (self.server.directory / "requests.jsonl").open("a") as log:
                log.write(json.dumps(item) + "\n")

    def reply(self, body, status=200):
        self.send_response(status)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        context = self.context()
        self.record(context)
        resource = context.get("x-gp-data-dir", "")
        if resource == "/stall":
            # The cancellation test must close this connection, not wait for data.
            select.select([self.connection], [], [], 10)
            closed = self.connection.recv(1) == b""
            self.record({"event": "stall_closed", "closed": closed})
            return
        if resource == "/error":
            self.reply(b'{"message":"fixture error","hint":"fixture hint","trace":"fixture trace"}', 500)
        elif context.get("x-gp-segment-id") not in ("-1", "0"):
            self.reply(b"")
        elif resource == "/badrows":
            self.reply(b"bad,first\n1,one\noops,second\n2,two\n")
        elif resource == "/latebad":
            self.reply(b"1,one\n2,two\nbad,third\n")
        elif resource == "/marker":
            self.reply(b",,PXFERRMSG> fixture data error\n")
        elif resource == "/nonutf8":
            self.reply(b"1,\xff\n2,\xe9\n")
        elif resource == "/stream":
            self.send_response(200)
            self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()
            chunk = b"1,one\n" * 8192
            try:
                for _ in range(1000):
                    self.wfile.write(f"{len(chunk):x}\r\n".encode() + chunk + b"\r\n")
                    self.wfile.flush()
                    time.sleep(.01)
                self.wfile.write(b"0\r\n\r\n")
            except (BrokenPipeError, ConnectionResetError):
                self.record({"event": "stream_closed"})
        else:
            self.reply((self.server.directory / "read.csv").read_bytes())

    def do_POST(self):
        context = self.context()
        body = bytearray()
        while True:
            line = self.rfile.readline()
            if not line:
                self.record({"event": "upload_aborted"})
                return
            count = int(line.strip().split(b";", 1)[0], 16)
            if count == 0:
                assert self.rfile.readline() == b"\r\n"
                break
            body.extend(self.rfile.read(count))
            assert self.rfile.read(2) == b"\r\n"
        context["body"] = body.decode("utf-8", "backslashreplace")
        self.record(context)
        self.reply(b"ok")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=5889)
    parser.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args()
    args.directory.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.directory = args.directory
    server.serve_forever()
