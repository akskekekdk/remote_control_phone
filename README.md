# 원격조종 (RemoteCtl)

두 안드로이드 폰 사이에서, **A 폰으로 B 폰의 화면을 보며 원격으로 조종**하는 개인용 앱입니다.

- **A (조종)**: B의 화면을 실시간으로 보고 탭/스와이프/길게누르기와 뒤로·홈·최근앱 버튼으로 조종
- **B (피조종)**: 자기 화면을 공유하고, 들어온 입력을 실제 터치로 실행

앱 실행 시 **연결 방식**과 **역할**을 고릅니다.

| 모드 | 네트워크 | 전송 | 추가 서버 |
|---|---|---|---|
| **같은 와이파이** | 두 폰이 같은 와이파이 | 직접 TCP(7001) + H.264 | 없음 |
| **인터넷** | 서로 다른 망도 가능 | WebRTC(P2P) + 데이터채널 | 신호 서버(필수), TURN(권장) |

### 같은 와이파이 모드
시그널링 서버 없이 B가 직접 TCP 서버(포트 7001)를 열고 A가 B의 IP로 접속합니다.

### 인터넷 모드 (WebRTC)
방 코드로 두 폰을 짝지어 WebRTC P2P 연결을 맺습니다. 영상·입력은 폰끼리 직접 흐르고, 연결 성사만 신호 서버가 중계합니다.
1. `server/` 의 신호 서버를 공개 주소(wss)에 올립니다 — [server/README.md](server/README.md)
2. B: "인터넷" + "조종 받기" → 접근성 켜기 → 신호 서버 주소 입력 → 화면 공유 시작 → **방 코드** 확인
3. A: "인터넷" + "조종하기" → 같은 서버 주소 + 방 코드 입력 → 접속
4. 통신사망(LTE/5G) 등에서 연결이 안 되면 TURN 서버 자격증명을 입력 (server/README.md 참고)

## 구조

같은 와이파이 모드:
```
[B 폰 (피조종)]                                  [A 폰 (조종)]
MediaProjection ── 화면 캡처                      SurfaceView ◀── 표시
  → MediaCodec H.264 인코딩 ──── TCP(7001) ────▶ MediaCodec 디코딩
AccessibilityService ◀── 입력(정규화 좌표) ──────── 터치/스와이프/키
  → dispatchGesture 로 실제 터치 주입
```

인터넷 모드:
```
[B 폰]                         [신호 서버]                      [A 폰]
WebRTC 영상 트랙 송신   ◀── offer/answer/candidate 중계 ──▶   SurfaceViewRenderer
AccessibilityService  ◀═══ WebRTC 데이터채널(입력) P2P ═══▶   터치/스와이프/키
          └════════════ 영상·입력은 P2P 직접 전송 ════════════┘
```

- 좌표는 0.0~1.0 로 정규화해 전송 → 양쪽 해상도가 달라도 정확히 매핑
- 영상 긴 변을 1280px 로 축소, H.264 ~6Mbps

### 소스 구조
```
app/src/main/java/com/example/remotectl/
  MainActivity.kt            모드·역할 선택
  Protocol.kt                와이파이 모드 TCP 프레이밍
  host/   ScreenCaptureService, ControlAccessibilityService, HostActivity   (와이파이 B)
  client/ ClientActivity, VideoDecoder                                       (와이파이 A)
  webrtc/ SignalingClient, RtcConfig, InputCodec, Adapters,
          WebRtcHostService/Activity, WebRtcClientActivity                   (인터넷)
server/   signaling.js  신호 서버(Node.js/ws)
```

## 빌드

```bash
export ANDROID_HOME=/path/to/android-sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

요구사항: JDK 17, Android SDK (compileSdk 35, build-tools 35.0.0), minSdk 26.

## 사용법

1. 두 폰 모두에 APK 설치 (디버그 서명이라 "출처를 알 수 없는 앱" 허용 필요)
2. **B 폰**: "같은 와이파이" + "조종 받기(B)" → 접근성 켜기 → **PIN** 확인/설정 → "화면 공유 시작" → 화면 녹화 허용 → 표시되는 IP 확인
3. **A 폰**: "같은 와이파이" + "조종하기(A)" → B의 IP + **PIN** 입력 → 접속

(인터넷 모드는 위 "인터넷 모드(WebRTC)" 참고 — 서버 주소·방 코드·PIN 사용)

## 보안

- **단방향 조종**: A가 B를 보고 조종만 하며, B는 A를 보거나 조종할 수 없음 (영상 B→A, 입력 A→B 전용)
- **연결 PIN 인증**: B가 정한 PIN을 아는 A만 연결 가능. PIN이 틀리면
  - 와이파이: B가 핸드셰이크 단계에서 소켓을 즉시 끊음 (영상/입력 전송 안 됨)
  - 인터넷: B가 PIN 검증 전에는 answer를 보내지 않음 → 연결·영상 자체가 시작 안 됨
- **무차별 대입 차단**: 신호 서버가 PIN 실패를 방·IP별로 세어, 5회 틀리면 5분간 그 방/IP의 접속을 잠금 → 숫자 대입 공격 차단
- **서버 주소 비노출**: 신호 서버 이름은 저장소에 두지 않음(추측 어려운 무작위 이름 권장) — server/README.md 참고
- **제3자 조종 방지**: 같은 와이파이의 다른 사람이나 방 코드만 아는 사람도 PIN 없이는 붙을 수 없음
- **전송 암호화**: 인터넷 모드는 WebRTC(DTLS-SRTP)로 영상·입력이 기본 암호화됨. 신호 서버는 `wss`(TLS) 사용 권장. 와이파이 모드 TCP는 평문(같은 랜 한정)
- 이 앱은 B가 직접 설치·화면녹화 허용·접근성 활성화를 해야만 동작 — 임의의 타 기기를 조종할 수 없음

## 제약

- 은행/보안 앱 등 화면 캡처 차단 화면은 검은 화면으로 보임 (안드로이드 정책)
- 접근성 권한을 원격 제어에 사용하므로 Google Play 배포 시 심사가 까다로움 — 개인용 직접 설치 용도
- 인터넷 모드는 실제 2대 기기 + 배포된 신호 서버(+필요시 TURN)에서 검증 필요

## 앱 내 업데이트

앱 실행 시, 그리고 메인 화면의 "업데이트 확인" 버튼으로 `latest.json` 을 확인해 새 버전이 있으면 APK 를 받아 설치합니다.

- 확인 주소: `app/build.gradle.kts` 의 `UPDATE_URL` (GitHub raw `latest.json`)
- 배포(새 버전 내보내기):
  1. `versionCode`/`versionName` 올리고 release APK 빌드
  2. `dist/app-release.apk` 교체, `latest.json` 의 `versionCode`/`versionName`/`notes` 갱신
  3. 커밋 & 푸시 → 앱이 다음 실행/확인 때 자동 감지
- ⚠️ 앱이 인증 없이 받으므로 **레포가 Public 이어야** 업데이트가 동작합니다. (서명 키스토어는 `.gitignore` 로 제외되어 공개되지 않음)

## 라이선스

개인용. 자유롭게 수정해 사용하세요.
