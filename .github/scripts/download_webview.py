#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Download Android System WebView 97.0.4692.98 (armeabi-v7a / 32-bit) APK.
Tries APKMirror -> APKCombo -> APKPure. Runs inside GitHub Actions (overseas network).
"""
import re
import sys
import time
import requests
from bs4 import BeautifulSoup

HEADERS = {
    "User-Agent": ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                   "(KHTML, like Gecko) Chrome/97.0.4692.98 Safari/537.36"),
    "Accept-Language": "en-US,en;q=0.9",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
}

VERSION = "97.0.4692.98"
OUT = "webview97-armeabi-v7a.apk"


def apkmirror():
    base = "https://www.apkmirror.com"
    # Version page for Android System WebView 97.0.4692.98
    page = (base + "/apk/google-inc/android-system-webview/"
            "android-system-webview-97-0-4692-98-release/")
    s = requests.Session()
    s.headers.update(HEADERS)
    r = s.get(page, timeout=120)
    print(f"[apkmirror] version page status={r.status_code} len={len(r.text)}")
    if r.status_code != 200:
        return None
    soup = BeautifulSoup(r.text, "html.parser")
    # Each variant row has a downloadButton anchor; ABI is printed near the row.
    candidates = []
    for a in soup.select("a.downloadButton"):
        href = a.get("href") or ""
        # walk up to find the ABI label
        node = a
        abi = ""
        for _ in range(8):
            node = node.parent
            if node is None:
                break
            txt = node.get_text(" ", strip=True)
            m = re.search(r"armeabi-v7a|arm64-v8a|x86_64|x86\b", txt)
            if m:
                abi = m.group(0)
                break
        if href:
            candidates.append((abi, href))
    print("[apkmirror] candidates:", candidates[:10])
    target = None
    for abi, href in candidates:
        if abi == "armeabi-v7a":
            target = href
            break
    if not target:
        for abi, href in candidates:
            if abi:
                target = href
                break
    if not target and candidates:
        target = candidates[0][1]
    if not target:
        print("[apkmirror] no download link found")
        return None
    dl_page = base + target
    r2 = s.get(dl_page, timeout=120)
    print(f"[apkmirror] dl page status={r2.status_code} len={len(r2.text)}")
    m = re.search(r'href="(/wp-content/themes/APKMirror/download\.php\?id=\d+)"', r2.text)
    if not m:
        m = re.search(r'(https?://[^"\']*download\.php\?id=\d+)', r2.text)
    if not m:
        print("[apkmirror] no download.php link")
        return None
    dl = m.group(1) if m.group(1).startswith("http") else base + m.group(1)
    r3 = s.get(dl, timeout=120, allow_redirects=True)
    print(f"[apkmirror] final status={r3.status_code} url={r3.url}")
    final = None
    if ".apk" in r3.url:
        final = r3.url
    else:
        m2 = re.search(r'download\.php\?id=(\d+)&key=([0-9a-f]+)', r3.text)
        if m2:
            final = base + f"/wp-content/themes/APKMirror/download.php?id={m2.group(1)}&key={m2.group(2)}"
        else:
            m3 = re.search(r'(https?://[^"\']+\.apk[^"\']*)', r3.text)
            if m3 and "download.php" not in m3.group(1):
                final = m3.group(1)
    if not final:
        print("[apkmirror] cannot resolve apk url")
        return None
    print("[apkmirror] downloading from:", final)
    r4 = s.get(final, timeout=300, stream=True)
    total = 0
    with open(OUT, "wb") as f:
        for chunk in r4.iter_content(1024 * 256):
            if chunk:
                f.write(chunk)
                total += len(chunk)
    print(f"[apkmirror] saved bytes={total}")
    return total if total > 1000000 else None


def apkcombo():
    base = "https://apkcombo.com"
    page = base + "/android-system-webview/com.google.android.webview/download/apk"
    s = requests.Session()
    s.headers.update(HEADERS)
    r = s.get(page, timeout=120, allow_redirects=True)
    print(f"[apkcombo] page status={r.status_code} len={len(r.text)} url={r.url}")
    # APKCombo returns a direct-ish download after JS; look for download URL patterns
    m = re.search(r'(https?://[^"\']+\.apk[^"\']*)', r.text)
    if not m:
        print("[apkcombo] no apk link")
        return None
    url = m.group(1)
    print("[apkcombo] downloading from:", url)
    r2 = s.get(url, timeout=300, stream=True)
    total = 0
    with open(OUT, "wb") as f:
        for chunk in r2.iter_content(1024 * 256):
            if chunk:
                f.write(chunk)
                total += len(chunk)
    print(f"[apkcombo] saved bytes={total}")
    return total if total > 1000000 else None


def apkpure():
    # Old-style APKPure direct link (may redirect to cdn)
    version_codes = {
        "97.0.4692.98": "469209800",
    }
    vc = version_codes.get(VERSION)
    if not vc:
        print("[apkpure] no version code mapping")
        return None
    url = f"https://d.apkpure.com/b/APK/com.google.android.webview?versionCode={vc}"
    s = requests.Session()
    s.headers.update(HEADERS)
    r = s.get(url, timeout=300, stream=True, allow_redirects=True)
    print(f"[apkpure] status={r.status_code} final={r.url}")
    total = 0
    with open(OUT, "wb") as f:
        for chunk in r.iter_content(1024 * 256):
            if chunk:
                f.write(chunk)
                total += len(chunk)
    print(f"[apkpure] saved bytes={total}")
    return total if total > 1000000 else None


def main():
    for fn in (apkmirror, apkcombo, apkpure):
        try:
            ok = fn()
            if ok:
                print(f"SUCCESS via {fn.__name__}")
                return 0
        except Exception as e:
            print(f"[{fn.__name__}] error: {e}")
            time.sleep(2)
    print("ALL SOURCES FAILED")
    return 1


if __name__ == "__main__":
    sys.exit(main())
