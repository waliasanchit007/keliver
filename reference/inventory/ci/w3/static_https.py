#!/usr/bin/env python3
"""A static HTTPS file server for the W3 checks: serves <root> as-is, the way
S3, GCS, GitHub Pages or nginx would, and logs every request to <log>.

    static_https.py <root> <port> <cert.pem> <key.pem> <log>

No index generation, no Keliver logic: whatever keliver-publish wrote is all a
host can get. Binds 127.0.0.1 only (the emulator reaches it as 10.0.2.2).
"""
import functools, http.server, ssl, sys

root, port, cert, key, log = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4], sys.argv[5]
out = open(log, "a", buffering=1)


class Handler(http.server.SimpleHTTPRequestHandler):
    def log_request(self, code="-", size="-"):
        out.write(f"{self.command} {self.path} {code}\n")

    def log_message(self, fmt, *args):
        pass


ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.minimum_version = ssl.TLSVersion.TLSv1_2
ctx.load_cert_chain(cert, key)
server = http.server.ThreadingHTTPServer(("127.0.0.1", port), functools.partial(Handler, directory=root))
server.socket = ctx.wrap_socket(server.socket, server_side=True)
out.write(f"serving {root} on https://127.0.0.1:{port}\n")
server.serve_forever()
