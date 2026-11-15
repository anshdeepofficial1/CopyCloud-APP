import socket
import unittest
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from copycloud import derive, seal, read_frame, write_frame

class WireTest(unittest.TestCase):
    def test_authenticated_encryption(self):
        key = derive(bytes(32), bytes(range(32)), "client")
        packet = seal(key, {"seq": 1, "type": "join"})
        self.assertIn(b"join", AESGCM(key).decrypt(packet[:12], packet[12:], None))
        with self.assertRaises(Exception):
            AESGCM(key).decrypt(packet[:12], packet[12:-1] + bytes([packet[-1] ^ 1]), None)
    def test_framing(self):
        a, b = socket.socketpair()
        try:
            write_frame(a, b"test")
            self.assertEqual(read_frame(b), b"test")
        finally:
            a.close()
            b.close()

class GroupTest(unittest.TestCase):
    def test_two_devices_and_clipboard_relay(self):
        import json
        import os
        import queue
        import threading
        from copycloud import Relay
        relay = Relay.__new__(Relay)
        relay.secret = os.urandom(32)
        relay.events = queue.Queue()
        relay.peers = set()
        relay.lock = threading.Lock()
        relay.slots = threading.BoundedSemaphore(2)
        clients = []
        threads = []
        def join(name):
            server, client = socket.socketpair()
            client.settimeout(3)
            relay.slots.acquire()
            thread = threading.Thread(target=relay.handle, args=(server,), daemon=True)
            thread.start()
            clients.append(client)
            threads.append(thread)
            challenge = read_frame(client)
            tx = derive(relay.secret, challenge, "client")
            rx = derive(relay.secret, challenge, "server")
            write_frame(client, seal(tx, {"type": "join", "name": name, "seq": 1}))
            def receive():
                packet = read_frame(client)
                return json.loads(AESGCM(rx).decrypt(packet[:12], packet[12:], None))
            self.assertEqual(receive()["type"], "welcome")
            self.assertEqual(receive()["type"], "peers")
            return client, tx, receive
        try:
            a, key_a, read_a = join("Phone A")
            b, key_b, read_b = join("Phone B")
            self.assertEqual(read_a()["count"], 3)
            write_frame(a, seal(key_a, {"type": "clip", "text": "Hello from A", "seq": 2}))
            self.assertEqual(read_b()["text"], "Hello from A")
            write_frame(b, seal(key_b, {"type": "clip", "text": "Hello from B", "seq": 2}))
            self.assertEqual(read_a()["text"], "Hello from B")
            relay.broadcast({"type": "clip", "text": "From desktop", "source": "Desktop"})
            self.assertEqual(read_a()["text"], "From desktop")
            self.assertEqual(read_b()["text"], "From desktop")
            # A replay must close the originating authenticated session.
            write_frame(a, seal(key_a, {"type": "clip", "text": "Replay", "seq": 2}))
            self.assertEqual(a.recv(1), b"")
            self.assertEqual(read_b()["count"], 2)
        finally:
            for client in clients:
                client.close()
            for thread in threads:
                thread.join(timeout=4)

if __name__ == "__main__":
    unittest.main()
