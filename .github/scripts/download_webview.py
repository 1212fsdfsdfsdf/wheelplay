#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Download Android System WebView 97.0.4692.98 (armeabi-v7a / 32-bit) APK.
APKMirror: variant page -> download/?key=... -> APK CDN.
"""
import re
import sys
import requests
from bs4 import BeautifulSoup

HEADERS = {
    "User-Agent": ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                   "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"),
    "Accept-Language": "en-US,en;q=0.9",
}

OUT = "webview97-armeabi-v7a.apk"


def apkmirror():
    base = "https://www.apkmirror.com"
    page = (base + "/apk/google-inc/android-system-webview/"
            "android-system-webview-97-0-4692-98-release/")
    s = requests.Session()
    s.headers.update(HEADERS)
    r = s.get(page, timeout=120)
    print(f"[apkmirror] version page status={r.status_code} len={len(r.text)}")
    soup = BeautifulSoup(r.text, "html.parser")
    target = None
    for row in soup.select("div.table-row"):
        txt = row.get_text(" ", strip=True)
        a = row.select_one('a[href*="apk-download"]')
        if a and ("APK" in txt) and ("armeabi-v7a" in txt) and ("Android 10" not in txt):
            target = a.get("href")
            print(f"[apkmirror] selected variant: {target}")
            break
    if not target:
        print("[apkmirror] no suitable row")
        return None

    dl_page = base + target
    r2 = s.get(dl_page, timeout=120)
    print(f"[apkmirror] dl page status={r2.status_code} len={len(r2.text)}")
    soup2 = BeautifulSoup(r2.text, "html.parser")
    # The real download button: href contains /download/?key=
    key_url = None
    for a in soup2.find_all("a", href=True):
        href = a.get("href", "")
        if "/download/?key=" in href:
            key_url = href
            break
    if not key_url:
        print("[apkmirror] no keyed download url")
        return None
    if key_url.startswith("/"):
        key_url = base + key_url
    print(f"[apkmirror] keyed url: {key_url[:180]}")

    r3 = s.get(key_url, timeout=120, allow_redirects=True, stream=True)
    print(f"[apkmirror] status={r3.status_code} final={r3.url[:200]} len={r3.headers.get('Content-Length')}")
    # APKMirror returns 302 to CDN then binary; follow and save
    total = 0
    with open(OUT, "wb") as f:
        for chunk in r3.iter_content(1024 * 256):
            if chunk:
                f.write(chunk)
                total += len(chunk)
    print(f"[apkmirror] saved bytes={total}")
    return total if total > 1000000 else None


def main():
    try:
        ok = apkmirror()
        if ok:
            print("SUCCESS")
            return 0
    except Exception as e:
        print(f"[apkmirror] error: {e}")
    print("FAILED")
    return 1


if __name__ == "__main__":
    sys.exit(main())
