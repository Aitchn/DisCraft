"""Bounded client startup gates for the isolated real-game acceptance controller."""
import time

def wait_for_graphics_ready(process, log, role, timeout=120, interval=.25):
    """One JVM owns cold GL initialization at a time; never restart or ignore a failed child."""
    deadline = time.monotonic() + timeout
    while True:
        # Forge emits the early-window result before verbose game/resource loading.
        with log.open('rb') as stream:
            text = stream.read(1024 * 1024).decode('utf-8', errors='replace')
        if 'ERROR DISPLAY' in text or 'Exception in thread "main"' in text:
            raise RuntimeError(f'{role} graphical startup failed; see {role}.log')
        status = process.poll()
        if status is not None:
            raise RuntimeError(f'{role} exited during graphical startup ({status}); see {role}.log')
        if 'GL info:' in text or f'MULTIPLAYER {role} ' in text:
            return
        if time.monotonic() >= deadline:
            raise RuntimeError(f'{role} graphical startup timeout; see {role}.log')
        time.sleep(interval)
