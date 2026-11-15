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
if __name__ == "__main__":
    unittest.main()
