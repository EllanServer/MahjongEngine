"""Small standard-library RCON client used only from outside the Paper process."""

from __future__ import annotations

from dataclasses import dataclass
import socket
import struct


SERVERDATA_RESPONSE_VALUE = 0
SERVERDATA_EXECCOMMAND = 2
SERVERDATA_AUTH_RESPONSE = 2
SERVERDATA_AUTH = 3
MAX_PACKET_LENGTH = 10 * 1024 * 1024


@dataclass(frozen=True)
class RconPacket:
    request_id: int
    packet_type: int
    payload: str


def encode_packet(packet: RconPacket) -> bytes:
    payload = packet.payload.encode("utf-8")
    body = struct.pack("<ii", packet.request_id, packet.packet_type) + payload + b"\x00\x00"
    return struct.pack("<i", len(body)) + body


def _recv_exact(connection: socket.socket, length: int) -> bytes:
    chunks: list[bytes] = []
    remaining = length
    while remaining:
        chunk = connection.recv(remaining)
        if not chunk:
            raise ConnectionError(f"RCON socket closed with {remaining} bytes still expected")
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def receive_packet(connection: socket.socket) -> RconPacket:
    (length,) = struct.unpack("<i", _recv_exact(connection, 4))
    if length < 10 or length > MAX_PACKET_LENGTH:
        raise ValueError(f"Invalid RCON packet length: {length}")
    body = _recv_exact(connection, length)
    request_id, packet_type = struct.unpack("<ii", body[:8])
    if body[-2:] != b"\x00\x00":
        raise ValueError("RCON packet is missing its two NUL terminators")
    payload = body[8:-2].decode("utf-8", errors="replace")
    return RconPacket(request_id=request_id, packet_type=packet_type, payload=payload)


class RconClient:
    def __init__(self, host: str, port: int, password: str, timeout: float = 5.0) -> None:
        self.host = host
        self.port = port
        self.password = password
        self.timeout = timeout
        self._connection: socket.socket | None = None
        self._next_request_id = 10

    def __enter__(self) -> "RconClient":
        connection = socket.create_connection((self.host, self.port), timeout=self.timeout)
        connection.settimeout(self.timeout)
        self._connection = connection
        request_id = self._allocate_request_id()
        connection.sendall(encode_packet(RconPacket(request_id, SERVERDATA_AUTH, self.password)))
        response = receive_packet(connection)
        if response.request_id == -1:
            self.close()
            raise PermissionError("RCON authentication failed")
        if response.request_id != request_id or response.packet_type != SERVERDATA_AUTH_RESPONSE:
            self.close()
            raise ValueError(
                "Unexpected RCON authentication response "
                f"id={response.request_id} type={response.packet_type}"
            )
        return self

    def __exit__(self, _exc_type: object, _exc: object, _traceback: object) -> None:
        self.close()

    def _allocate_request_id(self) -> int:
        request_id = self._next_request_id
        self._next_request_id += 1
        return request_id

    def command(self, command: str) -> str:
        if self._connection is None:
            raise RuntimeError("RCON client is not connected")
        request_id = self._allocate_request_id()
        self._connection.sendall(
            encode_packet(RconPacket(request_id, SERVERDATA_EXECCOMMAND, command))
        )
        response = receive_packet(self._connection)
        if response.request_id != request_id or response.packet_type != SERVERDATA_RESPONSE_VALUE:
            raise ValueError(
                f"Unexpected RCON command response id={response.request_id} "
                f"type={response.packet_type} for request {request_id}"
            )
        return response.payload

    def close(self) -> None:
        if self._connection is not None:
            try:
                self._connection.close()
            finally:
                self._connection = None
