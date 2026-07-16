from __future__ import annotations

from pathlib import Path
import socket
import sys
import threading
import unittest


LIVE_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(LIVE_DIR))

from rcon import (  # noqa: E402
    RconClient,
    RconPacket,
    SERVERDATA_AUTH,
    SERVERDATA_AUTH_RESPONSE,
    SERVERDATA_EXECCOMMAND,
    SERVERDATA_RESPONSE_VALUE,
    encode_packet,
    receive_packet,
)


class FakeRconServer:
    def __init__(self, password: str, response: str) -> None:
        self.password = password
        self.response = response
        self.error: BaseException | None = None
        self.listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen(1)
        self.port = int(self.listener.getsockname()[1])
        self.thread = threading.Thread(target=self._serve, daemon=True)

    def __enter__(self) -> "FakeRconServer":
        self.thread.start()
        return self

    def __exit__(self, _exc_type: object, _exc: object, _traceback: object) -> None:
        self.thread.join(5)
        self.listener.close()
        if self.error is not None:
            raise self.error

    def _serve(self) -> None:
        try:
            connection, _address = self.listener.accept()
            with connection:
                auth = receive_packet(connection)
                if auth.packet_type != SERVERDATA_AUTH:
                    raise AssertionError(f"Unexpected auth packet: {auth}")
                auth_id = auth.request_id if auth.payload == self.password else -1
                connection.sendall(encode_packet(RconPacket(auth_id, SERVERDATA_AUTH_RESPONSE, "")))
                if auth_id == -1:
                    return
                command = receive_packet(connection)
                if command.packet_type != SERVERDATA_EXECCOMMAND or command.payload != "mspt":
                    raise AssertionError(f"Unexpected command packet: {command}")
                connection.sendall(
                    encode_packet(RconPacket(command.request_id, SERVERDATA_RESPONSE_VALUE, self.response))
                )
        except BaseException as exception:
            self.error = exception


class RconClientTest(unittest.TestCase):
    def test_authenticates_and_executes_command(self) -> None:
        with FakeRconServer("secret", "raw mspt response") as server:
            with RconClient("127.0.0.1", server.port, "secret") as client:
                self.assertEqual("raw mspt response", client.command("mspt"))

    def test_rejects_bad_password(self) -> None:
        with FakeRconServer("secret", "unused") as server:
            with self.assertRaises(PermissionError):
                with RconClient("127.0.0.1", server.port, "wrong"):
                    pass

    def test_packet_codec_preserves_utf8(self) -> None:
        left, right = socket.socketpair()
        try:
            packet = RconPacket(42, SERVERDATA_RESPONSE_VALUE, "牌局 MSPT")
            left.sendall(encode_packet(packet))
            self.assertEqual(packet, receive_packet(right))
        finally:
            left.close()
            right.close()


if __name__ == "__main__":
    unittest.main()
