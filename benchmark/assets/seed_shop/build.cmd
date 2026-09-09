@echo off
REM Windows 一键编译：把所有源文件编译到 out/
setlocal
if not exist out mkdir out
javac -encoding UTF-8 -d out ^
  src\com\shop\model\*.java ^
  src\com\shop\repository\*.java ^
  src\com\shop\service\*.java ^
  src\com\shop\util\*.java ^
  src\com\shop\web\*.java
if %errorlevel%==0 (
  echo BUILD OK
  java -cp out com.shop.web.App
) else (
  echo BUILD FAILED
  exit /b 1
)
