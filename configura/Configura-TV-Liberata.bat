@echo off
title TV Liberata - Configurazione
color 0A
rem Configura TV Liberata: lancia lo script PowerShell accanto a questo file.
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Configura-TV-Liberata.ps1" %*
