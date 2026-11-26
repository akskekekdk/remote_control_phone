@echo off
REM 원격조종 PC 호스트 실행 (Windows)
REM 처음 한 번은 파이썬 가상환경을 만들고 패키지를 자동 설치합니다.
chcp 65001 >nul
cd /d "%~dp0"

where python >nul 2>nul
if errorlevel 1 (
  echo [오류] 파이썬이 설치돼 있지 않습니다.
  echo https://www.python.org/downloads/ 에서 Python 3.10 이상을 설치하세요.
  echo 설치할 때 "Add python.exe to PATH" 를 꼭 체크하세요.
  pause
  exit /b 1
)

if not exist ".venv" (
  echo [설치] 가상환경 생성 중...
  python -m venv .venv
)

call ".venv\Scripts\activate.bat"

python -c "import aiortc" >nul 2>nul
if errorlevel 1 (
  echo [설치] 필요한 패키지를 설치합니다. 처음 한 번만 몇 분 걸립니다...
  python -m pip install --upgrade pip
  python -m pip install -r requirements.txt
  if errorlevel 1 (
    echo [오류] 패키지 설치 실패. 인터넷 연결을 확인하세요.
    pause
    exit /b 1
  )
)

echo [실행] 원격조종 PC 호스트 시작. 창을 닫으면 종료됩니다.
python host_pc.py
pause
