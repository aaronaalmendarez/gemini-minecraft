#!/usr/bin/env python3
import argparse
import json
import os
import shlex
import subprocess
import sys
import time
from pathlib import Path
from urllib.error import URLError
from urllib.request import urlopen


PROTOCOL_VERSION = "2025-03-26"


def default_server_command(project_root: Path) -> list[str]:
    windows_root = str(project_root).replace("/mnt/c/", "C:/").replace("/", "\\")
    if os.name == "nt":
        return [
            r"C:\Program Files\nodejs\node.exe",
            str(project_root / "run-mcp-sidecar-node.js"),
            "--project-root",
            str(project_root),
        ]
    return [
        "/mnt/c/Windows/System32/cmd.exe",
        "/d",
        "/c",
        f"{windows_root}\\run-mcp-sidecar.cmd",
    ]


def read_mcp_message(stream) -> tuple[dict, str]:
    header = bytearray()
    recent = bytearray()
    while True:
        ch = stream.read(1)
        if not ch:
            raise EOFError("EOF while reading MCP headers")
        header.extend(ch)
        recent.extend(ch)
        if len(recent) > 4:
            recent = recent[-4:]
        if recent == b"\r\n\r\n" or recent.endswith(b"\n\n"):
            break
    header_text = header.decode("utf-8", errors="replace")
    content_length = None
    for line in header_text.replace("\r\n", "\n").split("\n"):
        if ":" not in line:
            continue
        key, value = line.split(":", 1)
        if key.strip().lower() == "content-length":
            content_length = int(value.strip())
            break
    if content_length is None:
        raise ValueError(f"Missing Content-Length header: {header_text!r}")
    body = stream.read(content_length)
    if len(body) != content_length:
        raise EOFError("EOF while reading MCP body")
    payload = body.decode("utf-8", errors="replace")
    return json.loads(payload), header_text


def write_mcp_message(stream, payload: dict) -> None:
    raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    stream.write(f"Content-Length: {len(raw)}\r\n\r\n".encode("utf-8"))
    stream.write(raw)
    stream.flush()


def print_frame(direction: str, payload: dict) -> None:
    print(f"\n=== {direction} ===")
    print(json.dumps(payload, indent=2, ensure_ascii=False))


def check_bridge_health(url: str) -> None:
    try:
        with urlopen(url, timeout=3) as response:
            body = response.read().decode("utf-8", errors="replace")
            print("\n=== BRIDGE HEALTH ===")
            print(body)
    except URLError as exc:
        print(f"\n=== BRIDGE HEALTH ERROR ===\n{exc}")


def main() -> int:
    project_root = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description="Debug a stdio MCP server for gemini-minecraft.")
    parser.add_argument("--command", help="Full command line to launch instead of the default sidecar.")
    parser.add_argument("--list-tools", action="store_true", help="Send tools/list after initialize.")
    parser.add_argument("--call", help="Tool name to call after initialize.")
    parser.add_argument("--args-json", default="{}", help="JSON object passed to tools/call arguments.")
    parser.add_argument("--bridge-health", action="store_true", help="Probe the MCP bridge health endpoint before stdio handshake.")
    parser.add_argument("--timeout-sec", type=float, default=8.0, help="Read timeout for response polling.")
    args = parser.parse_args()

    cmd = shlex.split(args.command, posix=os.name != "nt") if args.command else default_server_command(project_root)
    print("Launching MCP server:")
    print("  " + " ".join(shlex.quote(part) for part in cmd))

    if args.bridge_health:
        check_bridge_health("http://127.0.0.1:7766/v1/health")

    proc = subprocess.Popen(
        cmd,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    assert proc.stdin is not None and proc.stdout is not None and proc.stderr is not None

    try:
        init = {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {},
                "clientInfo": {"name": "debug-mcp-client", "version": "1.0"},
            },
        }
        print_frame("SEND initialize", init)
        write_mcp_message(proc.stdin, init)
        start = time.time()
        while True:
            if time.time() - start > args.timeout_sec:
                raise TimeoutError("Timed out waiting for initialize response")
            if proc.poll() is not None:
                stderr = proc.stderr.read().decode("utf-8", errors="replace")
                raise RuntimeError(f"MCP process exited early with code {proc.returncode}. stderr:\n{stderr}")
            if proc.stdout.peek(1):
                break
            time.sleep(0.05)
        response, _ = read_mcp_message(proc.stdout)
        print_frame("RECV initialize", response)

        initialized = {"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}}
        print_frame("SEND notifications/initialized", initialized)
        write_mcp_message(proc.stdin, initialized)

        if args.list_tools:
            request = {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}}
            print_frame("SEND tools/list", request)
            write_mcp_message(proc.stdin, request)
            response, _ = read_mcp_message(proc.stdout)
            print_frame("RECV tools/list", response)

        if args.call:
            try:
                tool_args = json.loads(args.args_json)
            except json.JSONDecodeError as exc:
                raise SystemExit(f"--args-json must be valid JSON: {exc}") from exc
            if not isinstance(tool_args, dict):
                raise SystemExit("--args-json must decode to a JSON object")
            request = {
                "jsonrpc": "2.0",
                "id": 3,
                "method": "tools/call",
                "params": {"name": args.call, "arguments": tool_args},
            }
            print_frame("SEND tools/call", request)
            write_mcp_message(proc.stdin, request)
            response, _ = read_mcp_message(proc.stdout)
            print_frame("RECV tools/call", response)

        return 0
    finally:
        try:
            proc.stdin.close()
        except Exception:
            pass
        try:
            proc.terminate()
        except Exception:
            pass
        try:
            proc.wait(timeout=2)
        except Exception:
            try:
                proc.kill()
            except Exception:
                pass
        stderr = proc.stderr.read().decode("utf-8", errors="replace").strip()
        if stderr:
            print("\n=== STDERR ===")
            print(stderr)


if __name__ == "__main__":
    raise SystemExit(main())
