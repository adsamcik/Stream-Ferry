#!/usr/bin/env python3
"""Dependency-free Jellyfin + DLNA fixture for Stream Ferry demos and local tests."""

from __future__ import annotations

import argparse
import html
import json
import re
import signal
import threading
import time
import urllib.parse
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Optional


TICKS_PER_SECOND = 10_000_000
SERVER_ID = "stream-ferry-demo-server"
USER_ID = "demo-user"
ACCESS_TOKEN = "demo-token-local-only"
LIBRARY_ID = "demo-movies"
PLAYBACK_ID = "big-buck-bunny-playback"
DOWNLOAD_ID = "big-buck-bunny-download"
RENDERER_UDN = "uuid:stream-ferry-demo-tv"
RENDERER_USN = f"{RENDERER_UDN}::urn:schemas-upnp-org:device:MediaRenderer:1"


@dataclass
class DemoState:
    video: Path
    poster: Path
    renderer_port: int
    lock: threading.RLock = field(default_factory=threading.RLock)
    current_uri: str = ""
    current_title: str = "Nothing playing"
    transport_state: str = "STOPPED"
    volume: int = 42
    position_seconds: int = 0
    started_monotonic: Optional[float] = None
    playback_reports: list[dict[str, Any]] = field(default_factory=list)
    bytes_served: int = 0

    def set_uri(self, uri: str, title: str) -> None:
        with self.lock:
            self.current_uri = uri
            self.current_title = title or "Big Buck Bunny"
            self.transport_state = "STOPPED"
            self.position_seconds = 0
            self.started_monotonic = None

    def play(self) -> None:
        with self.lock:
            if self.transport_state != "PLAYING":
                self.started_monotonic = time.monotonic() - self.position_seconds
            self.transport_state = "PLAYING"

    def pause(self) -> None:
        with self.lock:
            self.position_seconds = self.position()
            self.started_monotonic = None
            self.transport_state = "PAUSED_PLAYBACK"

    def stop(self) -> None:
        with self.lock:
            self.position_seconds = self.position()
            self.started_monotonic = None
            self.transport_state = "STOPPED"

    def seek(self, seconds: int) -> None:
        with self.lock:
            self.position_seconds = max(0, min(30, seconds))
            if self.transport_state == "PLAYING":
                self.started_monotonic = time.monotonic() - self.position_seconds

    def position(self) -> int:
        with self.lock:
            if self.transport_state == "PLAYING" and self.started_monotonic is not None:
                return max(0, min(30, int(time.monotonic() - self.started_monotonic)))
            return self.position_seconds


class DemoServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address: tuple[str, int], handler: type[BaseHTTPRequestHandler], state: DemoState):
        super().__init__(address, handler)
        self.state = state


