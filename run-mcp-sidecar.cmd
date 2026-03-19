@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
set "NODE_BIN=C:\Program Files\nodejs\node.exe"
if exist "%NODE_BIN%" (
  "%NODE_BIN%" "%SCRIPT_DIR%run-mcp-sidecar-node.js" --project-root "%SCRIPT_DIR%"
) else (
  node "%SCRIPT_DIR%run-mcp-sidecar-node.js" --project-root "%SCRIPT_DIR%"
)
