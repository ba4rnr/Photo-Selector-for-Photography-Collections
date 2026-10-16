@echo off
chcp 65001 >nul
cd /d "%~dp0"

echo ── 摄影集照片筛选器 ──
echo.
echo 正在构建（第一次会慢一些，之后很快）...
call mvnw.cmd -q -DskipTests package
if errorlevel 1 (
    echo.
    echo 构建失败。把上面这段错误信息发给我就行。
    pause
    exit /b 1
)

echo 启动...
start "" javaw -jar "target\photo-selector.jar"
if errorlevel 1 (
    echo.
    echo 启动失败，可能是 java 不在 PATH 里。
    pause
    exit /b 1
)

echo 已经启动，这个窗口可以关掉了。
timeout /t 2 >nul
