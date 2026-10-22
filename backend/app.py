import os
import re
import json
import time
import shutil
import subprocess
import threading
from pathlib import Path
from typing import Dict, List, Set, Tuple
from concurrent.futures import ThreadPoolExecutor, as_completed

import httpx
from fastapi import FastAPI, Header, HTTPException, Depends
from pydantic import BaseModel

app = FastAPI(title="DriveZip Pro Cloud Backend", version="3.0.0")

BACKEND_API_KEY = os.environ.get("BACKEND_API_KEY", "drivezip2026secret")
WORK_DIR = Path("/tmp/drivezip_jobs")
WORK_DIR.mkdir(parents=True, exist_ok=True)

# YouTube-grade lecture bitrates: target height, video bitrate (kbps), audio bitrate (kbps)
QUALITY_PRESETS = {
    "480p": {"height": 480, "v_kbps": 400, "a_kbps": 64},
    "360p": {"height": 360, "v_kbps": 220, "a_kbps": 64},
    "240p": {"height": 240, "v_kbps": 135, "a_kbps": 48},
    "144p": {"height": 144, "v_kbps": 80,  "a_kbps": 48},
}

WORKSPACE_EXPORTS = {
    "application/vnd.google-apps.document": ("application/pdf", ".pdf"),
    "application/vnd.google-apps.spreadsheet": ("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx"),
    "application/vnd.google-apps.presentation": ("application/pdf", ".pdf"),
}

JOBS_LOCK = threading.Lock()
ACTIVE_JOBS: Dict[str, dict] = {}


def verify_key(x_backend_key: str = Header(default="")):
    if x_backend_key != BACKEND_API_KEY:
        raise HTTPException(status_code=401, detail="Unauthorized: Invalid X-Backend-Key")
    return True


