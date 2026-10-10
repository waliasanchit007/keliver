#!/usr/bin/env python3
"""A static HTTPS file server for the W3 checks: serves <root> as-is, the way
S3, GCS, GitHub Pages or nginx would, and logs every request to <log>.

    static_https.py <root> <port> <cert.pem> <key.pem> <log>

No index generation, no Keliver logic: whatever keliver-publish wrote is all a
host can get. Binds 127.0.0.1 only (the emulator reaches it as 10.0.2.2).

W6: POST /report stands in for an app's report collector. Each body (a host's
JSON report, at most 4 KiB) is logged as one line, "REPORT <body>"; the reply
is 204. Nothing else accepts a POST.
"""
import functools, http.server, ssl, sys

root, port, cert, key, log = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4], sys.argv[5]
out = open(log, "a", buffering=1)


class Handler(http.server.SimpleHTTPRequestHandler):
    def log_request(self, code="-", size="-"):
        out.write(f"{self.command} {self.path} {code}\n")

    def log_message(self, fmt, *args):
        pass

    def do_POST(self):
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = -1
        if self.path != "/report" or not 0 < length <= 4096:
            self.send_response(404 if self.path != "/report" else 413)
            self.end_headers()
            return
        body = self.rfile.read(length).decode("utf-8", "replace").replace("\r", " ").replace("\n", " ")
        out.write(f"REPORT {body}\n")
        self.send_response(204)
        self.end_headers()


ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.minimum_version = ssl.TLSVersion.TLSv1_2
ctx.load_cert_chain(cert, key)
server = http.server.ThreadingHTTPServer(("127.0.0.1", port), functools.partial(Handler, directory=root))
server.socket = ctx.wrap_socket(server.socket, server_side=True)
out.write(f"serving {root} on https://127.0.0.1:{port}\n")
server.serve_forever()
