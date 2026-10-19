#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Download Android System WebView 97.0.4692.98 (armeabi-v7a / 32-bit) APK.
APKMirror via Playwright headless browser (bypass JS redirect).
"""
import sys
import time
from playwright.sync_api import sync_playwright

OUT = "webview97-armeabi-v7a.apk"
VERSION_PAGE = ("https://www.apkmirror.com/apk/google-inc/android-system-webview/"
                "android-system-webview-97-0-4692-98-release/")


def main():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True, args=["--no-sandbox"])
        ctx = browser.new_context(
            user_agent=("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"),
            locale="en-US",
        )
        page = ctx.new_page()

        # 1. version page
        print("[pw] goto version page")
        page.goto(VERSION_PAGE, wait_until="domcontentloaded", timeout=90000)
        page.wait_for_timeout(3000)

        # 2. find the variant row: APK + armeabi-v7a + not Android 10
        rows = page.query_selector_all("div.table-row")
        print(f"[pw] rows: {len(rows)}")
        target = None
        for row in rows:
            txt = row.inner_text()
            if ("APK" in txt) and ("armeabi-v7a" in txt) and ("Android 10" not in txt):
                link = row.query_selector('a[href*="apk-download"]')
                if link:
                    target = link
                    print(f"[pw] selected row: {txt[:100]!r}")
                    break
        if not target:
            print("[pw] no row")
            return 1

        # 3. go to download page
        with page.expect_navigation(wait_until="domcontentloaded", timeout=90000):
            target.click()
        print("[pw] on download page:", page.url)
        page.wait_for_timeout(3000)

        # 4. click the real downloadButton (href contains /download/?key=)
        btn = None
        for a in page.query_selector_all("a.downloadButton, a[href*='/download/?key=']"):
            href = a.get_attribute("href") or ""
            if "/download/?key=" in href:
                btn = a
                print(f"[pw] found keyed button: {href[:120]}")
                break
        if not btn:
            # dump all downloadButton hrefs
            for a in page.query_selector_all("a.downloadButton"):
                print("[pw] dlbtn:", (a.get_attribute("href") or "")[:150])
            print("[pw] NO keyed button")
            return 1

        # 5. click -> expect download
        try:
            with page.expect_download(timeout=120000) as dl_info:
                btn.click()
            download = dl_info.value
            print("[pw] download suggested:", download.suggested_filename)
            download.save_as(OUT)
            print("[pw] saved:", OUT)
            return 0
        except Exception as e:
            print(f"[pw] expect_download failed: {e}")
            # fallback: look for .apk response
            print("[pw] trying direct navigation capture")
            try:
                with page.expect_download(timeout=60000) as dl_info2:
                    page.evaluate("() => { window.location.href = document.querySelector('a[href*=\"/download/?key=\"]').href; }")
                download2 = dl_info2.value
                download2.save_as(OUT)
                print("[pw] saved via fallback:", OUT)
                return 0
            except Exception as e2:
                print(f"[pw] fallback failed: {e2}")
                return 1


if __name__ == "__main__":
    sys.exit(main())
