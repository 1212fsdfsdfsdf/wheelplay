#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Download Android System WebView 97.0.4692.98 (armeabi-v7a / 32-bit) APK.
Tries APKMirror -> Uptodown -> APKPure. Runs in GitHub Actions.
"""
import re
import sys
import time
import requests
from bs4 import BeautifulSoup

HEADERS = {
    "User-Agent": ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                   "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"),
    "Accept-Language": "en-US,en;q=0.9",
    "Accept": ("text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,"
               "image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"),
}

OUT = "webview97-armeabi-v7a.apk"


def save_stream(resp):
    total = 0
    with open(OUT, "wb") as f:
        for chunk in resp.iter_content(1024 * 256):
            if chunk:
                f.write(chunk)
                total += len(chunk)
    return total


def apkmirror():
    base = "https://www.apkmirror.com"
    page = (base + "/apk/google-inc/android-system-webview/"
            "android-system-webview-97-0-4692-98-release/")
    s = requests.Session()
    s.headers.update(HEADERS)
    r = s.get(page, timeout=120)
    print(f"[apkmirror] version page status={r.status_code} len={len(r.text)}")
    if r.status_code != 200:
        return None
    soup = BeautifulSoup(r.text, "html.parser")
    rows = soup.select("div.table-row")
    print(f"[apkmirror] table rows: {len(rows)}")
    target = None
    for row in rows:
        txt = row.get_text(" ", strip=True)
        a = row.select_one('a[href*="apk-download"]')
        if not a:
            continue
        # We want the full APK for armeabi-v7a, NOT BUNDLE, and for Android 8.x (avoid "Android 10")
        if ("APK" in txt) and ("armeabi-v7a" in txt) and ("Android 10" not in txt):
            target = a.get("href")
            print(f"[apkmirror] SELECTED: {txt[:110]!r} href={target}")
            break
    if not target:
        print("[apkmirror] no suitable APK/armeabi-v7a row")
        return None
    dl_page = base + target
    print(f"[apkmirror] dl page: {dl_page}")
    r2 = s.get(dl_page, timeout=120)
    print(f"[apkmirror] dl page status={r2.status_code} len={len(r2.text)}")

    # Dump every anchor + data attributes mentioning download/php/id
    soup2 = BeautifulSoup(r2.text, "html.parser")
    found = False
    for a in soup2.find_all("a", href=True):
        href = a["href"]
        if "download" in href.lower() or "php" in href.lower():
            print(f"[apkmirror] anchor: {href[:200]} rel={a.get('rel')} class={a.get('class')}")
            found = True
    # Also search raw text for download.php patterns
    for mm in re.finditer(r'[^\s"\']*(?:download\.php|/wp-content/)[^\s"\']*', r2.text):
        print(f"[apkmirror] raw cand: {mm.group(0)[:200]}")
        found = True
    if not found:
        print("[apkmirror] NO download anchors found at all")

    # Try known patterns
    candidates = []
    m = re.search(r'href="([^"]*download\.php\?[^"]*)"', r2.text)
    if m:
        candidates.append(m.group(1))
    m = re.search(r'data-(?:url|link|id)="([^"]*)"', r2.text)
    if m:
        candidates.append(m.group(1))
    for c in candidates:
        if c.startswith("/"):
            c = base + c
        print(f"[apkmirror] try candidate: {c[:200]}")
        try:
            r3 = s.get(c, timeout=120, allow_redirects=True)
            print(f"[apkmirror] cand status={r3.status_code} url={r3.url} len={len(r3.content)}")
            if ".apk" in r3.url and r3.status_code == 200:
                total = save_stream(r3)
                print(f"[apkmirror] saved bytes={total}")
                if total > 1000000:
                    return total
            elif len(r3.content) > 500000:
                with open(OUT, "wb") as f:
                    f.write(r3.content)
                print(f"[apkmirror] direct binary bytes={len(r3.content)}")
                return len(r3.content)
            else:
                # maybe page with key link
                m2 = re.search(r'download\.php\?id=(\d+)&key=([0-9a-f]+)', r3.text)
                if m2:
                    final = base + f"/wp-content/themes/APKMirror/download.php?id={m2.group(1)}&key={m2.group(2)}"
                    print(f"[apkmirror] keyed url: {final[:200]}")
                    r4 = s.get(final, timeout=300, stream=True)
                    total = save_stream(r4)
                    print(f"[apkmirror] saved bytes={total}")
                    if total > 1000000:
                        return total
        except Exception as e:
            print(f"[apkmirror] candidate error: {e}")
    return None


def uptodown():
    base = "https://android-system-webview.en.uptodown.com"
    s = requests.Session()
    s.headers.update(HEADERS)
    page = base + "/android/download"
    try:
        r = s.get(page, timeout=90)
        print(f"[uptodown] status={r.status_code} len={len(r.text)}")
        # Uptodown download URL is a JS-driven POST; find data-url or /post/download
        m = re.search(r'data-url="([^"]*)"', r.text)
        if not m:
            m = re.search(r'(/post/download/\d+)', r.text)
        if not m:
            m = re.search(r'(https?://[^"\']+\.apk[^"\']*)', r.text)
        if not m:
            print("[uptodown] no download url")
            return None
        url = m.group(1)
        if url.startswith("/"):
            url = base + url
        print(f"[uptodown] download url: {url[:200]}")
        r2 = s.get(url, timeout=300, stream=True)
        total = save_stream(r2)
        print(f"[uptodown] saved bytes={total}")
        return total if total > 1000000 else None
    except Exception as e:
        print(f"[uptodown] error: {e}")
        return None


def apkpure():
    vc = "469209800"
    s = requests.Session()
    s.headers.update(HEADERS)
    s.headers["Referer"] = "https://apkpure.com/android-system-webview/com.google.android.webview/download/97.0.4692.98"
    url = f"https://d.apkpure.com/b/APK/com.google.android.webview?versionCode={vc}"
    try:
        r = s.get(url, timeout=300, stream=True, allow_redirects=True)
        print(f"[apkpure] status={r.status_code} final={r.url}")
        if r.status_code != 200:
            return None
        total = save_stream(r)
        print(f"[apkpure] saved bytes={total}")
        return total if total > 1000000 else None
    except Exception as e:
        print(f"[apkpure] error: {e}")
        return None


def main():
    for fn in (apkmirror, uptodown, apkpure):
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
