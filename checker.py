import sys
import uuid
import time
import random
from datetime import datetime, timezone, timedelta

import requests

import config
import notifier

IST = timezone(timedelta(hours=5, minutes=30))


def _get_headers(pincode: str) -> dict:
    coords = config.PINCODE_COORDS.get(pincode, {})
    return {
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/131.0.0.0 Safari/537.36"
        ),
        "Accept": "application/json, text/plain, */*",
        "Accept-Language": "en-IN,en;q=0.9",
        "Origin": "https://blinkit.com",
        "Referer": f"https://blinkit.com/prn/x/prid/{config.PRODUCT_ID}",
        "lat": coords.get("lat", "18.5992"),
        "lon": coords.get("lon", "73.927"),
        "app_client": "consumer_web",
        "device_id": str(uuid.uuid4()),
        "session_uuid": str(uuid.uuid4()),
    }


def _parse_stock_response(data: dict) -> tuple[bool, int]:
    """Parse API response and return (in_stock, inventory)."""
    if not data.get("is_success"):
        return False, 0

    snippets = data.get("response", {}).get("snippets", [])

    for snippet in snippets:
        snippet_data = snippet.get("data", {})
        if "is_sold_out" in snippet_data:
            is_sold_out = snippet_data["is_sold_out"]
            inventory = snippet_data.get("inventory", 0)
            if not is_sold_out and inventory > 0:
                return True, inventory
            return False, inventory

    # Backup: check page-level sticky footer
    sticky = data.get("response", {}).get("page_level_components", {}).get("sticky", {})
    for model in sticky.get("footer_snippet_models", []):
        snippet_data = model.get("snippet", {}).get("data", {})
        if "is_sold_out" in snippet_data:
            is_sold_out = snippet_data["is_sold_out"]
            inventory = snippet_data.get("inventory", 0)
            if not is_sold_out and inventory > 0:
                return True, inventory
            return False, inventory

    return False, 0


def check_with_curl_cffi(pincode: str) -> dict:
    """Primary method: use curl_cffi for TLS fingerprint impersonation."""
    try:
        from curl_cffi import requests as cffi_requests
    except ImportError:
        return {"pincode": pincode, "in_stock": False, "inventory": 0, "error": "curl_cffi not installed"}

    coords = config.PINCODE_COORDS.get(pincode)
    if not coords:
        return {"pincode": pincode, "in_stock": False, "inventory": 0, "error": f"No coordinates for pincode {pincode}"}

    url = config.BLINKIT_API_URL.format(product_id=config.PRODUCT_ID)
    headers = _get_headers(pincode)

    try:
        session = cffi_requests.Session(impersonate="chrome")
        response = session.post(url, headers=headers, timeout=15)
        if response.status_code != 200:
            return {"pincode": pincode, "in_stock": False, "inventory": 0, "error": f"HTTP {response.status_code}"}

        data = response.json()
        in_stock, inventory = _parse_stock_response(data)
        return {"pincode": pincode, "in_stock": in_stock, "inventory": inventory, "error": None}
    except Exception as e:
        return {"pincode": pincode, "in_stock": False, "inventory": 0, "error": f"curl_cffi: {e}"}


def check_with_playwright(pincode: str) -> dict:
    """Fallback method: use Playwright's browser network stack for API call."""
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        return {"pincode": pincode, "in_stock": False, "inventory": 0, "error": "playwright not installed"}

    coords = config.PINCODE_COORDS.get(pincode)
    if not coords:
        return {"pincode": pincode, "in_stock": False, "inventory": 0, "error": f"No coordinates for pincode {pincode}"}

    url = config.BLINKIT_API_URL.format(product_id=config.PRODUCT_ID)
    headers = _get_headers(pincode)

    try:
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True, args=["--no-sandbox", "--disable-dev-shm-usage"])
            context = browser.new_context()
            api = context.request

            response = api.post(url, headers=headers)
            if response.status != 200:
                browser.close()
                return {"pincode": pincode, "in_stock": False, "inventory": 0, "error": f"HTTP {response.status}"}

            data = response.json()
            browser.close()

            in_stock, inventory = _parse_stock_response(data)
            return {"pincode": pincode, "in_stock": in_stock, "inventory": inventory, "error": None}
    except Exception as e:
        return {"pincode": pincode, "in_stock": False, "inventory": 0, "error": f"playwright: {e}"}


def check_stock_for_pincode(pincode: str) -> dict:
    """Check stock using curl_cffi first, falling back to Playwright if needed."""
    result = check_with_curl_cffi(pincode)
    if result["error"] is None:
        return result

    print(f"    curl_cffi failed ({result['error']}), trying Playwright...")
    return check_with_playwright(pincode)


def run_stock_check():
    """Run the full stock check across all configured pincodes."""
    now = datetime.now(IST).strftime("%Y-%m-%d %H:%M:%S IST")
    print(f"\n{'='*60}")
    print(f"Blinkit Stock Check - {now}")
    print(f"Product: {config.PRODUCT_NAME}")
    print(f"Pincodes: {', '.join(config.PINCODES)}")
    print(f"{'='*60}\n")

    available_pincodes = []
    failed_pincodes = []

    for pincode in config.PINCODES:
        pincode = pincode.strip()
        result = check_stock_for_pincode(pincode)

        if result["error"]:
            print(f"  [{pincode}] ERROR: {result['error']}")
            failed_pincodes.append(pincode)
        elif result["in_stock"]:
            print(f"  [{pincode}] IN STOCK! (inventory: {result['inventory']})")
            available_pincodes.append(pincode)
        else:
            print(f"  [{pincode}] Out of stock (inventory: {result['inventory']})")

        time.sleep(random.uniform(0.5, 1.5))

    if available_pincodes:
        print(f"\n*** ALERT: Stock available in: {', '.join(available_pincodes)} ***")
        notifier.send_stock_alert(available_pincodes)
    else:
        print(f"\nNo stock found in any pincode.")

    if len(failed_pincodes) == len(config.PINCODES):
        error_msg = "All pincode checks failed. Blinkit API may be blocking requests or has changed."
        print(f"\nERROR: {error_msg}")
        notifier.send_error_alert(error_msg)

    print(f"\nCheck complete. Exiting.")
    return len(available_pincodes) > 0


if __name__ == "__main__":
    if not config.TELEGRAM_BOT_TOKEN or not config.TELEGRAM_CHAT_ID:
        print("WARNING: Telegram credentials not set. Notifications will not be sent.")
        print("Set TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID environment variables.\n")

    try:
        run_stock_check()
    except Exception as e:
        print(f"FATAL: Unhandled exception: {e}")
        try:
            notifier.send_error_alert(f"Fatal error: {e}")
        except Exception:
            pass
        sys.exit(1)
