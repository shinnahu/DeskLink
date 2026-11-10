@echo off
rem Builds DeskLinkHost.exe (release, no console). "build.cmd test" also builds DeskLinkHostTest.exe
rem (console + developer self-tests). Requires Visual Studio 2022 with the C++ workload.
setlocal
cd /d "%~dp0"
if not defined VSCMD_VER (
  call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" >nul || exit /b 1
)
if not exist build\obj mkdir build\obj
if not exist build\objt mkdir build\objt
if not exist res\app.ico powershell -NoProfile -ExecutionPolicy Bypass -File res\make-icon.ps1 || exit /b 1
rc /nologo /fo build\app.res res\app.rc || exit /b 1

set CFLAGS=/nologo /std:c++20 /EHsc /O2 /MT /W3 /utf-8 /Zi /DUNICODE /D_UNICODE /DWIN32_LEAN_AND_MEAN /DNOMINMAX /D_CRT_SECURE_NO_WARNINGS
set LFLAGS=/MANIFEST:EMBED /MANIFESTUAC:NO /MANIFESTINPUT:res\app.manifest /DEBUG /OPT:REF /OPT:ICF
set LIBS=d3d11.lib dxgi.lib mfplat.lib mfuuid.lib mf.lib ws2_32.lib bcrypt.lib crypt32.lib user32.lib gdi32.lib gdiplus.lib shell32.lib ole32.lib oleaut32.lib advapi32.lib dwmapi.lib wtsapi32.lib userenv.lib iphlpapi.lib shlwapi.lib winmm.lib comctl32.lib winhttp.lib setupapi.lib

if /i "%1"=="test" goto test
cl %CFLAGS% /Fobuild\obj\ /Fdbuild\obj\ /Febuild\DeskLinkHost.exe src\*.cpp build\app.res /link /SUBSYSTEM:WINDOWS %LFLAGS% %LIBS% || exit /b 1
goto end
:test
cl %CFLAGS% /DDESKLINK_CONSOLE /DDESKLINK_TESTING /Fobuild\objt\ /Fdbuild\objt\ /Febuild\DeskLinkHostTest.exe src\*.cpp build\app.res /link /SUBSYSTEM:CONSOLE %LFLAGS% %LIBS% || exit /b 1
:end
echo BUILD OK
