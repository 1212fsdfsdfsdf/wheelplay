#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Download Android System WebView 97.0.4692.98 (armeabi-v7a / 32-bit) APK.
APKMirror: variant page -> download/?key=... -> intermediate page -> CDN .apk.
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

    # Visit keyed url -> intermediate page
    r3 = s.get(key_url, timeout=120, allow_redirects=True)
    print(f"[apkmirror] keyed status={r3.status_code} final={r3.url[:160]} len={len(r3.content)}")
    text = r3.text

    # Dump every link / url that could be the apk
    apk_candidates = []
    for m in re.finditer(r'(https?://[^\s"\']+\.apk[^\s"\']*)', text):
        apk_candidates.append(m.group(1))
    if not apk_candidates:
        # Some pages embed it in JS: var url='...' or "url":"..."
        for m in re.finditer(r'https?://[^\s"\']*apkmirror\.com[^\s"\']*', text):
            u = m.group(0)
            if "download" in u or ".apk" in u:
                apk_candidates.append(u)
    print(f"[apkmirror] apk candidates: {len(apk_candidates)}")
    for c in apk_candidates[:10]:
        print("[apkmirror]   cand:", c[:200])

    for c in apk_candidates:
        try:
            r4 = s.get(c, timeout=300, stream=True, allow_redirects=True)
            total = 0
            with open(OUT, "wb") as f:
                for chunk in r4.iter_content(1024 * 256):
                    if chunk:
                        f.write(chunk)
                        total += len(chunk)
            print(f"[apkmirror] try cand saved={total} final={r4.url[:160]}")
            if total > 1000000:
                return total
        except Exception as e:
            print(f"[apkmirror] cand error: {e}")

    # fallback: dump script vars
    print("[apkmirror] dumping script snippets around 'download'")
    for m in re.finditer(r'download[^;]{0,120}', text):
        print("   ", m.group(0)[:140])
        break
    return None


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
