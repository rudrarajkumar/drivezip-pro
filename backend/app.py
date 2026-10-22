import os
import re
import json
import time
import shutil
import subprocess
from pathlib import Path
from typing import Dict, List, Set, Tuple

import httpx
from fastapi import FastAPI, Header, HTTPException, Depends
from pydantic import BaseModel

app = FastAPI(title="DriveZip Pro Cloud Backend", version="2.0.0")

BACKEND_API_KEY = os.environ.get("BACKEND_API_KEY", "drivezip2026secret")
WORK_DIR = Path("/tmp/drivezip_jobs")
WORK_DIR.mkdir(parents=True, exist_ok=True)

# Standard bitrate presets: target height, video bitrate (kbps), audio bitrate (kbps)
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
    """Fallback duration/resolution probe over HTTP Range if Drive videoMediaMetadata is empty."""
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
            # Never upscale: copy original as-is
            estimates[q_name] = {"bytes": orig_size, "is_copy": True, "label": "copy (<= target)"}
        else:
            if duration_ms > 0:
                total_kbps = preset["v_kbps"] + preset["a_kbps"]
                est_bytes = int((total_kbps * 1000 / 8.0) * dur_sec * 1.03)
            else:
                ratio = (target_h / float(max(short_side, target_h))) ** 1.35
                est_bytes = int(orig_size * ratio)

            if est_bytes >= orig_size:
                # Never exceed original size
                estimates[q_name] = {"bytes": orig_size, "is_copy": True, "label": "copy (smaller orig)"}
            else:
                estimates[q_name] = {"bytes": max(est_bytes, 64 * 1024), "is_copy": False, "label": "estimated"}
    return estimates


