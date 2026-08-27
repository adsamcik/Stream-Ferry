from __future__ import annotations

import importlib.util
import http.client
import json
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path


SERVER_PATH = Path(__file__).parents[1] / "demo_server.py"
SPEC = importlib.util.spec_from_file_location("stream_ferry_demo_server", SERVER_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class DemoServerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.video = root / "fixture.mp4"
        self.poster = root / "poster.jpg"
        self.video.write_bytes(bytes(range(256)) * 32)
        self.poster.write_bytes(b"jpeg-fixture")
        self.state = MODULE.DemoState(self.video, self.poster, 0)
        self.server = MODULE.DemoServer(("127.0.0.1", 0), MODULE.DemoHandler, self.state)
        self.state.renderer_port = self.server.server_port
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.temp.cleanup()

    def test_jellyfin_login_browse_and_playback_info(self) -> None:
        info = self.json_request("/System/Info/Public")
        self.assertEqual("stream-ferry-demo-server", info["Id"])

        login = self.json_request(
            "/Users/AuthenticateByName",
            {"Username": "demo", "Pw": "streamferry"},
        )
        self.assertEqual("demo-user", login["User"]["Id"])

        views = self.json_request("/Users/demo-user/Views")
        self.assertEqual("Demo Movies", views["Items"][0]["Name"])

        playback = self.json_request("/Items/big-buck-bunny-playback/PlaybackInfo", {})
        self.assertTrue(playback["MediaSources"][0]["SupportsDirectPlay"])
        self.assertEqual(self.video.stat().st_size, playback["MediaSources"][0]["Size"])

    def test_video_range_contract(self) -> None:
        request = urllib.request.Request(
            self.base + "/Videos/big-buck-bunny-playback/stream.mp4",
            headers={"Range": "bytes=10-29"},
        )
        with urllib.request.urlopen(request) as response:
            self.assertEqual(206, response.status)
            self.assertEqual("bytes 10-29/8192", response.headers["Content-Range"])
            self.assertEqual(self.video.read_bytes()[10:30], response.read())

    def test_playback_info_consumes_body_before_reused_media_connection(self) -> None:
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port)
        body = json.dumps({"DeviceProfile": {"DirectPlayProfiles": [{"Container": "mp4"}]}})
        connection.request(
            "POST",
            "/Items/big-buck-bunny-playback/PlaybackInfo",
            body=body,
            headers={"Content-Type": "application/json", "Content-Length": str(len(body))},
        )
        response = connection.getresponse()
        self.assertEqual(200, response.status)
        response.read()

        connection.request("GET", "/Videos/big-buck-bunny-playback/stream.mp4", headers={"Range": "bytes=0-15"})
        media = connection.getresponse()
        self.assertEqual(206, media.status)
        self.assertEqual(self.video.read_bytes()[:16], media.read())
        connection.close()

    def test_dlna_control_state(self) -> None:
        self.soap("SetAVTransportURI", "<InstanceID>0</InstanceID><CurrentURI>http://10.0.2.15:1234/proxy</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>")
        self.soap("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
        state = self.json_request("/api/state")
        self.assertEqual("PLAYING", state["transportState"])
        self.assertTrue(state["proxyUrlReceived"])

    def soap(self, action: str, inner: str) -> str:
        envelope = f'<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:{action} xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">{inner}</u:{action}></s:Body></s:Envelope>'
        request = urllib.request.Request(
            self.base + "/upnp/avtransport/control",
            data=envelope.encode(),
            headers={"SOAPACTION": f'"urn:schemas-upnp-org:service:AVTransport:1#{action}"', "Content-Type": "text/xml"},
        )
        with urllib.request.urlopen(request) as response:
            return response.read().decode()

    def json_request(self, path: str, body=None):
        data = None if body is None else json.dumps(body).encode()
        headers = {} if data is None else {"Content-Type": "application/json"}
        with urllib.request.urlopen(urllib.request.Request(self.base + path, data=data, headers=headers)) as response:
            return json.loads(response.read())


if __name__ == "__main__":
    unittest.main()
