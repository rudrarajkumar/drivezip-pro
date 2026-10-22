import os
import re
import json
import time
import shutil
import subprocess
from pathlib import Path
from typing import Dict, Tuple

import httpx
from fastapi import FastAPI, Header, HTTPException, Depends
from pydantic import BaseModel

app = FastAPI(title="DriveZip Pro Cloud Backend", version="1.0.0")

BACKEND_API_KEY = os.environ.get("BACKEND_API_KEY", "drivezip2026secret")
WORK_DIR = Path("/tmp/drivezip_jobs")
WORK_DIR.mkdir(parents=True, exist_ok=True)

QUALITY_PRESETS = {
    "480p": {"height": 480, "v_kbps": 750, "a_kbps": 96},
    "360p": {"height": 360, "v_kbps": 420, "a_kbps": 96},
    "240p": {"height": 240, "v_kbps": 240, "a_kbps": 64},
    "144p": {"height": 144, "v_kbps": 130, "a_kbps": 64},
}

WORKSPACE_EXPORTS = {
    "application/vnd.google-apps.document": ("application/pdf", ".pdf"),
    "application/vnd.google-apps.spreadsheet": ("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx"),
    "application/vnd.google-apps.presentation": ("application/pdf", ".pdf"),
}


def verify_key(x_backend_key: str = Header(default="")):
    if x_backend_key != BACKEND_API_KEY:
        raise HTTPException(status_code=401, detail="Unauthorized: Invalid X-Backend-Key")
    return True


def sanitize_filename(name: str) -> str:
    cleaned = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", name).strip()
    return cleaned if cleaned else "unnamed_file"


def extract_drive_id(link_or_id: str) -> str:
    s = link_or_id.strip()
    for pat in (
        r"/folders/([a-zA-Z0-9_-]{10,})",
        r"/file/d/([a-zA-Z0-9_-]{10,})",
        r"/d/([a-zA-Z0-9_-]{10,})",
        r"[?&]id=([a-zA-Z0-9_-]{10,})",
    ):
        m = re.search(pat, s)
        if m:
            return m.group(1)
    if re.fullmatch(r"[a-zA-Z0-9_-]{10,}", s):
        return s
    raise HTTPException(status_code=400, detail="Invalid Google Drive link or ID format.")


def build_drive_auth(oauth_token: str, api_key: str):
    headers = {}
    params = {"supportsAllDrives": "true", "includeItemsFromAllDrives": "true"}
    if oauth_token.strip():
        headers["Authorization"] = f"Bearer {oauth_token.strip()}"
    elif api_key.strip():
        params["key"] = api_key.strip()
    else:
        raise HTTPException(
            status_code=400,
            detail="Missing Google Auth! In Tab 2, Apply your Google Drive API Key (for public links) or Sign In with Google (for private/shared links).",
        )
    return headers, params


def drive_api_get(client: httpx.Client, url: str, headers: dict, params: dict) -> dict:
    for attempt in range(4):
        resp = client.get(url, headers=headers, params=params, timeout=30.0)
        if resp.status_code == 200:
            return resp.json()
        if resp.status_code in (429, 500, 502, 503, 504):
            time.sleep(1.5 * (attempt + 1))
            continue
        try:
            msg = resp.json().get("error", {}).get("message", resp.text)
        except Exception:
            msg = resp.text
        raise HTTPException(status_code=resp.status_code, detail=f"Google Drive API Error ({resp.status_code}): {msg}")
    raise HTTPException(status_code=504, detail="Google Drive API timed out.")


def probe_video_via_ffprobe(file_id: str, headers: dict, params: dict) -> Tuple[int, int, int]:
    try:
        dl_url = f"https://www.googleapis.com/drive/v3/files/{file_id}?alt=media&supportsAllDrives=true"
        if "key" in params:
            dl_url += f"&key={params['key']}"
        cmd = [
            "ffprobe", "-v", "error",
            "-select_streams", "v:0",
            "-show_entries", "stream=width,height:format=duration",
            "-of", "json",
        ]
        if "Authorization" in headers:
            cmd.extend(["-headers", f"Authorization: {headers['Authorization']}\r\n"])
        cmd.append(dl_url)
        out = subprocess.check_output(cmd, stderr=subprocess.DEVNULL, text=True, timeout=15)
        data = json.loads(out)
        streams = data.get("streams", [{}])
        w = int(streams[0].get("width", 1280) or 1280)
        h = int(streams[0].get("height", 720) or 720)
        dur = float(data.get("format", {}).get("duration", 0) or 0)
        return w, h, int(dur * 1000)
    except Exception:
        return 1280, 720, 0