class DemoHandler(BaseHTTPRequestHandler):
    server: DemoServer
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt: str, *args: Any) -> None:
        print(f"[{self.log_date_time_string()}] {self.client_address[0]} {fmt % args}", flush=True)

    def do_HEAD(self) -> None:  # noqa: N802
        self._dispatch(head_only=True)

    def do_GET(self) -> None:  # noqa: N802
        self._dispatch(head_only=False)

    def do_POST(self) -> None:  # noqa: N802
        self._dispatch(head_only=False)

    def do_DELETE(self) -> None:  # noqa: N802
        self._dispatch(head_only=False)

    def _dispatch(self, head_only: bool) -> None:
        parsed = urllib.parse.urlsplit(self.path)
        path = parsed.path.rstrip("/") or "/"
        query = urllib.parse.parse_qs(parsed.query)

        if path == "/System/Info/Public" and self.command == "GET":
            self._json({"Id": SERVER_ID, "ServerName": "Stream Ferry Demo Library", "Version": "10.11.0"}, head_only)
        elif path == "/Users/AuthenticateByName" and self.command == "POST":
            body = self._read_json()
            if body.get("Username") != "demo" or body.get("Pw") != "streamferry":
                self._json({"Message": "Invalid demo credentials"}, head_only, HTTPStatus.UNAUTHORIZED)
            else:
                self._json({"AccessToken": ACCESS_TOKEN, "ServerId": SERVER_ID, "User": {"Id": USER_ID, "Name": "Demo"}}, head_only)
        elif path == f"/Users/{USER_ID}/Views" and self.command == "GET":
            self._json({"Items": [library_item()], "TotalRecordCount": 1}, head_only)
        elif path == f"/Users/{USER_ID}/Items/Resume" and self.command == "GET":
            item = media_item(PLAYBACK_ID)
            item["UserData"] = {"PlaybackPositionTicks": 7 * TICKS_PER_SECOND, "Played": False, "PlayedPercentage": 23.3}
            self._json({"Items": [item], "TotalRecordCount": 1}, head_only)
        elif path.startswith(f"/Users/{USER_ID}/Items/") and self.command == "GET":
            item_id = path.rsplit("/", 1)[-1]
            self._json(media_item(item_id), head_only) if item_id in media_ids() else self._not_found(head_only)
        elif path == "/Items" and self.command == "GET":
            parent = first(query, "parentId") or first(query, "ParentId")
            search = first(query, "searchTerm") or first(query, "SearchTerm")
            items = [media_item(PLAYBACK_ID), media_item(DOWNLOAD_ID)] if parent == LIBRARY_ID or search is not None else [library_item()]
            if search:
                items = [item for item in items if search.lower() in item["Name"].lower()]
            self._json({"Items": items, "TotalRecordCount": len(items)}, head_only)
        elif re.fullmatch(r"/Items/[^/]+/PlaybackInfo", path) and self.command == "POST":
            item_id = path.split("/")[2]
            # OkHttp reuses this HTTP/1.1 connection for the following media GET. Consume the request
            # body even though the fixture does not need to inspect the device profile.
            self._read_body()
            self._json(playback_info(item_id, self.server.state.video.stat().st_size), head_only) if item_id in media_ids() else self._not_found(head_only)
        elif re.fullmatch(r"/Videos/[^/]+/stream\.mp4", path) and self.command in {"GET", "HEAD"}:
            item_id = path.split("/")[2]
            self._serve_video(item_id, head_only) if item_id in media_ids() else self._not_found(head_only)
        elif re.fullmatch(r"/Items/[^/]+/Images/Primary", path) and self.command in {"GET", "HEAD"}:
            self._serve_file(self.server.state.poster, "image/jpeg", head_only)
        elif path.startswith("/MediaSegments/") and self.command == "GET":
            self._json({"Items": []}, head_only)
        elif path.startswith("/Sessions/Playing") and self.command == "POST":
            with self.server.state.lock:
                self.server.state.playback_reports.append({"path": path, "body": self._read_json(), "at": time.time()})
                self.server.state.playback_reports[:] = self.server.state.playback_reports[-30:]
            self._empty()
        elif path == "/Videos/ActiveEncodings" and self.command == "DELETE":
            self._empty()
        elif (path.startswith("/UserPlayedItems/") or path.startswith("/UserItems/") or "/UserData" in path) and self.command in {"POST", "DELETE"}:
            self._read_body()
            self._json({}, head_only)
        elif path == "/device.xml" and self.command == "GET":
            self._xml(device_description(self.server.state.renderer_port), head_only)
        elif path in {"/upnp/avtransport/control", "/upnp/rendering/control"} and self.command == "POST":
            self._handle_soap(path)
        elif path == "/api/state" and self.command == "GET":
            self._json(self._state_payload(), head_only)
        elif path == "/" and self.command == "GET":
            self._html(receiver_dashboard(), head_only)
        else:
            self._not_found(head_only)

    def _state_payload(self) -> dict[str, Any]:
        state = self.server.state
        with state.lock:
            return {
                "receiver": "Stream Ferry Demo TV",
                "title": state.current_title,
                "transportState": state.transport_state,
                "positionSeconds": state.position(),
                "durationSeconds": 30,
                "volume": state.volume,
                "proxyUrlReceived": bool(state.current_uri),
                "bytesServedByJellyfin": state.bytes_served,
                "playbackReports": len(state.playback_reports),
            }

    def _handle_soap(self, path: str) -> None:
        action_header = self.headers.get("SOAPACTION", "")
        action = action_header.rsplit("#", 1)[-1].strip('"')
        body = self._read_body().decode("utf-8", errors="replace")
        state = self.server.state

        if path.endswith("rendering/control"):
            if action == "GetVolume":
                inner = f"<CurrentVolume>{state.volume}</CurrentVolume>"
            elif action == "SetVolume":
                volume = xml_value(body, "DesiredVolume")
                with state.lock:
                    state.volume = max(0, min(100, int(volume or state.volume)))
                inner = ""
            else:
                inner = ""
            self._soap_response("RenderingControl", action, inner)
            return

        if action == "SetAVTransportURI":
            uri = xml_value(body, "CurrentURI") or ""
            metadata = xml_value(body, "CurrentURIMetaData") or ""
            title = xml_value(metadata, "title") or "Big Buck Bunny"
            state.set_uri(uri, title)
            inner = ""
        elif action == "Play":
            state.play()
            inner = ""
        elif action == "Pause":
            state.pause()
            inner = ""
        elif action == "Stop":
            state.stop()
            inner = ""
        elif action == "Seek":
            state.seek(parse_clock(xml_value(body, "Target") or "0:00:00"))
            inner = ""
        elif action == "GetTransportInfo":
            inner = (
                f"<CurrentTransportState>{state.transport_state}</CurrentTransportState>"
                "<CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>"
            )
        elif action == "GetPositionInfo":
            position = format_clock(state.position())
            inner = (
                "<Track>1</Track><TrackDuration>0:00:30</TrackDuration><TrackMetaData></TrackMetaData>"
                f"<TrackURI>{html.escape(state.current_uri)}</TrackURI><RelTime>{position}</RelTime>"
                f"<AbsTime>{position}</AbsTime><RelCount>2147483647</RelCount><AbsCount>2147483647</AbsCount>"
            )
        else:
            inner = ""
        self._soap_response("AVTransport", action, inner)

    def _soap_response(self, service: str, action: str, inner: str) -> None:
        payload = (
            '<?xml version="1.0"?>'
            '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">'
            f'<s:Body><u:{action}Response xmlns:u="urn:schemas-upnp-org:service:{service}:1">'
            f"{inner}</u:{action}Response></s:Body></s:Envelope>"
        ).encode("utf-8")
        self._send_bytes(payload, "text/xml; charset=utf-8")

    def _serve_video(self, item_id: str, head_only: bool) -> None:
        path = self.server.state.video
        size = path.stat().st_size
        start, end, partial = parse_range(self.headers.get("Range"), size)
        if start is None:
            self.send_response(HTTPStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
            self.send_header("Content-Range", f"bytes */{size}")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        length = end - start + 1
        self.send_response(HTTPStatus.PARTIAL_CONTENT if partial else HTTPStatus.OK)
        self.send_header("Content-Type", "video/mp4")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("ETag", '"stream-ferry-demo-v1"')
        self.send_header("Cache-Control", "no-store")
        if partial:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.send_header("Content-Length", str(length))
        self.end_headers()
        if head_only:
            return

        throttle = item_id == DOWNLOAD_ID
        remaining = length
        with path.open("rb") as source:
            source.seek(start)
            while remaining > 0:
                chunk = source.read(min(64 * 1024, remaining))
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                    self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError):
                    break
                remaining -= len(chunk)
                with self.server.state.lock:
                    self.server.state.bytes_served += len(chunk)
                if throttle:
                    # Keep the foreground work visible long enough to demonstrate that it continues
                    # after Stream Ferry leaves the foreground. The 4 MiB fixture takes about a minute.
                    time.sleep(0.90)

    def _serve_file(self, path: Path, content_type: str, head_only: bool) -> None:
        data = path.read_bytes()
        self._send_bytes(data, content_type, head_only=head_only)

    def _read_body(self) -> bytes:
        length = int(self.headers.get("Content-Length", "0") or 0)
        return self.rfile.read(length) if length else b""

    def _read_json(self) -> dict[str, Any]:
        raw = self._read_body()
        if not raw:
            return {}
        try:
            value = json.loads(raw)
            return value if isinstance(value, dict) else {}
        except json.JSONDecodeError:
            return {}

    def _json(self, value: Any, head_only: bool = False, status: HTTPStatus = HTTPStatus.OK) -> None:
        self._send_bytes(json.dumps(value, separators=(",", ":")).encode("utf-8"), "application/json", status, head_only)

    def _xml(self, value: str, head_only: bool = False) -> None:
        self._send_bytes(value.encode("utf-8"), "application/xml; charset=utf-8", head_only=head_only)

    def _html(self, value: str, head_only: bool = False) -> None:
        self._send_bytes(value.encode("utf-8"), "text/html; charset=utf-8", head_only=head_only)

    def _empty(self) -> None:
        self.send_response(HTTPStatus.NO_CONTENT)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _not_found(self, head_only: bool) -> None:
        self._json({"Message": "Not found"}, head_only, HTTPStatus.NOT_FOUND)

    def _send_bytes(
        self,
        data: bytes,
        content_type: str,
        status: HTTPStatus = HTTPStatus.OK,
        head_only: bool = False,
    ) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if not head_only:
            self.wfile.write(data)


