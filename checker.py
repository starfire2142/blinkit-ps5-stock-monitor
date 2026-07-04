import sys
import uuid
import time
import random
import os
from datetime import datetime, timezone, timedelta

import requests

import config
import notifier

IST = timezone(timedelta(hours=5, minutes=30))

PROXY_LIST_URLS = [
    "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/countries/IN/data.json",
]


def _find_indian_proxy() -> str | None:
    """Fetch a working Indian proxy from public proxy lists."""
    for list_url in PROXY_LIST_URLS:
        try:
            resp = requests.get(list_url, timeout=10)
            if resp.status_code != 200:
                continue
            proxies = resp.json()
            # Try up to 5 proxies
            random.shuffle(proxies)
            for proxy_data in proxies[:5]:
                protocol = proxy_data.get("protocol", "http")
                ip = proxy_data.get("ip", "")
                port = proxy_data.get("port", "")
                if not ip or not port:
                    continue
                proxy_url = f"{protocol}://{ip}:{port}"
                # Test proxy with a quick request
                try:
                    test_resp = requests.get(
                        "https://blinkit.com",
                        proxies={"http": proxy_url, "https": proxy_url},
                        timeout=8,
                    )
                    if test_resp.status_code == 200:
                        return proxy_url
                except Exception:
                    continue
        except Exception:
            continue
    return None


def check_stock_for_pincode(session: requests.Session, pincode: str) -> dict:
    """Check product stock for a given pincode via Blinkit's internal API."""
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
        if response.status_code == 403:
            return {"pincode": pincode, "in_stock": False, "inventory": 0, "error": "HTTP 403 - blocked by Cloudflare (non-Indian IP)"}
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

        if is_sold_out:
            sticky = data.get("response", {}).get("page_level_components", {}).get("sticky", {})
            for model in sticky.get("footer_snippet_models", []):
                snippet_data = model.get("snippet", {}).get("data", {})
                if "is_sold_out" in snippet_data:
                    is_sold_out = snippet_data["is_sold_out"]
                    inventory = snippet_data.get("inventory", 0)
                    break

        in_stock = not is_sold_out and inventory > 0
        return {"pincode": pincode, "in_stock": in_stock, "inventory": inventory, "error": None}

    except requests.RequestException as e:
        return {"pincode": pincode, "in_stock": False, "inventory": 0, "error": str(e)}
    except (KeyError, ValueError, TypeError) as e:
        return {"pincode": pincode, "in_stock": False, "inventory": 0, "error": f"Parse error: {e}"}


def run_stock_check():
    """Run the full stock check across all configured pincodes."""
    now = datetime.now(IST).strftime("%Y-%m-%d %H:%M:%S IST")
    print(f"\n{'='*60}")
    print(f"Blinkit Stock Check - {now}")
    print(f"Product: {config.PRODUCT_NAME}")
    print(f"Pincodes: {', '.join(config.PINCODES)}")
    print(f"{'='*60}\n")

    # Configure proxy if available (needed for non-Indian IPs)
    proxy_url = os.environ.get("PROXY_URL", "")
    proxies = {"http": proxy_url, "https": proxy_url} if proxy_url else None

    session = requests.Session()
    if proxies:
        session.proxies.update(proxies)
        print(f"  Using proxy: {proxy_url[:30]}...\n")
    elif os.environ.get("USE_AUTO_PROXY"):
        indian_proxy = _find_indian_proxy()
        if indian_proxy:
            session.proxies.update({"http": indian_proxy, "https": indian_proxy})
            print(f"  Using auto-detected Indian proxy: {indian_proxy}\n")
        else:
            print("  WARNING: No working Indian proxy found, trying direct...\n")

    available_pincodes = []
    failed_pincodes = []
    failed_errors = []

    for pincode in config.PINCODES:
        pincode = pincode.strip()
        result = check_stock_for_pincode(session, pincode)

        if result["error"]:
            print(f"  [{pincode}] ERROR: {result['error']}")
            failed_pincodes.append(pincode)
            failed_errors.append(result["error"])
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
        errors = "; ".join(f"{p}: {e}" for p, e in zip(failed_pincodes, failed_errors))
        error_msg = f"All pincode checks failed.\n\nDetails: {errors}"
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
