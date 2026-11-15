"""Local CopyCloud relay and opt-in system clipboard bridge. Run on a trusted LAN."""
import base64
import hmac
import hashlib
import json
import os
import queue
import socket
import struct
import threading
import tkinter as tk
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAX = 300_000
LIMIT = 60_000
PORT = 47631

def derive(secret, challenge, label):
    return hmac.new(secret, challenge + label.encode(), hashlib.sha256).digest()

def seal(key, msg):
    nonce = os.urandom(12)
    return nonce + AESGCM(key).encrypt(nonce, json.dumps(msg, ensure_ascii=False).encode(), None)

def read_exact(sock, n):
    result = bytearray()
    while len(result) < n:
        chunk = sock.recv(n - len(result))
        if not chunk:
            raise EOFError()
        result.extend(chunk)
    return bytes(result)

def read_frame(sock):
    n = struct.unpack(">I", read_exact(sock, 4))[0]
    if not 1 <= n <= MAX:
        raise ValueError("Invalid frame")
    return read_exact(sock, n)

def write_frame(sock, data):
    if not 1 <= len(data) <= MAX:
        raise ValueError("Invalid frame")
    sock.sendall(struct.pack(">I", len(data)) + data)

class Peer:
    def __init__(self, conn, key):
        self.conn, self.key, self.seq = conn, key, 0
        self.lock = threading.Lock()
        self.name = "Device"

    def send(self, msg):
        with self.lock:
            self.seq += 1
            write_frame(self.conn, seal(self.key, dict(msg, seq=self.seq)))

class Relay:
    def __init__(self, events):
        self.secret = os.urandom(32)
        self.events = events
        self.peers = set()
        self.lock = threading.Lock()
        self.server = socket.socket()
        self.server.bind(("0.0.0.0", PORT))
        self.server.listen(12)
        self.slots = threading.BoundedSemaphore(12)

    def snapshot(self):
        with self.lock:
            return list(self.peers)

    def broadcast(self, msg, skip=None):
        for p in self.snapshot():
            if p is not skip:
                try:
                    p.send(msg)
                except (OSError, ValueError):
                    p.conn.close()

    def announce(self):
        count = len(self.snapshot())
        self.events.put(("count", count))
        self.broadcast({"type": "peers", "count": count + 1})

    def handle(self, conn):
        peer = None
        try:
            conn.settimeout(8)
            challenge = os.urandom(32)
            write_frame(conn, challenge)
            rx = derive(self.secret, challenge, "client")
            peer = Peer(conn, derive(self.secret, challenge, "server"))
            received = 0
            while True:
                data = read_frame(conn)
                msg = json.loads(AESGCM(rx).decrypt(data[:12], data[12:], None))
                received += 1
                if msg["seq"] != received:
                    raise ValueError("Replay or sequence error")
                if received == 1:
                    if msg["type"] != "join":
                        raise ValueError("Join required")
                    peer.name = str(msg["name"])[:80]
                    peer.send({"type": "welcome"})
                    conn.settimeout(10)
                    with self.lock:
                        self.peers.add(peer)
                    self.announce()
                elif msg["type"] == "clip":
                    text = msg["text"]
                    if isinstance(text, str) and 0 < len(text.encode()) <= LIMIT:
                        self.events.put(("clip", text))
                        self.broadcast({"type": "clip", "text": text, "source": peer.name}, peer)
                # Idle clients must remain connected; a socket read timeout is handled below.
                conn.settimeout(None)
        except Exception:
            pass
        finally:
            conn.close()
            if peer:
                with self.lock:
                    self.peers.discard(peer)
                self.announce()
            self.slots.release()

    def run(self):
        while True:
            conn, _ = self.server.accept()
            if not self.slots.acquire(blocking=False):
                conn.close()
                continue
            threading.Thread(target=self.handle, args=(conn,), daemon=True).start()

def local_ip():
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        try:
            s.connect(("192.0.2.1", 9))
            return s.getsockname()[0]
        except OSError:
            return socket.gethostbyname(socket.gethostname())

def main():
    events = queue.Queue()
    relay = Relay(events)
    threading.Thread(target=relay.run, daemon=True).start()
    root = tk.Tk()
    root.title("CopyCloud • Desktop alpha")
    root.geometry("640x290")
    root.configure(bg="#10191a")
    invite = "CC1|{}:{}|{}".format(local_ip(), PORT, base64.urlsafe_b64encode(relay.secret).decode().rstrip("="))
    tk.Label(root, text="CopyCloud", font=("Segoe UI", 26, "bold"), fg="#c8ff77", bg="#10191a").pack(pady=(20, 4))
    tk.Label(root, text="Private LAN group · Keep this invite secret", fg="white", bg="#10191a").pack()
    entry = tk.Entry(root, width=75)
    entry.insert(0, invite)
    entry.configure(state="readonly")
    entry.pack(pady=12)
    status = tk.StringVar(value="0 connected phones")
    tk.Label(root, textvariable=status, fg="white", bg="#10191a").pack()
    enabled = tk.BooleanVar(value=False)
    tk.Checkbutton(root, text="Enable live text clipboard synchronization", variable=enabled).pack(pady=12)
    last = None
    was_enabled = False
    def poll():
        nonlocal last, was_enabled
        if enabled.get() and not was_enabled:
            try:
                last = root.clipboard_get()
            except tk.TclError:
                last = None
        was_enabled = enabled.get()
        try:
            while True:
                kind, data = events.get_nowait()
                if kind == "count":
                    status.set(f"{data} connected phones")
                elif enabled.get():
                    root.clipboard_clear()
                    root.clipboard_append(data)
                    last = data
        except queue.Empty:
            pass
        if enabled.get():
            try:
                text = root.clipboard_get()
                if text != last:
                    last = text
                    if 0 < len(text.encode()) <= LIMIT:
                        threading.Thread(target=relay.broadcast, args=({"type": "clip", "text": text, "source": "Desktop"},), daemon=True).start()
            except tk.TclError:
                pass
        root.after(300, poll)
    root.after(300, poll)
    root.mainloop()

if __name__ == "__main__":
    main()