def first(query: dict[str, list[str]], name: str) -> Optional[str]:
    values = query.get(name)
    return values[0] if values else None


def media_ids() -> set[str]:
    return {PLAYBACK_ID, DOWNLOAD_ID}


def library_item() -> dict[str, Any]:
    return {
        "Id": LIBRARY_ID,
        "Name": "Demo Movies",
        "Type": "CollectionFolder",
        "CollectionType": "movies",
        "IsFolder": True,
        "ImageTags": {"Primary": "demo-poster-v1"},
        "Overview": "A deterministic local library for Stream Ferry screenshots, declarations, and regression testing.",
    }


def media_item(item_id: str) -> dict[str, Any]:
    download = item_id == DOWNLOAD_ID
    return {
        "Id": item_id,
        "Name": "Big Buck Bunny · Download Demo" if download else "Big Buck Bunny · Playback Demo",
        "ProductionYear": 2008,
        "RunTimeTicks": 30 * TICKS_PER_SECOND,
        "Overview": (
            "A deliberately throttled copy for demonstrating resumable foreground downloads."
            if download
            else "A bright 30-second open-movie excerpt for testing Stream Ferry playback on a demo TV."
        ),
        "IsFolder": False,
        "Type": "Movie",
        "ImageTags": {"Primary": "demo-poster-v1"},
        "UserData": {"PlaybackPositionTicks": 0, "Played": False, "PlayedPercentage": 0.0},
        "Chapters": [
            {"StartPositionTicks": 0, "Name": "Opening", "ImageTag": "demo-poster-v1"},
            {"StartPositionTicks": 15 * TICKS_PER_SECOND, "Name": "Meadow", "ImageTag": "demo-poster-v1"},
        ],
    }


