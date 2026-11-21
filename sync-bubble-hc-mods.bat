@echo off
rem Copies the MoonahBubbleHC Prism instance's mods to the Bubble HC server (stop the server first).
cd /d "%~dp0"
python sync-bubble-hc-mods.py %*
pause
