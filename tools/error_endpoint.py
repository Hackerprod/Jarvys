#!/usr/bin/env python3
"""Minimal, standalone error-reporting endpoint for the Jarvys Android app.
Not related to any other service on this VPS. Accepts POST JSON error
reports and appends them to a local JSONL log file for inspection.
"""
import http.server
import json
import socketserver
import datetime
import os

LOG_PATH = "/root/Projects/Jarvys/tools/error_reports.jsonl"
PORT = 8090

class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != "/jarvys/errors":
            self.send_response(404)
            self.end_headers()
            return
        length = int(self.headers.get("Content-Length", 0))
        if length > 200_000:
            self.send_response(413)
            self.end_headers()
            return
        raw = self.rfile.read(length)
        try:
            payload = json.loads(raw.decode("utf-8"))
        except Exception:
            payload = {"raw": raw.decode("utf-8", errors="replace")}
        record = {
            "received_at": datetime.datetime.utcnow().isoformat() + "Z",
            "remote_addr": self.client_address[0],
            "payload": payload,
        }
        os.makedirs(os.path.dirname(LOG_PATH), exist_ok=True)
        with open(LOG_PATH, "a", encoding="utf-8") as f:
            f.write(json.dumps(record, ensure_ascii=False) + "\n")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"ok":true}')

    def do_GET(self):
        if self.path == "/jarvys/errors/health":
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"ok")
            return
        self.send_response(404)
        self.end_headers()

    def log_message(self, format, *args):
        pass  # keep stdout/systemd journal quiet; the JSONL file is the real log

class ThreadingServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True

if __name__ == "__main__":
    server = ThreadingServer(("0.0.0.0", PORT), Handler)
    server.serve_forever()