def playback_info(item_id: str, size: int) -> dict[str, Any]:
    return {
        "PlaySessionId": f"demo-session-{item_id}",
        "MediaSources": [
            {
                "Id": f"source-{item_id}",
                "Container": "mp4",
                "Bitrate": 1_200_000,
                "RunTimeTicks": 30 * TICKS_PER_SECOND,
                "Size": size,
                "SupportsDirectPlay": True,
                "SupportsDirectStream": True,
                "SupportsTranscoding": False,
                "DirectStreamUrl": f"/Videos/{item_id}/stream.mp4?static=true",
                "MediaStreams": [
                    {"Type": "Video", "Codec": "h264", "BitDepth": 8, "Width": 854, "Height": 480, "BitRate": 1_050_000, "Index": 0},
                    {"Type": "Audio", "Codec": "aac", "BitRate": 128_000, "Index": 1, "Language": "eng", "DisplayTitle": "English AAC", "IsDefault": True},
                ],
            }
        ],
    }


def device_description(renderer_port: int) -> str:
    return f"""<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <URLBase>http://10.0.2.2:{renderer_port}/</URLBase>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>Stream Ferry Demo TV</friendlyName>
    <manufacturer>Stream Ferry</manufacturer>
    <modelName>Local Demo Receiver</modelName>
    <modelNumber>1</modelNumber>
    <modelDescription>Debug-only receiver fixture</modelDescription>
    <UDN>{RENDERER_UDN}</UDN>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <controlURL>/upnp/avtransport/control</controlURL>
        <eventSubURL>/upnp/avtransport/event</eventSubURL>
        <SCPDURL>/upnp/avtransport.xml</SCPDURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <controlURL>/upnp/rendering/control</controlURL>
        <eventSubURL>/upnp/rendering/event</eventSubURL>
        <SCPDURL>/upnp/rendering.xml</SCPDURL>
      </service>
    </serviceList>
  </device>
</root>"""


def receiver_dashboard() -> str:
    return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Stream Ferry Demo TV</title><style>
