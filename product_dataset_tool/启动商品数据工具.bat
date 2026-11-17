@echo off
cd /d "%~dp0"
where python >nul 2>nul
if errorlevel 1 (
  echo 未检测到 Python 3.10 或更新版本，请先安装 Python，并勾选“Add Python to PATH”。
  pause
  exit /b 1
)
python -c "import cv2, PIL, tkinter" >nul 2>nul
if errorlevel 1 (
  echo 缺少图像处理依赖，正在安装……
  python -m pip install -r requirements.txt
  if errorlevel 1 (
    echo 安装失败，请检查网络或 Python 环境。
    pause
    exit /b 1
  )
)
python main.py
if errorlevel 1 pause