def calculate_video_estimates(orig_size: int, width: int, height: int, duration_ms: int) -> Dict[str, dict]:
    short_side = min(width, height) if (width and height) else 720
    dur_sec = max(duration_ms / 1000.0, 1.0)
    estimates = {"Original": {"bytes": orig_size, "is_copy": True, "label": "exact"}}
    for q_name, preset in QUALITY_PRESETS.items():
        target_h = preset["height"]
        if short_side <= target_h:
            estimates[q_name] = {"bytes": orig_size, "is_copy": True, "label": "copy (<= target)"}
        else:
            if duration_ms > 0:
                total_kbps = preset["v_kbps"] + preset["a_kbps"]
                est_bytes = int((total_kbps * 1000 / 8.0) * dur_sec * 1.03)
            else:
                ratio = (target_h / float(max(short_side, target_h))) ** 1.35
                est_bytes = int(orig_size * ratio)

            if est_bytes >= orig_size:
                estimates[q_name] = {"bytes": orig_size, "is_copy": True, "label": "copy (smaller orig)"}
            else:
                estimates[q_name] = {"bytes": max(est_bytes, 64 * 1024), "is_copy": False, "label": "estimated"}
    return estimates


class InspectRequest(BaseModel):
    drive_link: str
    oauth_token: str = ""
    api_key: str = ""


@app.get("/")
def root():
    return {"service": "DriveZip Pro Cloud Backend", "milestone": 1, "status": "online"}


@app.get("/api/v1/health")
def health(_: bool = Depends(verify_key)):
    total, used, free = shutil.disk_usage(WORK_DIR)
    return {"authenticated": True, "milestone": 1, "disk_free_gb": round(free / (1024 ** 3), 2)}


@app.post("/api/v1/inspect")
def inspect_resource(req: InspectRequest, _: bool = Depends(verify_key)):
    resource_id = extract_drive_id(req.drive_link)
    headers, base_params = build_drive_auth(req.oauth_token, req.api_key)
    fields = "id,name,mimeType,size,videoMediaMetadata,shortcutDetails,capabilities(canDownload)"

    with httpx.Client(follow_redirects=True) as client:
        item = drive_api_get(
            client,
            f"https://www.googleapis.com/drive/v3/files/{resource_id}",
            headers,
            {**base_params, "fields": fields},
        )
        if item.get("mimeType") == "application/vnd.google-apps.shortcut":
            target_id = item.get("shortcutDetails", {}).get("targetId")
            if target_id:
                item = drive_api_get(
                    client,
                    f"https://www.googleapis.com/drive/v3/files/{target_id}",
                    headers,
                    {**base_params, "fields": fields},
                )

        mime = item.get("mimeType", "")
        is_folder = mime == "application/vnd.google-apps.folder"
        raw_name = sanitize_filename(item.get("name", "DriveItem"))
        orig_size = int(item.get("size", 0) or 0)

        if mime in WORKSPACE_EXPORTS:
            _, ext = WORKSPACE_EXPORTS[mime]
            if not raw_name.lower().endswith(ext):
                raw_name += ext
            if orig_size == 0:
                orig_size = 512 * 1024

        is_video = mime.startswith("video/")
        v_meta = item.get("videoMediaMetadata", {})
        width = int(v_meta.get("width", 0) or 0)
        height = int(v_meta.get("height", 0) or 0)
        duration_ms = int(v_meta.get("durationMillis", 0) or 0)

        if is_video and duration_ms == 0:
            width, height, duration_ms = probe_video_via_ffprobe(item["id"], headers, base_params)

        qualities = calculate_video_estimates(orig_size, width, height, duration_ms) if is_video else {}

        return {
            "resource_id": item["id"],
            "name": raw_name,
            "mime_type": mime,
            "is_folder": is_folder,
            "is_video": is_video,
            "is_document": not is_video and not is_folder,
            "orig_size_bytes": orig_size,
            "width": width,
            "height": height,
            "duration_ms": duration_ms,
            "can_download": item.get("capabilities", {}).get("canDownload", True),
            "qualities": qualities,
        }
