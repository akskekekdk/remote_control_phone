# 원격조종 — Windows 미니PC 호스트

폰 앱(조종기)으로 **Windows 미니PC를 원격 조종**합니다.
지금 쓰는 폰 앱을 그대로 쓰며, PC에 이 프로그램만 깔아 실행하면 됩니다.
(신호 서버 · Cloudflare TURN · 방코드 · PIN 모두 폰 앱과 동일하게 사용)

## 방법 1 — 파이썬 없이 (추천, exe 하나)

1. 아래 주소에서 **host_pc.exe** 를 받는다 (파이썬 설치 불필요):
   https://github.com/akskekekdk/remote_control_phone/releases/download/pc-host/host_pc.exe
2. 원하는 폴더에 두고 **더블클릭**. 처음 실행하면 옆에 `config.ini` 가 생긴다.
3. `config.ini` 를 메모장으로 열어 **폰 앱과 똑같이** 채운다(room·pin·Cloudflare). 저장.
4. `host_pc.exe` 다시 실행 → "방 참가" 뜨면 대기. 폰 앱에서 같은 코드/PIN 으로 '인터넷' 접속.
   - Windows 보안 경고(파란 창)가 뜨면 "추가 정보 → 실행"을 누른다(서명 없는 개인 프로그램이라 그렇다).

## 방법 2 — 파이썬으로 실행

1. **Python 설치**: https://www.python.org/downloads/ 에서 Python 3.10 이상 설치.
   - 설치 첫 화면에서 **"Add python.exe to PATH"** 체크 필수.
2. 이 `pc_host` 폴더를 PC로 복사. (GitHub에서 저장소를 ZIP으로 받아 풀면 됩니다)
3. `config.ini` 를 메모장으로 열어 **폰 앱과 똑같이** 채우기:
   - `room` = 폰 앱의 **코드**
   - `pin` = 폰 앱의 **PIN**
   - `key_id`, `token` = 폰 앱에 넣은 **Cloudflare 값** (없으면 비워둠 → 같은 와이파이에서만)
4. `run.bat` **더블클릭**. 처음 한 번은 패키지를 자동 설치(몇 분).

## 사용

1. PC에서 `run.bat` 실행 → "방 참가" 메시지가 뜨면 대기 상태.
2. 폰 앱에서 **같은 방코드/PIN** 으로 **'인터넷'** 접속.
3. 폰에 PC 화면이 뜨고 조종 가능.

## 조작 매핑

| 폰 동작 | PC 동작 |
|---|---|
| 탭 | 좌클릭 |
| 길게 누르기 | 우클릭 |
| 밀기(드래그) | 마우스 드래그 |
| 뒤로 | Esc |
| 홈 | 윈도우 키 |
| 최근 | Alt+Tab |
| 볼륨 +/- | 시스템 볼륨 |
| 키보드(앱 자판) | 글자 입력 / Enter·Backspace |

## 자동 시작(선택)

PC 켜질 때 자동 실행하려면 `run.bat` 바로가기를 만들어
`Win+R` → `shell:startup` 폴더에 넣으세요.

## 문제 해결

- **연결은 되는데 화면이 안 뜸**: Cloudflare 값이 폰과 다르거나 비어 있음 → `config.ini` 확인.
- **느림/끊김**: `config.ini` 의 `max_dim` 을 1024, `fps` 를 15 로 낮춰보세요.
- **클릭 위치가 안 맞음**: 모니터가 여러 대면 주 모니터만 조종됩니다.
- **패키지 설치 실패**: 인터넷 확인 후 `run.bat` 다시 실행.
