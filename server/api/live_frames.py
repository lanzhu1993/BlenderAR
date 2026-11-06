"""Latest-frame relay for a single Blender preview on the LAN."""
import asyncio
import struct

from fastapi import HTTPException, Request, Response, WebSocket

MAX_FRAME_BYTES = 2 * 1024 * 1024
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def register_live_frames(app, check_code):
    subscribers = set()
    latest = None

    @app.put("/api/live/smoke/{frame}", status_code=204)
    async def publish(frame: int, request: Request):
        nonlocal latest
        check_code(request, None)
        if not 1 <= frame <= 540:
            raise HTTPException(422, "Frame must be in 1–540")
        data = bytearray()
        async for chunk in request.stream():
            if len(data) + len(chunk) > MAX_FRAME_BYTES:
                raise HTTPException(413, "Frame too large")
            data.extend(chunk)
        if (len(data) < 33 or data[:8] != PNG_SIGNATURE or
                data[12:16] != b"IHDR" or data[25] != 6 or
                struct.unpack_from(">II", data, 16) != (480, 480)):
            raise HTTPException(415, "Expected a 480×480 RGBA PNG")
        latest = struct.pack(">I", frame) + data
        for queue in subscribers:
            if queue.full():
                queue.get_nowait()
            queue.put_nowait(latest)
        return Response(status_code=204)

    @app.websocket("/ws/live/smoke")
    async def subscribe(ws: WebSocket):
        code = ws.headers.get("x-pairing-code") or ws.query_params.get("code")
        if code != app.state.ctx.pairing_code:
            await ws.close(code=4401)
            return
        await ws.accept()
        queue = asyncio.Queue(maxsize=1)
        subscribers.add(queue)
        if latest is not None:
            queue.put_nowait(latest)

        async def watch_disconnect():
            while (await ws.receive())["type"] != "websocket.disconnect":
                pass

        async def send_frames():
            while True:
                await asyncio.wait_for(ws.send_bytes(await queue.get()), timeout=5)

        tasks = [asyncio.create_task(watch_disconnect()), asyncio.create_task(send_frames())]
        try:
            await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        finally:
            subscribers.discard(queue)
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
