#!/usr/bin/env python3
"""Loopback-only HTTP fixture for executing the live WKWebView contracts."""
import http.server
import pathlib
import sys

class Fixture(http.server.BaseHTTPRequestHandler):
    def reply(self, data, content_type="text/html"):
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Security-Policy", "script-src 'self'; object-src 'none'")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/first":
            self.reply(b'''<div class=row><span>First</span><button id=first>Go first</button></div>
                <div class=row><span>Second</span><button id=second>Go second</button></div>
                <div><span id=nested>Unique text</span></div><a href=/next>Follow</a>''')
        elif self.path == "/next":
            self.reply(b"<title>Next</title><h1>Arrived</h1>")
        else:
            self.reply(b"echo", "text/plain")

    def do_POST(self):
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            chunks = []
            total = 0
            while True:
                size = int(self.rfile.readline().split(b";", 1)[0].strip(), 16)
                if size == 0:
                    while self.rfile.readline().strip():
                        pass
                    break
                total += size
                if total > 1024 * 1024:
                    self.send_error(413)
                    return
                chunks.append(self.rfile.read(size))
                self.rfile.read(2)
            body = b"".join(chunks)
        else:
            size = int(self.headers.get("Content-Length", "0"))
            if size > 1024 * 1024:
                self.send_error(413)
                return
            body = self.rfile.read(size)
        self.reply(body, "application/octet-stream")

    def log_message(self, *args):
        pass

server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Fixture)
pathlib.Path(sys.argv[1]).write_text(str(server.server_port))
server.serve_forever()
