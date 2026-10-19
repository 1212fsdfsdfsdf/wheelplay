#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Download Android System WebView 97.0.4692.98 (armeabi-v7a / 32-bit) APK.
Tries APKMirror -> APKPure(with referer) -> APKCombo. Runs in GitHub Actions.
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

    # Debug: print all apk-download links with their row text
    all_rows = soup.select("div.table-row")
    print(f"[apkmirror] table rows: {len(all_rows)}")
    for row in all_rows:
        txt = row.get_text(" ", strip=True)
        a = row.select_one('a[href*="apk-download"]')
        if a:
            print(f"[apkmirror] ROW: abi-ish={txt[:80]!r} href={a.get('href')}")

    # Pick the row containing armeabi-v7a
    target = None
    for row in all_rows:
        txt = row.get_text(" ", strip=True)
        if "armeabi-v7a" in txt:
            a = row.select_one('a[href*="apk-download"]')
            if a:
                target = a.get("href")
                break
    if not target:
        # fallback: any row that mentions an abi at all
        for row in all_rows:
            txt = row.get_text(" ", strip=True)
            if re.search(r"armeabi-v7a|arm64-v8a|x86", txt):
                a = row.select_one('a[href*="apk-download"]')
                if a:
                    target = a.get("href")
                    break
    if not target:
        print("[apkmirror] no variant row found")
        return None
    dl_page = base + target
    print(f"[apkmirror] dl page: {dl_page}")
    r2 = s.get(dl_page, timeout=120)
    print(f"[apkmirror] dl page status={r2.status_code} len={len(r2.text)}")

    # download.php links
    m = re.search(r'href="([^"]*download\.php\?id=\d+[^"]*)"', r2.text)
    if not m:
        m = re.search(r'(https?://[^"\']*download\.php\?id=\d+[^"\']*)', r2.text)
    if not m:
        print("[apkmirror] no download.php link; dumping candidates")
        for mm in re.finditer(r'[^"\']*download\.php[^"\']*', r2.text):
            print("  cand:", mm.group(0)[:150])
        return None
    dl = m.group(1)
    if dl.startswith("/"):
        dl = base + dl
    print(f"[apkmirror] download.php: {dl}")
    r3 = s.get(dl, timeout=120, allow_redirects=True)
    print(f"[apkmirror] after download.php status={r3.status_code} url={r3.url} len={len(r3.content)}")

    final = None
    if ".apk" in r3.url and r3.status_code == 200:
        final = r3.url
    elif len(r3.content) > 500000:  # already the apk body?
        print("[apkmirror] download.php returned binary directly")
        with open(OUT, "wb") as f:
            f.write(r3.content)
        return len(r3.content)
    else:
        # page may contain key link or direct apk
        m2 = re.search(r'download\.php\?id=(\d+)&key=([0-9a-f]+)', r3.text)
        if m2:
            final = base + f"/wp-content/themes/APKMirror/download.php?id={m2.group(1)}&key={m2.group(2)}"
        else:
            m3 = re.search(r'(https?://[^"\']+\.apk[^"\']*)', r3.text)
            if m3 and "download.php" not in m3.group(1):
                final = m3.group(1)
            else:
                for mm in re.finditer(r'[^"\']*download\.php[^"\']*', r3.text):
                    print("  key cand:", mm.group(0)[:150])
    if not final:
        print("[apkmirror] cannot resolve final url")
        return None
    print(f"[apkmirror] final url: {final[:200]}")
    r4 = s.get(final, timeout=300, stream=True)
    total = 0
    with open(OUT, "wb") as f:
        for chunk in r4.iter_content(1024 * 256):
            if chunk:
                f.write(chunk)
                total += len(chunk)
    print(f"[apkmirror] saved bytes={total}")
    return total if total > 1000000 else None


def apkpure():
    version_codes = {"97.0.4692.98": "469209800"}
    vc = version_codes.get("97.0.4692.98")
    if not vc:
        return None
    s = requests.Session()
    s.headers.update(HEADERS)
    s.headers["Referer"] = "https://apkpure.com/android-system-webview/com.google.android.webview/download/97.0.4692.98"
    url = f"https://d.apkpure.com/b/APK/com.google.android.webview?versionCode={vc}"
    r = s.get(url, timeout=300, stream=True, allow_redirects=True)
    print(f"[apkpure] status={r.status_code} final={r.url} len={len(r.content) if r.status_code!=200 else 'stream'}")
    if r.status_code != 200 or len(r.content) < 1000000:
        print("[apkpure] failed/blocked")
        return None
    total = 0
    with open(OUT, "wb") as f:
        for chunk in r.iter_content(1024 * 256):
            if chunk:
                f.write(chunk)
                total += len(chunk)
    print(f"[apkpure] saved bytes={total}")
    return total if total > 1000000 else None


def apkcombo():
    for host in ("apkcombo.com", "apkcombo.net", "apkcombo.io"):
        try:
            base = f"https://{host}"
            page = base + "/android-system-webview/com.google.android.webview/download/apk"
            s = requests.Session()
            s.headers.update(HEADERS)
            r = s.get(page, timeout=60, allow_redirects=True)
            print(f"[apkcombo/{host}] status={r.status_code} len={len(r.text)} url={r.url}")
            m = re.search(r'(https?://[^"\']+\.apk[^"\']*)', r.text)
            if m:
                url = m.group(1)
                r2 = s.get(url, timeout=300, stream=True)
                total = 0
                with open(OUT, "wb") as f:
                    for chunk in r2.iter_content(1024 * 256):
                        if chunk:
                            f.write(chunk)
                            total += len(chunk)
                print(f"[apkcombo/{host}] saved bytes={total}")
                if total > 1000000:
                    return total
        except Exception as e:
            print(f"[apkcombo/{host}] error: {e}")
            continue
    return None


def main():
    for fn in (apkmirror, apkpure, apkcombo):
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
