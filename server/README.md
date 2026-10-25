# 신호 서버 (Signaling Server)

인터넷 원격 모드에서 두 폰을 짝지어 주는 WebSocket 서버입니다. **영상·입력은 WebRTC P2P로 직접 흐르고**, 이 서버는 연결 성사(offer/answer/candidate 교환)만 중계합니다. 트래픽이 거의 없어 아주 작은 서버로도 충분합니다.

## 로컬 실행

```bash
cd server
npm install
PORT=8080 node signaling.js
# WebSocket 경로: ws://<내IP>:8080/ws
```

## 공개망 배포 (둘 중 하나)

두 폰이 서로 다른 네트워크(예: LTE ↔ 집 와이파이)에 있으려면, 서버가 **공인 주소**로 접근 가능해야 하고 **wss(TLS)** 가 필요합니다.

### A. VPS + Caddy (가장 간단한 TLS)
```bash
# 서버에서
PORT=8080 node signaling.js      # (pm2/systemd로 상시 실행 권장)
```
`Caddyfile`:
```
your-domain.com {
    reverse_proxy /ws* 127.0.0.1:8080
    reverse_proxy /health 127.0.0.1:8080
}
```
→ 앱에 입력할 주소: `wss://your-domain.com/ws`

### B. Render / Railway / Fly.io (무료 티어)
- **보안 팁:** 서비스 이름을 추측하기 어려운 무작위 값(예: `rc-a1b2c3d4`)으로 만드세요. 서비스 이름이 곧 주소(`<이름>.onrender.com`)라서, 이름이 단순하면 주소가 노출됩니다. 이 이름은 저장소에 두지 말고 본인만 알고 계세요.
- 설정: Web Service 새로 만들기 → 이 저장소 연결 → **Root Directory `server`**, Build `npm install`, Start `node signaling.js`. 포트는 플랫폼의 `PORT` 환경변수를 자동 사용.
- 배포 후 주소 뒤에 `/ws` 를 붙여 앱에 입력: `wss://<본인이정한무작위이름>.onrender.com/ws`

## TURN 서버 (통신사망에서 필수일 수 있음)

STUN 만으로는 대칭형 NAT(특히 LTE/5G)에서 P2P가 안 뚫릴 때가 많습니다. 그 경우 TURN이 있어야 연결됩니다.

- 직접 운영: [coturn](https://github.com/coturn/coturn)
  ```
  turnserver -a -u user:pass -r realm --no-tls --no-dtls -p 3478
  ```
  앱의 TURN 칸: `turn:<서버IP>:3478`, 사용자 `user`, 비밀번호 `pass`
- 또는 상용/무료 TURN 서비스(Metered, Twilio 등)의 자격증명을 앱 TURN 칸에 입력

## 동작 확인
```bash
curl http://localhost:8080/health   # → signaling ok
```
