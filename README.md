# 원격조종 (RemoteCtl)

같은 와이파이에 있는 두 안드로이드 폰 사이에서, **A 폰으로 B 폰의 화면을 보며 원격으로 조종**하는 개인용 앱입니다.

- **A (조종)**: B의 화면을 실시간으로 보고 탭/스와이프/길게누르기와 뒤로·홈·최근앱 버튼으로 조종
- **B (피조종)**: 자기 화면을 공유하고, 들어온 입력을 실제 터치로 실행

시그널링 서버 없이 B가 직접 TCP 서버(포트 7001)를 열고 A가 IP로 접속합니다. 같은 와이파이면 추가 서버가 필요 없습니다.

## 구조

```
[B 폰 (피조종)]                                  [A 폰 (조종)]
MediaProjection ── 화면 캡처                      SurfaceView ◀── 표시
  → MediaCodec H.264 인코딩 ──── TCP(7001) ────▶ MediaCodec 디코딩
AccessibilityService ◀── 입력(정규화 좌표) ──────── 터치/스와이프/키
  → dispatchGesture 로 실제 터치 주입
```

- 좌표는 0.0~1.0 로 정규화해 전송 → 양쪽 해상도가 달라도 정확히 매핑
- 영상 긴 변을 1280px 로 축소, H.264 ~6Mbps

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
2. **B 폰**: "조종 받기(B)" → 접근성 설정에서 **원격조종** 켜기 → "화면 공유 시작" → 화면 녹화 허용 → 표시되는 IP 확인
3. **A 폰**: "조종하기(A)" → B의 IP 입력 → 접속

## 제약

- 같은 와이파이 전용 (인터넷 너머 원격은 WebRTC + TURN 필요)
- 은행/보안 앱 등 화면 캡처 차단 화면은 검은 화면으로 보임 (안드로이드 정책)
- 접근성 권한을 원격 제어에 사용하므로 Google Play 배포 시 심사가 까다로움 — 개인용 직접 설치 용도

## 라이선스

개인용. 자유롭게 수정해 사용하세요.
