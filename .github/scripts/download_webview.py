#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Download Android System WebView 97.0.4692.98 (armeabi-v7a / 32-bit) APK.
Uptodown: versions page -> version page -> download button -> CDN apk.
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
BASE = "https://android-system-webview.en.uptodown.com"


def save_stream(resp):
    total = 0
    with open(OUT, "wb") as f:
        for chunk in resp.iter_content(1024 * 256):
            if chunk:
                f.write(chunk)
                total += len(chunk)
    return total


def uptodown():
    s = requests.Session()
    s.headers.update(HEADERS)
    # 1. versions list page
    versions_url = BASE + "/android/versions"
    r = s.get(versions_url, timeout=90)
    print(f"[uptodown] versions page status={r.status_code} len={len(r.text)}")
    # find link to version 97.0.4692.98
    ver_link = None
    for m in re.finditer(r'href="([^"]*)"[^>]*>([^<]*97\.0\.4692\.98[^<]*)<', r.text):
        ver_link = m.group(1)
        print(f"[uptodown] found version link: {ver_link}")
        break
    if not ver_link:
        # try post links near version text
        idx = r.text.find("97.0.4692.98")
        if idx > 0:
            chunk = r.text[max(0, idx-3000):idx+500]
            m = re.search(r'href="([^"]*(?:post|download)/[^"]*)"', chunk)
            if m:
                ver_link = m.group(1)
                print(f"[uptodown] post link near version: {ver_link}")
    if not ver_link:
        # fallback: just take the first post/download link on page
        m = re.search(r'href="(/android/post/\d+)"', r.text)
        if m:
            ver_link = m.group(1)
            print(f"[uptodown] fallback post link: {ver_link}")
    if not ver_link:
        print("[uptodown] NO version link; dump sample links")
        for mm in re.finditer(r'href="([^"]*)"', r.text[:100000]):
            if "post" in mm.group(1) or "download" in mm.group(1):
                print("  link:", mm.group(1)[:120])
        return None
    if ver_link.startswith("/"):
        ver_link = BASE + ver_link
    # 2. version page
    r2 = s.get(ver_link, timeout=90)
    print(f"[uptodown] version page status={r2.status_code} len={len(r2.text)} url={ver_link}")
    # 3. download button: data-url or form action or dw.uptodown link
    dl_url = None
    m = re.search(r'data-url="([^"]*)"', r2.text)
    if m and "download" in m.group(1):
        dl_url = m.group(1)
        print(f"[uptodown] data-url: {dl_url}")
    if not dl_url:
        m = re.search(r'id="detail-download-button"[^>]*data-url="([^"]*)"', r2.text)
        if m:
            dl_url = m.group(1)
    if not dl_url:
        m = re.search(r'(https?://dw\.uptodown\.com/[^"\']+\.apk[^"\']*)', r2.text)
        if m:
            dl_url = m.group(1)
            print(f"[uptodown] direct dw link: {dl_url[:150]}")
    if not dl_url:
        m = re.search(r'(/post/download/\d+)', r2.text)
        if m:
            dl_url = m.group(1)
    if not dl_url:
        print("[uptodown] NO download url; dump button area")
        soup = BeautifulSoup(r2.text, "html.parser")
        btn = soup.select_one("#detail-download-button")
        print("  btn html:", str(btn)[:500] if btn else "none")
        for mm in re.finditer(r'[^"\']*download[^"\']*', r2.text[:200000]):
            print("  cand:", mm.group(0)[:120])
        return None
    if dl_url.startswith("/"):
        dl_url = BASE + dl_url
    # 4. hit the download url; expect 302 to dw.uptodown.com CDN
    r3 = s.get(dl_url, timeout=120, allow_redirects=True, stream=True)
    print(f"[uptodown] dl status={r3.status_code} final={r3.url[:160]}")
    total = save_stream(r3)
    print(f"[uptodown] saved bytes={total}")
    if total > 1000000:
        return total
    # 5. if that returned html, search inside for apk link
    r3c = None
    s2 = requests.Session()
    s2.headers.update(HEADERS)
    m2 = re.search(r'(https?://[^"\']+\.apk[^"\']*)', r3.text[:500000] if hasattr(r3, "text") else "")
    if m2:
        r4 = s2.get(m2.group(1), timeout=300, stream=True)
        total = save_stream(r4)
        print(f"[uptodown] second hop saved={total}")
        return total if total > 1000000 else None
    return None


def main():
    try:
        ok = uptodown()
        if ok:
            print("SUCCESS")
            return 0
    except Exception as e:
        print(f"[uptodown] error: {e}")
    print("FAILED")
    return 1


if __name__ == "__main__":
    sys.exit(main())
