"""assets/ 目录监听:Blender 重导出后自动 reload → 广播 manifest.changed。

watchdog 在独立线程回调(仅记录时间戳);轮询协程在静默期(防抖)后于事件循环执行回调。
"""
from __future__ import annotations

import asyncio
import threading
import time
from pathlib import Path
from typing import Callable, Optional

from watchdog.events import FileSystemEventHandler
from watchdog.observers import Observer


class _Handler(FileSystemEventHandler):
    """仅记录最后事件时间(线程安全;实际触发在轮询协程)。"""

    def __init__(self) -> None:
        super().__init__()
        self._last_event_at = 0.0
        self._lock = threading.Lock()

    def on_any_event(self, event):
        if event.is_directory:
            return
        with self._lock:
            self._last_event_at = time.time()

    def last_event_at(self) -> float:
        with self._lock:
            return self._last_event_at


class AssetsWatcher:
    """watchdog Observer 包装。on_change 在事件循环中被调用(静默 DEBOUNCE_S 后)。"""

    DEBOUNCE_S = 1.2

    def __init__(self, watch_dir: Path, on_change: Callable[[], None]):
        self.watch_dir = Path(watch_dir)
        self.on_change = on_change
        self._handler = _Handler()
        self._observer: Optional[Observer] = None
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._task: Optional[asyncio.Task] = None
        self._handled_at = 0.0

    def start(self, loop: asyncio.AbstractEventLoop) -> None:
        self._loop = loop
        self.watch_dir.mkdir(parents=True, exist_ok=True)
        self._observer = Observer(timeout=0.5)
        self._observer.schedule(self._handler, str(self.watch_dir), recursive=True)
        self._observer.start()
        self._task = loop.create_task(self._poll_loop())

    async def _poll_loop(self) -> None:
        while True:
            await asyncio.sleep(0.4)
            last = self._handler.last_event_at()
            if last <= self._handled_at:
                continue
            if time.time() - last >= self.DEBOUNCE_S:
                self._handled_at = last
                try:
                    await self._loop.run_in_executor(None, self.on_change)
                except Exception as exc:      # 回调异常不影响监听循环
                    print(f"[watcher] on_change 异常: {exc}")

    def stop(self) -> None:
        if self._task:
            self._task.cancel()
        if self._observer:
            self._observer.stop()
            self._observer.join(timeout=2)
