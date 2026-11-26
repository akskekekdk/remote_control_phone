"""
원격조종 — Windows 미니PC 호스트.

폰 앱(A/조종기)이 지금 쓰는 그대로(신호 서버 + Cloudflare TURN + 방코드 + PIN)
이 PC에 접속해 화면을 보고 마우스/키보드로 조종한다.

폰 → PC 입력 매핑:
  - 탭            → 좌클릭
  - 길게 누르기   → 우클릭
  - 밀기(스와이프)→ 드래그(좌버튼 누른 채 이동)
  - 뒤로/홈/최근  → Esc / 윈도우키 / Alt+Tab
  - 볼륨 +/-      → 시스템 볼륨
  - 키보드(앱 자판)→ 글자 입력 / Enter·Backspace 등

실행: run.bat (처음 한 번은 자동으로 파이썬 패키지를 설치)
설정: config.ini (방코드·PIN·Cloudflare 키를 폰 앱과 똑같이)
"""

import asyncio
import configparser
import json
import os
import sys
import threading
import time

import requests

try:
    import numpy as np
    import mss
    from PIL import Image
    from aiortc import (
        RTCPeerConnection, RTCSessionDescription, RTCConfiguration,
        RTCIceServer, RTCIceCandidate, VideoStreamTrack,
    )
    from aiortc.sdp import candidate_from_sdp
    from av import VideoFrame
    import websockets
    from pynput.mouse import Button, Controller as Mouse
    from pynput.keyboard import Key, Controller as Keyboard
except Exception as e:  # noqa
    print("필요한 패키지가 없습니다. run.bat 로 실행하면 자동 설치됩니다.")
    print("수동 설치: pip install -r requirements.txt")
    print("원인:", e)
    sys.exit(1)


# ---------------- 설정 읽기 ----------------
HERE = os.path.dirname(os.path.abspath(__file__))
CFG = configparser.ConfigParser()
CFG.read(os.path.join(HERE, "config.ini"), encoding="utf-8")

SIGNAL_URL = CFG.get("signaling", "url", fallback="wss://example.com/ws").strip()
ROOM = CFG.get("signaling", "room", fallback="").strip()
PIN = CFG.get("signaling", "pin", fallback="").strip()
CF_KEY = CFG.get("cloudflare", "key_id", fallback="").strip()
CF_TOKEN = CFG.get("cloudflare", "token", fallback="").strip()
MAX_DIM = CFG.getint("video", "max_dim", fallback=1366)
FPS = CFG.getint("video", "fps", fallback=20)

if not ROOM or not PIN:
    print("config.ini 의 room(방코드) 과 pin 을 폰 앱과 똑같이 채워주세요.")
    sys.exit(1)


# ---------------- ICE 서버 (Cloudflare TURN + STUN) ----------------
def build_ice_servers():
    servers = [RTCIceServer(urls=["stun:stun.l.google.com:19302"])]
    if CF_KEY and CF_TOKEN:
        try:
            url = f"https://rtc.live.cloudflare.com/v1/turn/keys/{CF_KEY}/credentials/generate-ice-servers"
            r = requests.post(url, headers={"Authorization": f"Bearer {CF_TOKEN}"},
                              json={"ttl": 86400}, timeout=12)
            if r.ok:
                data = r.json()
                for ice in data.get("iceServers", []):
                    urls = ice.get("urls")
                    urls = urls if isinstance(urls, list) else [urls]
                    stun = [u for u in urls if u.startswith("stun:")]
                    turn = [u for u in urls if u.startswith(("turn:", "turns:"))]
                    if stun:
                        servers.append(RTCIceServer(urls=stun))
                    if turn:
                        servers.append(RTCIceServer(
                            urls=turn,
                            username=ice.get("username", ""),
                            credential=ice.get("credential", ""),
                        ))
                print(f"[ICE] Cloudflare TURN OK ({len(data.get('iceServers', []))})")
            else:
                print(f"[ICE] Cloudflare 실패 HTTP {r.status_code} — STUN 만 사용")
        except Exception as e:  # noqa
            print(f"[ICE] Cloudflare 오류: {e} — STUN 만 사용")
    else:
        print("[ICE] Cloudflare 키 없음 — STUN 만 사용(같은 망/포트개방에서만 연결될 수 있음)")
    return servers


