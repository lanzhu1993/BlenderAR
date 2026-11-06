"""Launch the smoke scene with --python live_smoke.py to publish timeline frames."""
import os
from pathlib import Path
from queue import Empty, Full, Queue
from threading import Thread
from urllib.error import URLError
from urllib.request import Request, urlopen

import bpy

scene_path = Path(bpy.data.filepath).resolve()
frames = scene_path.parent / "preview" / os.environ.get("BLENDER_AR_FRAMES", "violet_fire_ar")
endpoint = os.environ.get("BLENDER_AR_SERVER", "http://127.0.0.1:8000").rstrip("/")
code = os.environ["PAIRING_CODE"]
pending = Queue(maxsize=1)


def publish():
    while True:
        frame = pending.get()
        try:
            data = (frames / f"f_{frame:04d}.png").read_bytes()
            request = Request(f"{endpoint}/api/live/smoke/{frame}", data=data, method="PUT",
                              headers={"X-Pairing-Code": code, "Content-Type": "image/png"})
            with urlopen(request, timeout=2):
                pass
        except (OSError, URLError) as error:
            print(f"BlenderAR frame {frame}: {error}", flush=True)


def frame_changed(scene, _depsgraph=None):
    if Path(bpy.data.filepath).resolve() != scene_path:
        return
    try:
        pending.put_nowait(scene.frame_current)
    except Full:
        try:
            pending.get_nowait()
        except Empty:
            pass
        pending.put_nowait(scene.frame_current)


if not (frames / f"f_{bpy.context.scene.frame_end:04d}.png").is_file():
    raise RuntimeError(f"Render the RGBA sequence into {frames} before starting live playback")
Thread(target=publish, daemon=True).start()
bpy.app.handlers.frame_change_post.append(frame_changed)
frame_changed(bpy.context.scene)
