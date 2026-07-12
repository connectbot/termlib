import fcntl
import json
import os
import pty
import select
import struct
import subprocess
import termios
import time

master, slave = pty.openpty()
fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack('HHHH', 24, 80, 0, 0))
env = dict(os.environ, TERM='xterm-256color', CACA_DRIVER='ncurses')
process = subprocess.Popen(['/usr/bin/cacafire'], stdin=slave, stdout=slave, stderr=slave, env=env, start_new_session=True)
os.close(slave)
start = time.monotonic()
frames = []
data = bytearray()
try:
    while time.monotonic() - start < 20:
        if select.select([master], [], [], 0.05)[0]:
            chunk = os.read(master, 65536)
            if not chunk:
                break
            data.extend(chunk)
            frames.append([time.monotonic() - start, len(chunk)])
finally:
    process.terminate()
    process.wait(timeout=5)
    os.close(master)
with open('/tmp/termlib-cacafire.bin', 'wb') as output:
    output.write(data)
with open('/tmp/termlib-cacafire.json', 'w') as output:
    json.dump({'seconds': 20, 'rows': 24, 'columns': 80, 'reads': frames}, output)
print(f'Captured {len(data)} bytes in {len(frames)} reads over 20 seconds')
