#!/usr/bin/env python3
"""
Local development server for CKB Cell Laboratory & Wear OS Visualisers.
Automatically detects a free port and allows previewing on desktop, mobile, or Pixel Watch.
"""

import http.server
import socketserver
import os
import sys
import socket

DEFAULT_START_PORT = 8084

class Handler(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        # Enable CORS and caching headers for local testing
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Cache-Control', 'no-cache, no-store, must-revalidate')
        super().end_headers()

def is_port_available(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        try:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(("", port))
            return True
        except OSError:
            return False

def find_available_port(start_port=DEFAULT_START_PORT, max_port=8150):
    for port in range(start_port, max_port + 1):
        if is_port_available(port):
            return port
    # Fallback to OS assigned port
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("", 0))
        return s.getsockname()[1]

def run():
    os.chdir(os.path.dirname(os.path.abspath(__file__)))
    socketserver.TCPServer.allow_reuse_address = True

    # Check CLI argument or search for a free port
    if len(sys.argv) > 1 and sys.argv[1].isdigit():
        target_port = int(sys.argv[1])
        if is_port_available(target_port):
            port = target_port
        else:
            print(f"Port {target_port} is currently in use. Searching for next free port...")
            port = find_available_port(target_port + 1)
    else:
        port = find_available_port(DEFAULT_START_PORT)

    try:
        with socketserver.TCPServer(("", port), Handler) as httpd:
            print("=" * 60, flush=True)
            print("  CKB CELL LABORATORY — DEV PREVIEW SERVER", flush=True)
            print(f"  URL: http://localhost:{port}/", flush=True)
            print("  Open in browser or test on Pixel Watch / mobile.", flush=True)
            print("=" * 60, flush=True)
            httpd.serve_forever()
    except Exception as e:
        print(f"Error starting server on port {port}: {e}", flush=True)
        # Try OS dynamic port
        fallback_port = find_available_port(0)
        with socketserver.TCPServer(("", fallback_port), Handler) as httpd:
            print(f"Started fallback server at: http://localhost:{fallback_port}/", flush=True)
            httpd.serve_forever()

if __name__ == "__main__":
    run()
