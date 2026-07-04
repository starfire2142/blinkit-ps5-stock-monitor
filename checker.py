import sys
import uuid
import time
import random
from datetime import datetime

from curl_cffi import requests as cffi_requests
import requests

import config
import notifier


def check_stock_for_pincode(session: cffi_requests.Session, pincode: str) -> dict:
    """Check product stock for a given pincode via Blinkit's internal API.
    Uses curl_cffi to impersonate Chrome TLS fingerprint (bypasses Cloudflare).
    Returns a dict with keys: pincode, in_stock, inventory, error
    """
    coords = config.PINCODE_COORDS.get(pincode)
    if not coords:
        return {"pincode": pincode, "in_stock": False, "inventory": 0, "error": f"No coordinates for pincode {pincode}"}

    url = config.BLINKIT_API_URL.format(product_id=config.PRODUCT_ID)

    headers = {
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/131.0.0.0 Safari/537.36"
        ),
        "Accept": "application/json, text/plain, */*",
        "Accept-Language": "en-IN,en;q=0.9",
        "Origin": "https://blinkit.com",
        "Referer": f"https://blinkit.com/prn/x/prid/{config.PRODUCT_ID}",
        "lat": coords["lat"],
        "lon": coords["lon"],
        "app_client": "consumer_web",
        "device_id": str(uuid.uuid4()),
        "session_uuid": str(uuid.uuid4()),
    }

    try:
        response = session.post(url, headers=headers, timeout=15)
        response.raise_for_status()
        data = response.json()

        if not data.get("is_success"):
            return {"pincode": pincode, "in_stock": False, "inventory": 0, "error": "API returned is_success=false"}

        snippets = data.get("response", {}).get("snippets", [])

        inventory = 0
        is_sold_out = True

        for snippet in snippets:
            snippet_data = snippet.get("data", {})
            if "is_sold_out" in snippet_data:
                is_sold_out = snippet_data["is_sold_out"]
                inventory = snippet_data.get("inventory", 0)
                break

        # Also check the page-level sticky footer as backup
        if is_sold_out:
            sticky = data.get("response", {}).get("page_level_components", {}).get("sticky", {})
            footer_models = sticky.get("footer_snippet_models", [])
            for model in footer_models:
                snippet_data = model.get("snippet", {}).get("data", {})
                if "is_sold_out" in snippet_data:
                    is_sold_out = snippet_data["is_sold_out"]
                    inventory = snippet_data.get("inventory", 0)
                    break

        in_stock = not is_sold_out and inventory > 0

        return {
            "pincode": pincode,
            "in_stock": in_stock,
            "inventory": inventory,
            "is_sold_out": is_sold_out,
            "error": None,
        }

    except Exception as e:
        return {"pincode": pincode, "in_stock": False, "inventory": 0, "error": str(e)}


def run_stock_check():
    """Run the full stock check across all configured pincodes."""
    print(f"\n{'='*60}")
    print(f"Blinkit Stock Check - {datetime.now().strftime('%Y-%m-%d %H:%M:%S IST')}")
    print(f"Product: {config.PRODUCT_NAME}")
    print(f"Pincodes: {', '.join(config.PINCODES)}")
    print(f"{'='*60}\n")

    session = cffi_requests.Session(impersonate="chrome")
    available_pincodes = []
    failed_pincodes = []

    for pincode in config.PINCODES:
        pincode = pincode.strip()
        result = check_stock_for_pincode(session, pincode)

        if result["error"]:
            print(f"  [{pincode}] ERROR: {result['error']}")
            failed_pincodes.append(pincode)
        elif result["in_stock"]:
            print(f"  [{pincode}] IN STOCK! (inventory: {result['inventory']})")
            available_pincodes.append(pincode)
        else:
            print(f"  [{pincode}] Out of stock (inventory: {result['inventory']})")

        time.sleep(random.uniform(0.5, 1.5))

    # Send notifications
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
