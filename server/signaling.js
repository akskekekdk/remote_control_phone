/**
 * 원격조종 인터넷 모드용 신호(signaling) 서버.
 *
 * 역할: 방(room) 코드로 두 폰(host=B, client=A)을 짝지어 주고,
 *       offer/answer/candidate 메시지를 상대에게 그대로 중계한다.
 *       영상/입력 자체는 WebRTC P2P 로 흐르며 이 서버를 거치지 않는다.
 *
 * 보안: PIN 무차별 대입 차단.
 *   - PIN 이 틀리면(host 가 auth-fail 을 보내면) 그 방/그 IP 의 실패 횟수를 센다.
 *   - 실패가 임계치를 넘으면 해당 방/IP 를 일정 시간 잠가(join 거부) 숫자 대입을 막는다.
 *   - 인증 성공(host 가 offer 전송)하면 그 방의 실패 기록을 초기화한다.
 *
 * 실행:
 *   npm install
 *   PORT=8080 node signaling.js
 */
const http = require("http");
const { WebSocketServer } = require("ws");

const PORT = process.env.PORT || 8080;

// --- 무차별 대입 차단 설정 ---
const FAIL_THRESHOLD = 5;              // 이 횟수만큼 PIN 틀리면 잠금
const LOCK_MS = 5 * 60 * 1000;         // 잠금 지속 시간(5분)
const roomFails = new Map();           // room -> { count, lockedUntil }
const ipFails = new Map();             // ip   -> { count, lockedUntil }

function isLocked(map, key) {
  const e = map.get(key);
  return !!(e && e.lockedUntil && Date.now() < e.lockedUntil);
}
function lockRemaining(map, key) {
  const e = map.get(key);
  if (!e || !e.lockedUntil) return 0;
  return Math.max(0, Math.ceil((e.lockedUntil - Date.now()) / 1000));
}
function addFail(map, key) {
  if (!key) return;
  const e = map.get(key) || { count: 0, lockedUntil: 0 };
  e.count += 1;
  if (e.count >= FAIL_THRESHOLD) { e.lockedUntil = Date.now() + LOCK_MS; e.count = 0; }
  map.set(key, e);
}
function clearFail(map, key) { if (key) map.delete(key); }

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

wss.on("connection", (ws, req) => {
  ws.room = null;
  ws.role = null;
  ws.isAlive = true;
  // Render 등 프록시 뒤에서는 x-forwarded-for 가 실제 클라이언트 IP
  ws.ip = String(req.headers["x-forwarded-for"] || "").split(",")[0].trim()
    || (req.socket && req.socket.remoteAddress) || "";

  ws.on("message", (raw) => {
    let msg;
    try { msg = JSON.parse(raw.toString()); } catch { return; }

    if (msg.type === "join") {
      const room = String(msg.room || "").trim();
      const role = msg.role === "host" ? "host" : "client";
      if (!room) { send(ws, { type: "error", message: "room 없음" }); return; }

      // 조종(client) 접속만 잠금 검사 (B host 는 계속 재접속해야 하므로 제외)
      if (role === "client") {
        if (isLocked(roomFails, room) || isLocked(ipFails, ws.ip)) {
          const sec = Math.max(lockRemaining(roomFails, room), lockRemaining(ipFails, ws.ip));
          send(ws, { type: "error", message: `시도가 너무 많습니다. ${sec}초 후 다시 시도하세요.` });
          try { ws.close(4001, "locked"); } catch {}
          console.log(`blocked join room=${room} ip=${ws.ip} (locked ${sec}s)`);
          return;
        }
      }

      let r = rooms.get(room);
      if (!r) { r = {}; rooms.set(room, r); }
      // 같은 역할이 이미 있으면 '최신 연결 우선' — 옛 연결(유령 포함)을 끊고 교체
      if (r[role] && r[role] !== ws) {
        try { r[role].close(4000, "replaced by new connection"); } catch {}
      }
      r[role] = ws;
      ws.room = room;
      ws.role = role;
      console.log(`join room=${room} role=${role}`);

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

      // PIN 틀림(host→client auth-fail): 실패 카운트 증가
      if (msg.type === "auth-fail" && ws.role === "host") {
        const clientWs = r.client;
        addFail(roomFails, ws.room);
        if (clientWs) addFail(ipFails, clientWs.ip);
        console.log(`auth-fail room=${ws.room} ip=${clientWs && clientWs.ip}`);
      }
      // PIN 통과(host→client offer): 실패 기록 초기화
      if (msg.type === "offer" && ws.role === "host") {
        clearFail(roomFails, ws.room);
        const clientWs = r.client;
        if (clientWs) clearFail(ipFails, clientWs.ip);
      }

      send(peer, msg);
    }
  });

  ws.on("pong", () => { ws.isAlive = true; });

  ws.on("close", () => {
    const r = rooms.get(ws.room);
    if (!r) return;
    if (r[ws.role] !== ws) return; // 이미 교체된 소켓이면 무시
    delete r[ws.role];
    const peer = ws.role === "host" ? r.client : r.host;
    send(peer, { type: "peer-left" });
    if (!r.host && !r.client) rooms.delete(ws.room);
    console.log(`leave room=${ws.room} role=${ws.role}`);
  });
});

// heartbeat: 30초마다 ping, 응답 없는(죽은) 소켓은 종료 → 유령 연결 정리
const HEARTBEAT_MS = 30000;
const heartbeat = setInterval(() => {
  wss.clients.forEach((ws) => {
    if (ws.isAlive === false) { try { ws.terminate(); } catch {} return; }
    ws.isAlive = false;
    try { ws.ping(); } catch {}
  });
}, HEARTBEAT_MS);

// 오래된 잠금 기록 정리 (메모리 누수 방지)
setInterval(() => {
  const now = Date.now();
  for (const [k, e] of roomFails) if (e.lockedUntil && e.lockedUntil < now && e.count === 0) roomFails.delete(k);
  for (const [k, e] of ipFails) if (e.lockedUntil && e.lockedUntil < now && e.count === 0) ipFails.delete(k);
}, 60 * 1000);

wss.on("close", () => clearInterval(heartbeat));

server.listen(PORT, () => console.log(`signaling listening on :${PORT} (path /ws)`));
