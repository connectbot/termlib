#!/usr/bin/env python3
# ConnectBot Terminal
# Copyright 2026 Kenny Root
# SPDX-License-Identifier: Apache-2.0
"""Drive the opt-in TalkBack device test through emulator touchscreen hardware.

Requires grpcio and grpcio-tools. Run while the test emulator is running, before
starting the instrumentation test with talkback=true. The emulator INI is advertised
in its startup log (usually /run/user/<uid>/avd/running/pid_<pid>.ini).
"""

import argparse
import importlib
from pathlib import Path
import subprocess
import sys
import tempfile
import time

import grpc
import grpc_tools
from grpc_tools import protoc
from google.protobuf.empty_pb2 import Empty

REQUEST = "/data/local/tmp/termlib-talkback-touch"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--emulator-ini", type=Path, required=True)
    parser.add_argument("--seconds", type=int, default=300)
    args = parser.parse_args()
    properties = dict(
        line.split("=", 1) for line in args.emulator_ini.read_text().splitlines() if "=" in line
    )
    properties = {key.strip(): value.strip() for key, value in properties.items()}
    serial = "emulator-" + properties["port.serial"]
    adb = [str(args.sdk / "platform-tools/adb"), "-s", serial, "shell"]

    def shell(command):
        return subprocess.run(adb + [command], capture_output=True, text=True, timeout=10)

    with tempfile.TemporaryDirectory(prefix="termlib-emulator-proto-") as generated:
        proto = args.sdk / "emulator/lib"
        result = protoc.main([
            "protoc", f"-I{proto}", f"-I{Path(grpc_tools.__file__).parent / '_proto'}",
            f"--python_out={generated}", f"--grpc_python_out={generated}",
            str(proto / "emulator_controller.proto"),
        ])
        if result:
            raise RuntimeError("Unable to generate emulator gRPC bindings")
        sys.path.insert(0, generated)
        messages = importlib.import_module("emulator_controller_pb2")
        bindings = importlib.import_module("emulator_controller_pb2_grpc")
        metadata = [("authorization", "Bearer " + properties["grpc.token"])]
        with grpc.insecure_channel("127.0.0.1:" + properties["grpc.port"]) as channel:
            emulator = bindings.EmulatorControllerStub(channel)
            emulator.getStatus(Empty(), metadata=metadata, timeout=10)

            def touch(x, y, pressure):
                emulator.sendTouch(messages.TouchEvent(touches=[
                    messages.Touch(x=x, y=y, identifier=0, pressure=pressure)
                ]), metadata=metadata, timeout=10)

            shell(f"rm -f {REQUEST} {REQUEST}.done")
            print(f"Touchscreen bridge ready for {serial}", flush=True)
            deadline = time.monotonic() + args.seconds
            previous = ""
            try:
                while time.monotonic() < deadline:
                    request = shell(f"cat {REQUEST}").stdout.strip()
                    if not request or request == previous:
                        time.sleep(0.05)
                        continue
                    previous = request
                    identifier, action, *coordinates = request.split()
                    if not identifier.isdigit() or action not in ("tap", "swipe", "explore", "longpress"):
                        raise ValueError("Invalid touchscreen request")
                    points = list(map(int, coordinates))
                    x, y = points[:2]
                    try:
                        if action == "tap" and len(points) == 2:
                            touch(x, y, 800)
                            time.sleep(0.15)
                        elif action == "longpress" and len(points) == 2:
                            # TalkBack's double-tap-and-hold gesture, through hardware.
                            touch(x, y, 800)
                            time.sleep(0.06)
                            touch(x, y, 0)
                            time.sleep(0.08)
                            touch(x, y, 800)
                            time.sleep(1.2)
                        elif action in ("swipe", "explore") and len(points) == 5:
                            if action == "explore":
                                # Let TalkBack's gesture detector enter touch exploration.
                                touch(x, y, 800)
                                time.sleep(0.8)
                            end_x, end_y, duration = points[2:]
                            for step in range(17):
                                x = round(points[0] + (end_x - points[0]) * step / 16)
                                y = round(points[1] + (end_y - points[1]) * step / 16)
                                touch(x, y, 800)
                                time.sleep(duration / 16000)
                            if action == "explore":
                                time.sleep(0.4)
                        else:
                            raise ValueError("Invalid touchscreen coordinates")
                    finally:
                        touch(x, y, 0)
                    shell(f"echo {identifier} > {REQUEST}.done")
                    print(f"Completed {identifier}: {action}", flush=True)
            finally:
                shell(f"rm -f {REQUEST} {REQUEST}.done")


if __name__ == "__main__":
    main()
