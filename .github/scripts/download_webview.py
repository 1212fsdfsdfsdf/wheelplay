#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Download Android System WebView 97.0.4692.98 (armeabi-v7a / 32-bit) APK.
APKMirror two-step download via Playwright: click downloadButton -> wait navigation
to /download/?key= page -> extract real CDN apk link -> download it.
"""
import re
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
        try:
            page.keyboard.press("Escape")
            page.wait_for_timeout(1000)
        except Exception:
            pass

        btn = None
        try:
            page.wait_for_selector('a[href*="/download/?key="]', timeout=30000)
            btn = page.query_selector('a[href*="/download/?key="]')
            print("[pw] keyed button found")
        except Exception as e:
            print(f"[pw] keyed button not found: {e}")
            return 1

        # Collect all network responses while clicking (catches .apk / CDN redirects)
        seen = []
        page.on("response", lambda resp: seen.append(resp.url))

        # click -> should navigate to /download/?key= intermediate page
        print("[pw] clicking keyed button...")
        try:
            with page.expect_navigation(wait_until="domcontentloaded", timeout=60000):
                try:
                    btn.click()
                except Exception:
                    btn.click(force=True)
        except Exception as e:
            print(f"[pw] nav wait failed (maybe no nav): {e}")

        page.wait_for_timeout(5000)
        print("[pw] after click url:", page.url)

        # dump every link on the intermediate page
        apk_links = []
        for a in page.query_selector_all("a[href]"):
            href = a.get_attribute("href") or ""
            if ".apk" in href or "downloadr" in href or "dw.uptodown" in href or "download" in href:
                apk_links.append(href)
                print("[pw] cand link:", href[:200])
        # meta refresh / iframe / scripts
        for m in re.finditer(r'(https?://[^\s"\']*\.apk[^\s"\']*)', page.content()):
            apk_links.append(m.group(1))
        print("[pw] total apk-ish links:", len(apk_links))
        # network seen
        for u in seen:
            if ".apk" in u or "downloadr" in u:
                print("[pw] seen resp:", u[:200])

        # try each candidate
        for u in apk_links:
            if not u.startswith("http"):
                u = BASE + u if u.startswith("/") else u
            try:
                resp = ctx.request.get(u, timeout=120000)
                print(f"[pw] fetch {u[:120]} -> {resp.status} len={len(resp.body())}")
                if len(resp.body()) > 1000000:
                    with open(OUT, "wb") as f:
                        f.write(resp.body())
                    print("[pw] saved", OUT)
                    return 0
            except Exception as e:
                print(f"[pw] fetch err {u[:80]}: {e}")

        # fallback: if page is the intermediate with a "start download" flow, click it
        for sel in ['a[href*=".apk"]', "#download-link", "a[download]", 'a[href*="downloadr2"]']:
            try:
                el = page.query_selector(sel)
                if el:
                    with page.expect_download(timeout=90000) as dl:
                        el.click(force=True)
                    dl.value.save_as(OUT)
                    print("[pw] saved via fallback click", OUT)
                    return 0
            except Exception:
                pass
        print("[pw] nothing worked")
        return 1


if __name__ == "__main__":
    sys.exit(main())