def detect_gpu() -> bool:
    try:
        out = subprocess.check_output(
            ["ffmpeg", "-hide_banner", "-encoders"],
            stderr=subprocess.STDOUT,
            text=True,
            timeout=10,
        )
        if "h264_nvenc" not in out:
            return False
        return subprocess.run(["nvidia-smi"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    except Exception:
        return False


HAS_GPU = detect_gpu()


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
            estimates[q_name] = {"bytes": orig_size, "is_copy": True, "label": "copy (<= target)"}
        else:
            if duration_ms > 0:
                total_kbps = preset["v_kbps"] + preset["a_kbps"]
                est_bytes = int((total_kbps * 1000 / 8.0) * dur_sec * 1.02)
            else:
                ratio = (target_h / float(max(short_side, target_h))) ** 1.45
                est_bytes = int(orig_size * ratio)

            if est_bytes >= orig_size:
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


def download_drive_file(file_info: dict, dest_path: Path, headers: dict, base_params: dict):
    fid = file_info["id"]
    if file_info.get("is_workspace", False):
        url = f"https://www.googleapis.com/drive/v3/files/{fid}/export"
        params = {**base_params, "mimeType": file_info.get("export_mime", "application/pdf")}
    else:
        url = f"https://www.googleapis.com/drive/v3/files/{fid}"
        params = {**base_params, "alt": "media", "acknowledgeAbuse": "true"}

    dest_path.parent.mkdir(parents=True, exist_ok=True)
    for attempt in range(4):
        with httpx.Client(follow_redirects=True, timeout=120.0) as client:
            with client.stream("GET", url, headers=headers, params=params) as resp:
                if resp.status_code == 200:
                    with open(dest_path, "wb") as f:
                        for chunk in resp.iter_bytes(chunk_size=1024 * 512):
                            f.write(chunk)
                    return
                if resp.status_code in (429, 500, 502, 503, 504):
                    time.sleep(2.0 * (attempt + 1))
                    continue
                raise RuntimeError(f"HTTP {resp.status_code} downloading {file_info['name']}")
    raise RuntimeError(f"Download failed after retries: {file_info['name']}")


def convert_video_ffmpeg(src_path: Path, dst_path: Path, target_quality: str):
    preset = QUALITY_PRESETS[target_quality]
    h = preset["height"]
    v_kbps = preset["v_kbps"]
    a_kbps = preset["a_kbps"]

    # Scale height to target without upscaling; keep width divisible by 2
    scale_filter = f"scale=-2:'min(ih,{h})'"
    if HAS_GPU:
        v_codec = [
            "-c:v", "h264_nvenc",
            "-preset", "p4",
            "-b:v", f"{v_kbps}k",
            "-maxrate", f"{int(v_kbps * 1.2)}k",
            "-bufsize", f"{v_kbps * 2}k",
        ]
    else:
        v_codec = [
            "-c:v", "libx264",
            "-preset", "veryfast",
            "-tune", "stillimage",
            "-crf", "30",
            "-b:v", f"{v_kbps}k",
            "-maxrate", f"{int(v_kbps * 1.15)}k",
            "-bufsize", f"{v_kbps * 2}k",
            "-threads", "2",
        ]

    cmd = [
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-i", str(src_path),
        "-vf", scale_filter,
        *v_codec,
        "-c:a", "aac", "-b:a", f"{a_kbps}k", "-ac", "1",
        "-movflags", "+faststart",
        str(dst_path),
    ]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        raise RuntimeError(f"FFmpeg error: {res.stderr[-300:]}")


def run_conversion_job(
    job_id: str,
    scan_data: dict,
    bulk_quality: str,
    video_overrides: Dict[str, str],
    oauth_token: str,
    api_key: str,
):
    job_dir = WORK_DIR / job_id
    out_tree = job_dir / "converted_files"
    tmp_tree = job_dir / "tmp_downloads"
    ckpt_file = job_dir / "checkpoint.json"

    for d in (out_tree, tmp_tree):
        d.mkdir(parents=True, exist_ok=True)

    completed_ids = {}
    if ckpt_file.exists():
        try:
            completed_ids = json.loads(ckpt_file.read_text())
        except Exception:
            completed_ids = {}

    headers, base_params = build_drive_auth(oauth_token, api_key)

    all_items = []
    for d in scan_data["documents"]:
        all_items.append({**d, "is_video": False, "target_quality": "Original"})
    for v in scan_data["videos"]:
        q_target = video_overrides.get(v["id"], bulk_quality)
        if q_target == "SKIP":
            continue
        all_items.append({**v, "is_video": True, "target_quality": q_target})

    with JOBS_LOCK:
        ACTIVE_JOBS[job_id]["total_files"] = len(all_items)
        ACTIVE_JOBS[job_id]["state"] = "CONVERTING"

    def process_one_item(item: dict):
        fid = item["id"]
        rel_path = item["rel_path"]
        is_vid = item["is_video"]
        q_target = item["target_quality"]

        if is_vid and q_target != "Original":
            base_no_ext, _ = os.path.splitext(rel_path)
            final_rel = f"{base_no_ext}.mp4"
        else:
            final_rel = rel_path

        final_dest = out_tree / final_rel
        final_dest.parent.mkdir(parents=True, exist_ok=True)

        est_bytes = item["orig_size"]
        if is_vid and q_target in QUALITY_PRESETS:
            est_bytes = item["qualities"].get(q_target, {}).get("bytes", item["orig_size"])

        # Checkpointing: Skip if already processed at the same quality
        if fid in completed_ids and completed_ids[fid].get("quality") == q_target and final_dest.exists():
            actual_sz = final_dest.stat().st_size
            return {
                "id": fid,
                "name": item["name"],
                "rel_path": final_rel,
                "is_video": is_vid,
                "quality": q_target,
                "orig_bytes": item["orig_size"],
                "estimated_bytes": est_bytes,
                "actual_bytes": actual_sz,
                "action": "cached (checkpoint)",
                "skipped": True,
            }

        with JOBS_LOCK:
            ACTIVE_JOBS[job_id]["current_file"] = f"{item['name']} ({q_target})"

        _, _, free_b = shutil.disk_usage(WORK_DIR)
        if free_b < 1.5 * (1024 ** 3):
            raise RuntimeError("Cloud server disk space low (<1.5 GB free).")

        if not item.get("can_download", True):
            raise RuntimeError(f"Download restricted by file owner: {item['name']}")

        action_label = "copied original"
        if not is_vid or q_target == "Original":
            download_drive_file(item, final_dest, headers, base_params)
        else:
            q_info = item["qualities"].get(q_target, {})
            needs_transcode = not q_info.get("is_copy", False)

            tmp_src = tmp_tree / f"{fid}_raw"
            tmp_out = tmp_tree / f"{fid}_conv.mp4"
            try:
                download_drive_file(item, tmp_src, headers, base_params)
                if needs_transcode:
                    convert_video_ffmpeg(tmp_src, tmp_out, q_target)
                    # Never exceed original size: keep smaller file
                    if tmp_out.stat().st_size < tmp_src.stat().st_size:
                        shutil.move(str(tmp_out), str(final_dest))
                        action_label = f"converted to {q_target}"
                    else:
                        shutil.move(str(tmp_src), str(final_dest))
                        action_label = "kept original (smaller than transcode)"
                else:
                    shutil.move(str(tmp_src), str(final_dest))
                    action_label = "copied (<= target resolution)"
            finally:
                tmp_src.unlink(missing_ok=True)
                tmp_out.unlink(missing_ok=True)

        actual_sz = final_dest.stat().st_size
        with JOBS_LOCK:
            completed_ids[fid] = {"quality": q_target, "actual_bytes": actual_sz}
            ckpt_file.write_text(json.dumps(completed_ids))

        return {
            "id": fid,
            "name": item["name"],
            "rel_path": final_rel,
            "is_video": is_vid,
            "quality": q_target,
            "orig_bytes": item["orig_size"],
            "estimated_bytes": est_bytes,
            "actual_bytes": actual_sz,
            "action": action_label,
            "skipped": False,
        }

    # Process 2 files in parallel (2 workers * 2 FFmpeg threads = 4 CPU cores on GitHub VM)
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = {pool.submit(process_one_item, it): it for it in all_items}
        for fut in as_completed(futures):
            f_meta = futures[fut]
            try:
                res = fut.result()
                with JOBS_LOCK:
                    ACTIVE_JOBS[job_id]["processed_files"] += 1
                    ACTIVE_JOBS[job_id]["actual_bytes_so_far"] += res["actual_bytes"]
                    ACTIVE_JOBS[job_id]["file_results"].append(res)
            except Exception as e:
                with JOBS_LOCK:
                    ACTIVE_JOBS[job_id]["processed_files"] += 1
                    ACTIVE_JOBS[job_id]["errors"].append(f"{f_meta['name']}: {str(e)}")

    with JOBS_LOCK:
        ACTIVE_JOBS[job_id]["state"] = "COMPLETED"
        ACTIVE_JOBS[job_id]["current_file"] = "All files converted and verified!"


class InspectRequest(BaseModel):
    drive_link: str
    oauth_token: str = ""
    api_key: str = ""


class StartJobRequest(BaseModel):
    drive_link: str
    bulk_quality: str = "360p"
    video_overrides: Dict[str, str] = {}
    oauth_token: str = ""
    api_key: str = ""


@app.get("/")
def root():
    return {"service": "DriveZip Pro Cloud Backend", "milestone": 3, "status": "online", "gpu": HAS_GPU}


@app.get("/api/v1/health")
def health(_: bool = Depends(verify_key)):
    total, used, free = shutil.disk_usage(WORK_DIR)
    return {"authenticated": True, "milestone": 3, "disk_free_gb": round(free / (1024 ** 3), 2), "gpu": HAS_GPU}


@app.post("/api/v1/inspect")
def inspect_resource(req: InspectRequest, _: bool = Depends(verify_key)):
    resource_id = extract_drive_id(req.drive_link)
    return scan_drive_recursive(resource_id, req.oauth_token, req.api_key)


@app.post("/api/v1/jobs/start")
def start_job(req: StartJobRequest, _: bool = Depends(verify_key)):
    resource_id = extract_drive_id(req.drive_link)
    scan_data = scan_drive_recursive(resource_id, req.oauth_token, req.api_key)
    job_id = f"{resource_id}_{req.bulk_quality}"

    with JOBS_LOCK:
        if job_id in ACTIVE_JOBS and ACTIVE_JOBS[job_id]["state"] == "CONVERTING":
            return ACTIVE_JOBS[job_id]

        est_v_bytes = 0
        for v in scan_data["videos"]:
            q_target = req.video_overrides.get(v["id"], req.bulk_quality)
            if q_target != "SKIP":
                est_v_bytes += v["qualities"].get(q_target, {}).get("bytes", v["orig_size"])

        est_total = scan_data["document_total_bytes"] + est_v_bytes

        ACTIVE_JOBS[job_id] = {
            "job_id": job_id,
            "title": scan_data["title"],
            "bulk_quality": req.bulk_quality,
            "state": "STARTING",
            "processed_files": 0,
            "total_files": scan_data["total_files"],
            "current_file": "Starting parallel FFmpeg workers...",
            "estimated_total_bytes": est_total,
            "actual_bytes_so_far": 0,
            "file_results": [],
            "errors": [],
        }

    threading.Thread(
        target=run_conversion_job,
        args=(job_id, scan_data, req.bulk_quality, req.video_overrides, req.oauth_token, req.api_key),
        daemon=True,
    ).start()

    return ACTIVE_JOBS[job_id]


@app.get("/api/v1/jobs/{job_id}/status")
def get_job_status(job_id: str, _: bool = Depends(verify_key)):
    with JOBS_LOCK:
        if job_id not in ACTIVE_JOBS:
            raise HTTPException(status_code=404, detail="Job ID not found")
        return ACTIVE_JOBS[job_id]
