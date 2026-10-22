#!/usr/bin/env python3
"""
Simple local development server for CKB Cell Laboratory & Wear OS Visualisers.
Allows previewing the HTML visualizer on desktop, mobile, or Pixel Watch over LAN.
"""

import http.server
import socketserver
import os
import sys

PORT = 8080

class Handler(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        # Enable CORS and caching headers for local testing
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Cache-Control', 'no-cache, no-store, must-revalidate')
        super().end_headers()

def run():
    os.chdir(os.path.dirname(os.path.abspath(__file__)))
    socketserver.TCPServer.allow_reuse_address = True
    try:
        with socketserver.TCPServer(("", PORT), Handler) as httpd:
            print(f"====================================================")
            print(f"  CKB CELL LABORATORY — DEV PREVIEW SERVER")
            print(f"  Serving on: http://localhost:{PORT}/")
            print(f"  Open in browser or test on Pixel Watch / mobile.")
            print(f"====================================================")
            httpd.serve_forever()
    except OSError as e:
        print(f"Port {PORT} in use, trying 8081...")
        with socketserver.TCPServer(("", 8081), Handler) as httpd:
            print(f"Serving on: http://localhost:8081/")
            httpd.serve_forever()

if __name__ == "__main__":
    run()
