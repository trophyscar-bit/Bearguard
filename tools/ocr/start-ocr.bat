@echo off
REM Bearguard's OCR reader. Self-contained: the environment and the model weights both live in
REM this directory, so nothing is installed outside the bot's own tree.
cd /d "%~dp0"
"%~dp0venv\Scripts\python.exe" "%~dp0ocr_service.py" 6975
