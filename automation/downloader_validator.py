#!/usr/bin/env python3
"""Headless API validator for downloader regressions.

Checks each URL for:
1) /api/detect classification
2) /api/preview fields (thumbnail, title, description)
3) /api/download success + /api/file/<filename> retrievability

Usage examples:
  python automation/downloader_validator.py --base-url http://127.0.0.1:5000
  python automation/downloader_validator.py --base-url https://quicksavevideos.com --include-unknown
  python automation/downloader_validator.py --base-url http://127.0.0.1:5000 --output-json reports/validator.json
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from dataclasses import asdict, dataclass, field
from typing import Dict, List, Optional, Tuple
from urllib.parse import urljoin

import requests


DEFAULT_CASES = [
    {
        "name": "Pinterest",
        "url": "https://pin.it/6De5hmpnC",
        "expected_detect": "pinterest_post",
    },
    {
        "name": "YouTube",
        "url": "https://youtube.com/shorts/cl0odsrNOc0?si=79_edw4T9AxuQHyI",
        "expected_detect": "yt_video",
    },
    {
        "name": "Instagram",
        "url": "https://www.instagram.com/reel/DK_XSFXTaRt/?hl=en",
        "expected_detect": "reel",
    },
    {
        "name": "X",
        "url": "https://x.com/nsinghal211/status/2042484856282849526?s=20",
        "expected_detect": "twitter_video",
    },
]

# A generic downloader sample (unknown to explicit platform handlers)
DEFAULT_UNKNOWN_CASE = {
    "name": "Unknown-Generic",
    "url": "https://vimeo.com/76979871",
    "expected_detect": "generic",
}


@dataclass
class CheckResult:
    name: str
    url: str
    detect_ok: bool = False
    detect_value: str = ""
    preview_ok: bool = False
    thumbnail_ok: bool = False
    title_ok: bool = False
    description_ok: bool = False
    download_ok: bool = False
    file_fetch_ok: bool = False
    file_name: str = ""
    file_size_bytes_seen: int = 0
    content_type: str = ""
    errors: List[str] = field(default_factory=list)

    @property
    def passed(self) -> bool:
        return (
            self.detect_ok
            and self.preview_ok
            and self.thumbnail_ok
            and self.title_ok
            and self.description_ok
            and self.download_ok
            and self.file_fetch_ok
        )


class Validator:
    def __init__(self, base_url: str, timeout: int, retries: int) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.retries = retries
        self.session = requests.Session()

    def _post_json(self, path: str, payload: Dict) -> Tuple[Optional[requests.Response], Optional[Dict], Optional[str]]:
        url = urljoin(self.base_url + "/", path.lstrip("/"))
        last_error = None
        for attempt in range(1, self.retries + 1):
            try:
                resp = self.session.post(url, json=payload, timeout=self.timeout)
                data = None
                try:
                    data = resp.json()
                except Exception:
                    data = None
                return resp, data, None
            except Exception as e:  # noqa: BLE001
                last_error = str(e)
                if attempt < self.retries:
                    time.sleep(min(1.5 * attempt, 4.0))
        return None, None, last_error

    def _get_stream(self, path: str) -> Tuple[Optional[requests.Response], Optional[str], int, str]:
        url = urljoin(self.base_url + "/", path.lstrip("/"))
        last_error = None
        for attempt in range(1, self.retries + 1):
            try:
                resp = self.session.get(url, timeout=self.timeout, stream=True)
                bytes_seen = 0
                if resp.status_code == 200:
                    for chunk in resp.iter_content(chunk_size=1024 * 64):
                        if not chunk:
                            continue
                        bytes_seen += len(chunk)
                        # We only need proof that content is downloadable and non-empty.
                        if bytes_seen >= 1024:
                            break
                return resp, None, bytes_seen, resp.headers.get("Content-Type", "")
            except Exception as e:  # noqa: BLE001
                last_error = str(e)
                if attempt < self.retries:
                    time.sleep(min(1.5 * attempt, 4.0))
        return None, last_error, 0, ""

    def run_case(self, case: Dict) -> CheckResult:
        result = CheckResult(name=case["name"], url=case["url"])

        # 1) detect
        d_resp, d_json, d_err = self._post_json("/api/detect", {"url": case["url"]})
        if d_err:
            result.errors.append(f"detect request failed: {d_err}")
        elif d_resp is None or d_resp.status_code != 200 or not isinstance(d_json, dict):
            status = d_resp.status_code if d_resp is not None else "no-response"
            result.errors.append(f"detect bad response: status={status}, body={d_json}")
        else:
            result.detect_value = str(d_json.get("url_type", ""))
            expected = case.get("expected_detect")
            if expected:
                result.detect_ok = (result.detect_value == expected)
                if not result.detect_ok:
                    result.errors.append(
                        f"detect mismatch: expected={expected}, got={result.detect_value}"
                    )
            else:
                result.detect_ok = bool(result.detect_value)

        # 2) preview
        p_resp, p_json, p_err = self._post_json("/api/preview", {"url": case["url"]})
        if p_err:
            result.errors.append(f"preview request failed: {p_err}")
        elif p_resp is None or p_resp.status_code != 200 or not isinstance(p_json, dict):
            status = p_resp.status_code if p_resp is not None else "no-response"
            result.errors.append(f"preview bad response: status={status}, body={p_json}")
        else:
            preview = p_json.get("preview", {}) if isinstance(p_json, dict) else {}
            result.preview_ok = bool(p_json.get("success") and isinstance(preview, dict))
            result.thumbnail_ok = bool(preview.get("thumbnail"))
            result.title_ok = bool((preview.get("title") or "").strip())
            result.description_ok = bool((preview.get("description") or "").strip())
            if not result.preview_ok:
                result.errors.append(f"preview missing success payload: {p_json}")
            if not result.thumbnail_ok:
                result.errors.append("preview thumbnail missing")
            if not result.title_ok:
                result.errors.append("preview title missing")
            if not result.description_ok:
                result.errors.append("preview description missing")

        # 3) download
        dl_payload = {"url": case["url"], "content_type": "both", "quality": "best"}
        dl_resp, dl_json, dl_err = self._post_json("/api/download", dl_payload)
        if dl_err:
            result.errors.append(f"download request failed: {dl_err}")
            return result

        if dl_resp is None or dl_resp.status_code != 200 or not isinstance(dl_json, dict):
            status = dl_resp.status_code if dl_resp is not None else "no-response"
            result.errors.append(f"download bad response: status={status}, body={dl_json}")
            return result

        result.download_ok = bool(dl_json.get("success"))
        result.file_name = str(dl_json.get("filename", ""))
        if not result.download_ok or not result.file_name:
            result.errors.append(f"download payload invalid: {dl_json}")
            return result

        # 4) fetch produced file
        f_resp, f_err, seen, ctype = self._get_stream(f"/api/file/{result.file_name}")
        result.file_size_bytes_seen = seen
        result.content_type = ctype
        if f_err:
            result.errors.append(f"file fetch failed: {f_err}")
            return result
        if f_resp is None or f_resp.status_code != 200:
            status = f_resp.status_code if f_resp is not None else "no-response"
            result.errors.append(f"file fetch bad status: {status}")
            return result

        # Accept common binary/file content types from Flask send_file responses.
        result.file_fetch_ok = seen > 0 and (
            "video" in ctype.lower()
            or "image" in ctype.lower()
            or "audio" in ctype.lower()
            or "application/octet-stream" in ctype.lower()
            or ctype == ""
        )
        if not result.file_fetch_ok:
            result.errors.append(f"file content validation failed: bytes={seen}, content-type={ctype}")

        return result


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Headless downloader validator")
    parser.add_argument("--base-url", default="http://127.0.0.1:5000", help="API base URL")
    parser.add_argument("--timeout", type=int, default=45, help="HTTP timeout seconds")
    parser.add_argument("--retries", type=int, default=2, help="Retry attempts per request")
    parser.add_argument("--include-unknown", action="store_true", help="Include generic unknown-site test case")
    parser.add_argument("--unknown-url", default="", help="Override unknown-site sample URL")
    parser.add_argument("--output-json", default="", help="Optional path to write full JSON report")
    return parser.parse_args()


def main() -> int:
    args = parse_args()

    cases = list(DEFAULT_CASES)
    if args.include_unknown:
        unknown_case = dict(DEFAULT_UNKNOWN_CASE)
        if args.unknown_url.strip():
            unknown_case["url"] = args.unknown_url.strip()
        cases.append(unknown_case)

    validator = Validator(base_url=args.base_url, timeout=args.timeout, retries=args.retries)

    print(f"\nValidator target: {args.base_url}")
    print(f"Total cases: {len(cases)}\n")

    results: List[CheckResult] = []
    for case in cases:
        res = validator.run_case(case)
        results.append(res)

        status = "PASS" if res.passed else "FAIL"
        print(f"[{status}] {res.name}")
        print(f"  URL: {res.url}")
        print(f"  detect: {res.detect_value} (ok={res.detect_ok})")
        print(f"  preview: ok={res.preview_ok}, thumbnail={res.thumbnail_ok}, title={res.title_ok}, description={res.description_ok}")
        print(f"  download: ok={res.download_ok}, file={res.file_name}, fetch_ok={res.file_fetch_ok}, bytes_seen={res.file_size_bytes_seen}")
        if res.errors:
            for e in res.errors:
                print(f"  - {e}")
        print()

    passed = sum(1 for r in results if r.passed)
    failed = len(results) - passed
    summary = {
        "base_url": args.base_url,
        "total": len(results),
        "passed": passed,
        "failed": failed,
        "results": [asdict(r) | {"passed": r.passed} for r in results],
    }

    print("Summary:")
    print(json.dumps({k: v for k, v in summary.items() if k != "results"}, indent=2))

    if args.output_json:
        with open(args.output_json, "w", encoding="utf-8") as f:
            json.dump(summary, f, indent=2)
        print(f"\nReport written: {args.output_json}")

    return 0 if failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())