def scan_drive_recursive(resource_id: str, oauth_token: str, api_key: str) -> dict:
    headers, base_params = build_drive_auth(oauth_token, api_key)
    fields_single = "id,name,mimeType,size,videoMediaMetadata,shortcutDetails,capabilities(canDownload)"
    fields_list = f"nextPageToken,files({fields_single})"

    documents_list: List[dict] = []
    videos_list: List[dict] = []

    with httpx.Client(follow_redirects=True) as client:
        root_meta = drive_api_get(
            client,
            f"https://www.googleapis.com/drive/v3/files/{resource_id}",
            headers,
            {**base_params, "fields": fields_single},
        )

        # Resolve shortcut if root link points to a shortcut
        if root_meta.get("mimeType") == "application/vnd.google-apps.shortcut":
            target_id = root_meta.get("shortcutDetails", {}).get("targetId")
            if target_id:
                root_meta = drive_api_get(
                    client,
                    f"https://www.googleapis.com/drive/v3/files/{target_id}",
                    headers,
                    {**base_params, "fields": fields_single},
                )

        root_name = sanitize_filename(root_meta.get("name", "DriveItem"))
        is_folder = root_meta.get("mimeType") == "application/vnd.google-apps.folder"

        def process_item(item: dict, rel_folder: str, seen_names: Set[str]):
            mime = item.get("mimeType", "")

            # Resolve shortcut inside folder
            if mime == "application/vnd.google-apps.shortcut":
                t_id = item.get("shortcutDetails", {}).get("targetId")
                if not t_id:
                    return
                try:
                    item = drive_api_get(
                        client,
                        f"https://www.googleapis.com/drive/v3/files/{t_id}",
                        headers,
                        {**base_params, "fields": fields_single},
                    )
                    mime = item.get("mimeType", "")
                except Exception:
                    return

            # If subfolder, recurse while preserving relative folder structure
            if mime == "application/vnd.google-apps.folder":
                sub_name = sanitize_filename(item.get("name", "Subfolder"))
                next_rel = f"{rel_folder}/{sub_name}" if rel_folder else sub_name
                walk_folder(item["id"], next_rel)
                return

            raw_name = sanitize_filename(item.get("name", "file"))
            is_workspace = mime in WORKSPACE_EXPORTS
            export_mime, export_ext = WORKSPACE_EXPORTS.get(mime, ("", ""))
            if is_workspace and not raw_name.lower().endswith(export_ext):
                raw_name += export_ext

            # Deduplicate identical filenames within the same folder
            base, ext = os.path.splitext(raw_name)
            unique_name = raw_name
            counter = 1
            while unique_name.lower() in seen_names:
                unique_name = f"{base} ({counter}){ext}"
                counter += 1
            seen_names.add(unique_name.lower())

            orig_size = int(item.get("size", 0) or 0)
            if is_workspace and orig_size == 0:
                orig_size = 512 * 1024

            is_video = mime.startswith("video/")
            rel_path = f"{rel_folder}/{unique_name}" if rel_folder else unique_name

            if is_video:
                v_meta = item.get("videoMediaMetadata", {})
                width = int(v_meta.get("width", 0) or 0)
                height = int(v_meta.get("height", 0) or 0)
                duration_ms = int(v_meta.get("durationMillis", 0) or 0)

                if duration_ms == 0:
                    width, height, duration_ms = probe_video_via_ffprobe(item["id"], headers, base_params)

                qualities = calculate_video_estimates(orig_size, width, height, duration_ms)

                videos_list.append({
                    "id": item["id"],
                    "name": unique_name,
                    "rel_path": rel_path,
                    "mime_type": mime,
                    "orig_size": orig_size,
                    "width": width,
                    "height": height,
                    "duration_ms": duration_ms,
                    "can_download": item.get("capabilities", {}).get("canDownload", True),
                    "qualities": qualities,
                })
            else:
                documents_list.append({
                    "id": item["id"],
                    "name": unique_name,
                    "rel_path": rel_path,
                    "mime_type": mime,
                    "orig_size": orig_size,
                    "is_workspace": is_workspace,
                    "export_mime": export_mime,
                    "can_download": item.get("capabilities", {}).get("canDownload", True),
                })

        def walk_folder(folder_id: str, rel_folder: str):
            page_token = None
            seen_names: Set[str] = set()
            while True:
                q_params = {
                    **base_params,
                    "q": f"'{folder_id}' in parents and trashed = false",
                    "pageSize": 100,
                    "fields": fields_list,
                    "orderBy": "folder,name",
                }
                if page_token:
                    q_params["pageToken"] = page_token
                res = drive_api_get(client, "https://www.googleapis.com/drive/v3/files", headers, q_params)
                for child in res.get("files", []):
                    process_item(child, rel_folder, seen_names)
                page_token = res.get("nextPageToken")
                if not page_token:
                    break

        if is_folder:
            walk_folder(root_meta["id"], "")
        else:
            process_item(root_meta, "", set())

    video_quality_totals = {}
    for q in ["Original", "480p", "360p", "240p", "144p"]:
        video_quality_totals[q] = sum(v["qualities"][q]["bytes"] for v in videos_list)

    document_total_bytes = sum(d["orig_size"] for d in documents_list)
    video_total_duration_sec = int(sum(v["duration_ms"] for v in videos_list) / 1000)

    return {
        "resource_id": root_meta["id"],
        "title": root_name,
        "is_folder": is_folder,
        "total_files": len(documents_list) + len(videos_list),
        "document_count": len(documents_list),
        "document_total_bytes": document_total_bytes,
        "video_count": len(videos_list),
        "video_total_duration_sec": video_total_duration_sec,
        "video_quality_totals": video_quality_totals,
        "documents": documents_list,
        "videos": videos_list,
    }


class InspectRequest(BaseModel):
    drive_link: str
    oauth_token: str = ""
    api_key: str = ""


@app.get("/")
def root():
    return {"service": "DriveZip Pro Cloud Backend", "milestone": 2, "status": "online"}


@app.get("/api/v1/health")
def health(_: bool = Depends(verify_key)):
    total, used, free = shutil.disk_usage(WORK_DIR)
    return {"authenticated": True, "milestone": 2, "disk_free_gb": round(free / (1024 ** 3), 2)}


@app.post("/api/v1/inspect")
def inspect_resource(req: InspectRequest, _: bool = Depends(verify_key)):
    resource_id = extract_drive_id(req.drive_link)
    return scan_drive_recursive(resource_id, req.oauth_token, req.api_key)