:root{color-scheme:dark;font-family:system-ui,sans-serif}body{margin:0;background:#111318;color:#edf0f8;display:grid;min-height:100vh;place-items:center}
.tv{width:min(840px,88vw);aspect-ratio:16/9;border:14px solid #282c35;border-radius:26px;background:radial-gradient(circle at 70% 25%,#563f75,#1a2736 58%,#101217);box-shadow:0 28px 80px #0009;position:relative;overflow:hidden}
.inner{position:absolute;inset:0;padding:7%;display:flex;flex-direction:column;justify-content:flex-end;background:linear-gradient(transparent 20%,#090b10dc)}
.chip{align-self:flex-start;background:#d6b7ff;color:#251436;border-radius:999px;padding:.45rem .8rem;font-weight:750}.title{font-size:clamp(1.7rem,4vw,3.4rem);font-weight:800;margin:.7rem 0 .2rem}.meta{color:#c7cad4}.bar{height:8px;background:#ffffff2b;border-radius:9px;margin-top:1.4rem;overflow:hidden}.fill{height:100%;width:0;background:#d6b7ff;border-radius:9px}.foot{display:flex;justify-content:space-between;margin-top:.55rem;color:#d8dbe4;font-variant-numeric:tabular-nums}
</style></head><body><main class="tv"><section class="inner"><span class="chip" id="state">READY</span><div class="title" id="title">Waiting for Stream Ferry…</div><div class="meta">Local demo receiver · DLNA control fixture</div><div class="bar"><div class="fill" id="fill"></div></div><div class="foot"><span id="position">0:00 / 0:30</span><span id="details">Proxy not connected</span></div></section></main>
<script>const f=n=>`0:${String(n).padStart(2,'0')}`;async function update(){const s=await fetch('/api/state',{cache:'no-store'}).then(r=>r.json());state.textContent=s.transportState;title.textContent=s.title;position.textContent=`${f(s.positionSeconds)} / ${f(s.durationSeconds)}`;fill.style.width=`${100*s.positionSeconds/s.durationSeconds}%`;details.textContent=s.proxyUrlReceived?'Phone proxy URL received':'Proxy not connected'}setInterval(update,700);update();</script></body></html>"""


def parse_range(value: Optional[str], size: int) -> tuple[Optional[int], int, bool]:
    if not value:
        return 0, size - 1, False
    match = re.fullmatch(r"bytes=(\d*)-(\d*)", value.strip())
    if not match:
        return None, size - 1, True
    start_text, end_text = match.groups()
    if not start_text:
        suffix = int(end_text or 0)
        if suffix <= 0:
            return None, size - 1, True
        start = max(0, size - suffix)
        return start, size - 1, True
    start = int(start_text)
    if start >= size:
        return None, size - 1, True
    end = min(size - 1, int(end_text)) if end_text else size - 1
    if end < start:
        return None, size - 1, True
    return start, end, True


def xml_value(raw_xml: str, local_name: str) -> Optional[str]:
    try:
        root = ET.fromstring(raw_xml)
        for element in root.iter():
            if element.tag.rsplit("}", 1)[-1].rsplit(":", 1)[-1].lower() == local_name.lower():
                return element.text or ""
    except ET.ParseError:
        return None
    return None


def parse_clock(value: str) -> int:
    try:
        hours, minutes, seconds = value.split(":", 2)
        return int(hours) * 3600 + int(minutes) * 60 + int(float(seconds))
    except (ValueError, TypeError):
        return 0


def format_clock(seconds: int) -> str:
    return f"{seconds // 3600}:{(seconds // 60) % 60:02d}:{seconds % 60:02d}"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--video", type=Path, required=True)
    parser.add_argument("--poster", type=Path, required=True)
    parser.add_argument("--jellyfin-port", type=int, default=8096)
    parser.add_argument("--renderer-port", type=int, default=8097)
    args = parser.parse_args()

    if not args.video.is_file() or not args.poster.is_file():
        parser.error("--video and --poster must point to downloaded demo assets")

    state = DemoState(args.video.resolve(), args.poster.resolve(), args.renderer_port)
    jellyfin = DemoServer(("0.0.0.0", args.jellyfin_port), DemoHandler, state)
    renderer = DemoServer(("0.0.0.0", args.renderer_port), DemoHandler, state)
    servers = [jellyfin, renderer]
    stop_event = threading.Event()

    def stop(_signum: int, _frame: Any) -> None:
        stop_event.set()

    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)
    threads = [threading.Thread(target=server.serve_forever, daemon=True) for server in servers]
    for thread in threads:
        thread.start()

    print(f"Jellyfin fixture: http://127.0.0.1:{args.jellyfin_port}", flush=True)
    print(f"Demo receiver:   http://127.0.0.1:{args.renderer_port}", flush=True)
    try:
        while not stop_event.wait(0.5):
            pass
    finally:
        for server in servers:
            server.shutdown()
            server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
