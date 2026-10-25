/**
 * 원격조종 인터넷 모드용 신호(signaling) 서버.
 *
 * 역할: 방(room) 코드로 두 폰(host=B, client=A)을 짝지어 주고,
 *       offer/answer/candidate 메시지를 상대에게 그대로 중계한다.
 *       영상/입력 자체는 WebRTC P2P 로 흐르며 이 서버를 거치지 않는다.
 *
 * 실행:
 *   npm install
 *   PORT=8080 node signaling.js
 *
 * WebSocket 경로: /ws   (앱에는 ws(s)://<host>:<port>/ws 를 입력)
 * 공개망에서는 리버스 프록시(nginx/caddy)로 wss(TLS) 처리를 권장.
 */
const http = require("http");
const { WebSocketServer } = require("ws");

const PORT = process.env.PORT || 8080;

// room -> { host?: ws, client?: ws }
const rooms = new Map();

const server = http.createServer((req, res) => {
  if (req.url === "/" || req.url === "/health") {
    res.writeHead(200, { "Content-Type": "text/plain" });
    res.end("signaling ok");
    return;
  }
  res.writeHead(404);
  res.end();
});

const wss = new WebSocketServer({ server, path: "/ws" });

function send(ws, obj) {
  if (ws && ws.readyState === ws.OPEN) ws.send(JSON.stringify(obj));
}

wss.on("connection", (ws) => {
  ws.room = null;
  ws.role = null;

  ws.on("message", (raw) => {
    let msg;
    try { msg = JSON.parse(raw.toString()); } catch { return; }

    if (msg.type === "join") {
      const room = String(msg.room || "").trim();
      const role = msg.role === "host" ? "host" : "client";
      if (!room) { send(ws, { type: "error", message: "room 없음" }); return; }

      let r = rooms.get(room);
      if (!r) { r = {}; rooms.set(room, r); }
      if (r[role] && r[role].readyState === r[role].OPEN) {
        send(ws, { type: "error", message: `이미 ${role} 가 접속해 있습니다` });
        return;
      }
      r[role] = ws;
      ws.room = room;
      ws.role = role;
      console.log(`join room=${room} role=${role}`);

      // 둘 다 있으면 양쪽에 알림
      if (r.host && r.client) {
        send(r.host, { type: "peer-joined" });
        send(r.client, { type: "peer-joined" });
      }
      return;
    }

    // 중계: 같은 방의 상대에게 그대로 전달 (auth/auth-fail 포함)
    if (["offer", "answer", "candidate", "auth", "auth-fail"].includes(msg.type)) {
      const r = rooms.get(ws.room);
      if (!r) return;
      const peer = ws.role === "host" ? r.client : r.host;
      send(peer, msg);
    }
  });

  ws.on("close", () => {
    const r = rooms.get(ws.room);
    if (!r) return;
    if (r[ws.role] === ws) delete r[ws.role];
    const peer = ws.role === "host" ? r.client : r.host;
    send(peer, { type: "peer-left" });
    if (!r.host && !r.client) rooms.delete(ws.room);
    console.log(`leave room=${ws.room} role=${ws.role}`);
  });
});

server.listen(PORT, () => console.log(`signaling listening on :${PORT} (path /ws)`));
