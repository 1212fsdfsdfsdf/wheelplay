#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Download Android System WebView 97.0.4692.98 (armeabi-v7a / 32-bit) APK.
APKMirror: goto keyed intermediate URL directly; page JS auto-triggers CDN download.
"""
import sys
from playwright.sync_api import sync_playwright

OUT = "webview97-armeabi-v7a.apk"
BASE = "https://www.apkmirror.com"
VERSION_PAGE = (BASE + "/apk/google-inc/android-system-webview/"
                "android-system-webview-97-0-4692-98-release/")


def main():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True, args=["--no-sandbox", "--disable-dev-shm-usage"])
        ctx = browser.new_context(
            user_agent=("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"),
            locale="en-US",
        )
        page = ctx.new_page()

        print("[pw] goto version page")
        page.goto(VERSION_PAGE, wait_until="domcontentloaded", timeout=90000)
        page.wait_for_timeout(3000)

        rows = page.query_selector_all("div.table-row")
        print(f"[pw] rows: {len(rows)}")
        dl_page = None
        for row in rows:
            txt = row.inner_text()
            if ("APK" in txt) and ("armeabi-v7a" in txt) and ("Android 10" not in txt):
                link = row.query_selector('a[href*="apk-download"]')
                if link:
                    href = link.get_attribute("href")
                    dl_page = BASE + href if href.startswith("/") else href
                    print(f"[pw] dl_page: {dl_page}")
                    break
        if not dl_page:
            print("[pw] no row")
            return 1

        page.goto(dl_page, wait_until="domcontentloaded", timeout=90000)
        print("[pw] on:", page.url)
        page.wait_for_timeout(3000)

        btn = None
        try:
            page.wait_for_selector('a[href*="/download/?key="]', timeout=30000)
            btn = page.query_selector('a[href*="/download/?key="]')
        except Exception as e:
            print(f"[pw] keyed button not found: {e}")
            return 1
        keyed = btn.get_attribute("href")
        keyed_url = BASE + keyed if keyed.startswith("/") else keyed
        print("[pw] keyed:", keyed_url[:160])

        # monitor apk responses
        apk_resps = []
        page.on("response", lambda r: apk_resps.append(r.url) if (".apk" in r.url or "downloadr" in r.url) else None)

        # goto intermediate page; JS should start the real download
        print("[pw] goto keyed page...")
        try:
            with page.expect_download(timeout=90000) as dl_info:
                try:
                    page.goto(keyed_url, wait_until="domcontentloaded", timeout=90000)
                except Exception:
                    pass
                page.wait_for_timeout(30000)  # let JS work
            download = dl_info.value
            print("[pw] download event:", download.suggested_filename)
            download.save_as(OUT)
            print("[pw] saved", OUT)
            return 0
        except Exception as e:
            print(f"[pw] no download event: {e}")

        # fallback: look at apk responses
        print("[pw] apk responses seen:", len(apk_resps))
        for u in apk_resps:
            print("[pw]   resp:", u[:200])
            try:
                resp = ctx.request.get(u, timeout=300000)
                body = resp.body()
                print(f"[pw]   fetch len={len(body)}")
                if len(body) > 5000000:
                    with open(OUT, "wb") as f:
                        f.write(body)
                    print("[pw] saved", OUT)
                    return 0
            except Exception as e2:
                print(f"[pw]   fetch err: {e2}")

        # fallback 2: current page content may embed final link
        content = page.content()
        import re
        for m in re.finditer(r'(https?://[^\s"\']*\.apk[^\s"\']*)', content):
            u = m.group(1)
            print("[pw] page apk link:", u[:200])
            try:
                resp = ctx.request.get(u, timeout=300000)
                body = resp.body()
                print(f"[pw]   fetch len={len(body)}")
                if len(body) > 5000000:
                    with open(OUT, "wb") as f:
                        f.write(body)
                    print("[pw] saved", OUT)
                    return 0
            except Exception as e3:
                print(f"[pw]   err: {e3}")
        print("[pw] all failed")
        return 1


if __name__ == "__main__":
    sys.exit(main())
