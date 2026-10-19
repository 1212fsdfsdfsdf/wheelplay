#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Download Android System WebView 97.0.4692.98 (armeabi-v7a / 32-bit) APK.
Tries Uptodown -> APKMirror. Runs in GitHub Actions.
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


def uptodown():
    s = requests.Session()
    s.headers.update(HEADERS)
    # App page: find the version entry page for 97.0.4692.98
    app_url = "https://android-system-webview.en.uptodown.com/android"
    r = s.get(app_url, timeout=90)
    print(f"[uptodown] app page status={r.status_code} len={len(r.text)}")
    # Uptodown lists versions; the download button on version page is data-url
    # Version page pattern: .../android/versions or .../android/post/xxx
    # Look for "97.0.4692.98" link
    m = re.search(r'href="(/android/[^"]*)"[^>]*>[^<]*97\.0\.4692\.98', r.text)
    if not m:
        m = re.search(r'(https?://[^"\']*/post/\d+)', r.text)
    if m:
        ver_url = m.group(1)
        if ver_url.startswith("/"):
            ver_url = "https://android-system-webview.en.uptodown.com" + ver_url
        print(f"[uptodown] version page: {ver_url}")
        r2 = s.get(ver_url, timeout=90)
        print(f"[uptodown] version page status={r2.status_code} len={len(r2.text)}")
        # Find download data-url
        m2 = re.search(r'data-url="([^"]*)"', r2.text)
        if m2:
            dl = m2.group(1)
            if dl.startswith("/"):
                dl = "https://android-system-webview.en.uptodown.com" + dl
            print(f"[uptodown] data-url: {dl}")
            r3 = s.get(dl, timeout=120, allow_redirects=True)
            print(f"[uptodown] dl status={r3.status_code} final={r3.url} len={len(r3.content)}")
            if ".apk" in r3.url or len(r3.content) > 500000:
                with open(OUT, "wb") as f:
                    f.write(r3.content)
                print(f"[uptodown] saved bytes={len(r3.content)}")
                return len(r3.content) if len(r3.content) > 1000000 else None
        # fallback: direct dw.uptodown.com link
        m3 = re.search(r'(https?://dw\.uptodown\.com/[^"\']+\.apk[^"\']*)', r2.text)
        if m3:
            url = m3.group(1)
            print(f"[uptodown] direct: {url[:150]}")
            r4 = s.get(url, timeout=300, stream=True)
            total = save_stream(r4)
            print(f"[uptodown] saved bytes={total}")
            return total if total > 1000000 else None
    else:
        print("[uptodown] no version link found; dumping download-related")
        for mm in re.finditer(r'[^"\']*download[^"\']*', r.text[:200000]):
            print("  ", mm.group(0)[:120])
    return None


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
            # dump the row's outer html around the anchor to find data attrs
            raw = str(a.parent.parent) if a.parent and a.parent.parent else str(row)
            for line in raw.split("\n"):
                if "download" in line.lower() or "data-" in line.lower():
                    print("[apkmirror] row line:", line.strip()[:300])
            break
    if not target:
        print("[apkmirror] no row")
        return None
    dl_page = base + target
    print(f"[apkmirror] dl page: {dl_page}")
    r2 = s.get(dl_page, timeout=120)
    print(f"[apkmirror] dl page status={r2.status_code} len={len(r2.text)}")
    # Print every context around 'download.php' occurrences
    hits = [mm.start() for mm in re.finditer(r'download\.php', r2.text)]
    print(f"[apkmirror] download.php occurrences: {len(hits)}")
    for h in hits[:10]:
        print("[apkmirror] ctx:", r2.text[max(0, h-200):h+200].replace("\n", " ")[:420])
    # Print every <a> with rel=nofollow or id containing download
    soup2 = BeautifulSoup(r2.text, "html.parser")
    for a in soup2.find_all("a", href=True):
        href = a.get("href", "")
        rel = " ".join(a.get("rel", []))
        aid = a.get("id", "")
        cls = " ".join(a.get("class", []))
        if ("download" in href.lower() or "download" in rel or "download" in aid
                or "download" in cls or "php" in href.lower()):
            print(f"[apkmirror] A href={href[:180]} rel={rel} id={aid} class={cls[:60]}")
    return None


def main():
    for fn in (uptodown, apkmirror):
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
