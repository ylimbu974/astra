@echo off
REM Start the Astra Host Agent on this Windows PC.
REM First run creates a virtual environment and installs the pinned libraries.
cd /d "%~dp0"

if not exist ".env" (
    echo No .env file yet. Copy .env.example to .env and fill in DEVICE_NAME, REGISTRY_URL and HOST_API_KEY.
    pause
    exit /b 1
)

if not exist ".venv\Scripts\python.exe" (
    echo Creating virtual environment...
    py -3.12 -m venv .venv || python -m venv .venv
    ".venv\Scripts\python.exe" -m pip install --upgrade pip
    ".venv\Scripts\python.exe" -m pip install -r requirements.txt || (pause & exit /b 1)
)

".venv\Scripts\python.exe" -m host_agent
if errorlevel 1 pause