# ---------------- 화면 캡처 트랙 ----------------
class ScreenTrack(VideoStreamTrack):
    def __init__(self, max_dim, fps):
        super().__init__()
        self.max_dim = max_dim
        self.fps = max(5, min(60, fps))
        self._start = time.monotonic()
        self._next = time.monotonic()
        self._tl = threading.local()
        self._exec = __import__("concurrent.futures").futures.ThreadPoolExecutor(max_workers=1)
        # 주 모니터 좌표(입력 매핑에 사용)
        with mss.mss() as s:
            self.mon = dict(s.monitors[1])

    def set_quality(self, max_dim, fps):
        if max_dim:
            self.max_dim = max_dim
        if fps:
            self.fps = max(5, min(60, fps))

    def _grab(self):
        if not hasattr(self._tl, "sct"):
            self._tl.sct = mss.mss()
        raw = self._tl.sct.grab(self._tl.sct.monitors[1])
        img = np.asarray(raw)  # BGRA
        img = img[:, :, [2, 1, 0]]  # BGR -> RGB
        h, w = img.shape[0], img.shape[1]
        m = max(w, h)
        if self.max_dim and m > self.max_dim:
            scale = self.max_dim / m
            nw = (int(w * scale) // 2) * 2
            nh = (int(h * scale) // 2) * 2
            img = np.asarray(Image.fromarray(img).resize((nw, nh), Image.BILINEAR))
        return np.ascontiguousarray(img)

    async def recv(self):
        interval = 1.0 / self.fps
        now = time.monotonic()
        wait = self._next - now
        if wait > 0:
            await asyncio.sleep(wait)
        self._next = max(self._next + interval, time.monotonic())

        loop = asyncio.get_event_loop()
        img = await loop.run_in_executor(self._exec, self._grab)
        frame = VideoFrame.from_ndarray(img, format="rgb24")
        frame.pts = int((time.monotonic() - self._start) * 90000)
        frame.time_base = __import__("fractions").Fraction(1, 90000)
        return frame


# ---------------- 입력 주입 ----------------
mouse = Mouse()
kbd = Keyboard()

VK = {
    "enter": Key.enter, "back": Key.backspace, "tab": Key.tab, "esc": Key.esc,
    "space": Key.space, "up": Key.up, "down": Key.down, "left": Key.left,
    "right": Key.right, "home": Key.home, "end": Key.end, "del": Key.delete,
}


def to_px(nx, ny, mon):
    x = mon["left"] + nx * mon["width"]
    y = mon["top"] + ny * mon["height"]
    return int(x), int(y)


def do_drag(sx, sy, ex, ey, dur_ms):
    mouse.position = (sx, sy)
    mouse.press(Button.left)
    steps = max(2, int(dur_ms / 15))
    for i in range(1, steps + 1):
        t = i / steps
        mouse.position = (int(sx + (ex - sx) * t), int(sy + (ey - sy) * t))
        time.sleep(dur_ms / 1000.0 / steps)
    mouse.release(Button.left)


def handle_input(msg, track, loop):
    """데이터채널 수신(JSON) → 실제 입력. 블로킹이라 실행기 스레드에서 호출됨."""
    try:
        o = json.loads(msg)
    except Exception:
        return
    t = o.get("t")
    mon = track.mon
    if t == "tap":
        mouse.position = to_px(o["x"], o["y"], mon)
        mouse.click(Button.left, 1)
    elif t == "long":
        mouse.position = to_px(o["x"], o["y"], mon)
        mouse.click(Button.right, 1)
    elif t == "swipe":
        sx, sy = to_px(o["x1"], o["y1"], mon)
        ex, ey = to_px(o["x2"], o["y2"], mon)
        do_drag(sx, sy, ex, ey, int(o.get("d", 200)))
    elif t == "key":
        k = o.get("k")
        if k == 0:
            kbd.tap(Key.esc)
        elif k == 1:
            kbd.tap(Key.cmd)  # 윈도우 키
        elif k == 2:
            with kbd.pressed(Key.alt):
                kbd.tap(Key.tab)
        elif k == 3:
            kbd.tap(Key.media_volume_up)
        elif k == 4:
            kbd.tap(Key.media_volume_down)
    elif t == "text":
        s = o.get("s", "")
        if s:
            kbd.type(s)
    elif t == "vkey":
        key = VK.get(o.get("k", ""))
        if key:
            kbd.tap(key)
    elif t == "quality":
        # 전송량 단계 → 해상도/프레임으로 반영 (대략)
        br = int(o.get("bitrate", 2_500_000))
        fps = int(o.get("fps", 20))
        max_dim = 1024 if br <= 1_000_000 else (1366 if br <= 3_000_000 else 1600)
        track.set_quality(max_dim, min(fps, 30))
        print(f"[화질] {max_dim}px / {min(fps,30)}fps")


# ---------------- WebRTC/신호 ----------------
class Session:
    def __init__(self, ice_servers):
        self.ice_servers = ice_servers
        self.pc = None
        self.track = None
        self.channel = None
        self.pending_candidates = []
        self.input_exec = __import__("concurrent.futures").futures.ThreadPoolExecutor(max_workers=1)

    async def close_pc(self):
        if self.channel:
            try:
                self.channel.close()
            except Exception:
                pass
            self.channel = None
        if self.pc:
            try:
                await self.pc.close()
            except Exception:
                pass
            self.pc = None
        self.track = None
        self.pending_candidates = []

    async def start(self, ws, loop):
        await self.close_pc()
        self.pc = RTCPeerConnection(RTCConfiguration(iceServers=self.ice_servers))
        self.track = ScreenTrack(MAX_DIM, FPS)
        self.pc.addTrack(self.track)
        self.channel = self.pc.createDataChannel("input")

        track = self.track

        @self.channel.on("message")
        def on_message(message):
            if isinstance(message, bytes):
                try:
                    message = message.decode("utf-8")
                except Exception:
                    return
            loop.run_in_executor(self.input_exec, handle_input, message, track, loop)

        @self.pc.on("connectionstatechange")
        async def on_state():
            print(f"[PC] 연결상태: {self.pc.connectionState}")

        offer = await self.pc.createOffer()
        await self.pc.setLocalDescription(offer)
        await ws.send(json.dumps({"type": "offer", "sdp": self.pc.localDescription.sdp}))
        print("[신호] offer 전송 — 폰의 응답 대기")

    async def on_answer(self, sdp):
        if not self.pc:
            return
        await self.pc.setRemoteDescription(RTCSessionDescription(sdp=sdp, type="answer"))
        for c in self.pending_candidates:
            try:
                await self.pc.addIceCandidate(c)
            except Exception:
                pass
        self.pending_candidates = []

    async def on_candidate(self, cand, mid, mline):
        if not cand:
            return
        s = cand[len("candidate:"):] if cand.startswith("candidate:") else cand
        try:
            ic = candidate_from_sdp(s)
            ic.sdpMid = mid
            ic.sdpMLineIndex = mline
        except Exception as e:  # noqa
            print("[신호] candidate 파싱 실패:", e)
            return
        if self.pc and self.pc.remoteDescription:
            try:
                await self.pc.addIceCandidate(ic)
            except Exception:
                pass
        else:
            self.pending_candidates.append(ic)


async def run():
    ice_servers = build_ice_servers()
    session = Session(ice_servers)
    loop = asyncio.get_event_loop()
    print(f"[시작] 방코드={ROOM}  PIN 설정됨  신호서버={SIGNAL_URL}")
    print("폰 앱에서 같은 방코드/PIN 으로 '인터넷'으로 접속하세요. (Ctrl+C 로 종료)")

    while True:
        try:
            async with websockets.connect(SIGNAL_URL, max_size=None) as ws:
                await ws.send(json.dumps({"type": "join", "room": ROOM, "role": "host"}))
                print("[신호] 서버 연결됨, 방 참가")
                async for raw in ws:
                    try:
                        o = json.loads(raw)
                    except Exception:
                        continue
                    typ = o.get("type")
                    if typ == "auth":
                        if o.get("pin", "") != PIN:
                            print("[보안] PIN 불일치 — 접속 거부")
                            await ws.send(json.dumps({"type": "auth-fail"}))
                        else:
                            print("[보안] PIN 확인 — 세션 시작")
                            await session.start(ws, loop)
                    elif typ == "answer":
                        await session.on_answer(o.get("sdp", ""))
                    elif typ == "candidate":
                        await session.on_candidate(
                            o.get("candidate", ""), o.get("sdpMid"),
                            int(o.get("sdpMLineIndex", 0)))
                    elif typ == "peer-left":
                        print("[신호] 폰이 나감")
                    elif typ == "error":
                        print("[신호] 서버 오류:", o.get("message"))
        except Exception as e:  # noqa
            print(f"[신호] 연결 끊김: {e} — 3초 후 재접속")
        await session.close_pc()
        await asyncio.sleep(3)


if __name__ == "__main__":
    try:
        asyncio.run(run())
    except KeyboardInterrupt:
        print("\n종료합니다.")
