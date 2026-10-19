#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Download Android System WebView 97.0.4692.98 (armeabi-v7a / 32-bit) APK.
APKMirror via Playwright headless browser. Handles google_vignette overlay.
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

        # goto download page directly
        page.goto(dl_page, wait_until="domcontentloaded", timeout=90000)
        print("[pw] on:", page.url)
        page.wait_for_timeout(3000)

        # dismiss possible google vignette overlay
        try:
            page.keyboard.press("Escape")
            page.wait_for_timeout(1000)
        except Exception:
            pass

        # wait for the keyed download button
        btn = None
        try:
            page.wait_for_selector('a[href*="/download/?key="]', timeout=30000)
            btn = page.query_selector('a[href*="/download/?key="]')
            print("[pw] keyed button found")
        except Exception as e:
            print(f"[pw] keyed button not found: {e}")
            for a in page.query_selector_all("a.downloadButton"):
                print("[pw] dlbtn:", (a.get_attribute("href") or "")[:150])
            # maybe overlay is blocking; try close overlay
            for sel in [".vignette-close", ".fc-close", "button.close", "[aria-label*='close' i]",
                        "#close", ".closeBtn", ".btn-close", "a[href='#']"]:
                try:
                    el = page.query_selector(sel)
                    if el:
                        el.click()
                        print("[pw] clicked overlay close:", sel)
                        page.wait_for_timeout(1500)
                        break
                except Exception:
                    pass
            try:
                page.wait_for_selector('a[href*="/download/?key="]', timeout=15000)
                btn = page.query_selector('a[href*="/download/?key="]')
                print("[pw] keyed button found after overlay close")
            except Exception as e2:
                print(f"[pw] still no button: {e2}")
                return 1

        # click and capture download
        try:
            with page.expect_download(timeout=120000) as dl_info:
                try:
                    btn.click()
                except Exception:
                    btn.click(force=True)
            download = dl_info.value
            print("[pw] download:", download.suggested_filename)
            download.save_as(OUT)
            print("[pw] saved", OUT)
            return 0
        except Exception as e:
            print(f"[pw] expect_download failed: {e}")
            # fallback: intercept responses ending in .apk
            page2 = ctx.new_page()
            apk_url = []
            page2.on("response", lambda resp: apk_url.append(resp.url) if ".apk" in resp.url else None)
            try:
                page2.goto(dl_page, wait_until="domcontentloaded", timeout=90000)
                page2.wait_for_timeout(2000)
                b2 = page2.query_selector('a[href*="/download/?key="]')
                if b2:
                    with page2.expect_download(timeout=120000) as dl2:
                        try:
                            b2.click()
                        except Exception:
                            b2.click(force=True)
                    dl2.value.save_as(OUT)
                    print("[pw] saved via second page:", OUT)
                    return 0
            except Exception as e2:
                print(f"[pw] fallback failed: {e2}")
                print("[pw] apk urls seen:", apk_url[:5])
                return 1


if __name__ == "__main__":
    sys.exit(main())
