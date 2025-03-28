import socket
import select
import sys
from .util import flatten_parameters_to_bytestring


class RequestError(Exception):
    pass


class Connection:
    """Connection to a Minecraft Pi game"""

    RequestFailed = "Fail"

    def __init__(self, address, port, debug=False):
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.socket.settimeout(10)
        self.socket.connect((address, port))
        self.socket.settimeout(60)  # doc suggests None for makefile
        self.lastSent = ""
        self.debug = debug

    def drain(self):
        """Drains the socket of incoming data"""
        while True:
            readable, _, _ = select.select([self.socket], [], [], 0.0)
            if not readable:
                break
            data = self.socket.recv(1500)
            if self.debug:
                e = "Drained Data: <%s>\n" % data.strip()
                e += "Last Message: <%s>\n" % self.lastSent.strip()
                sys.stderr.write(e)

    def send(self, f, *data):
        """
        Sends data. Note that a trailing newline '\n' is added here
        """
        s = b"".join([f, b"(", flatten_parameters_to_bytestring(data), b")", b"\n"])
        # print(s)
        self._send(s)

    def _send(self, s):
        """
        The actual socket interaction from self.send, extracted for easier mocking
        and testing
        """
        self.drain()
        self.lastSent = s

        self.socket.sendall(s)

    def receive(self):
        """Receives data. Note that the trailing newline '\n' is trimmed"""
        s = self.socket.makefile("r").readline().rstrip("\n")
        if s == Connection.RequestFailed:
            raise RequestError(f"{self.lastSent.strip()} failed")
        return s

    def sendReceive(self, *data):
        """Sends and receive data"""
        self.send(*data)
        return self.receive()
