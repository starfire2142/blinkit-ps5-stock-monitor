import sys
import time
import random
from datetime import datetime, timezone, timedelta

from playwright.sync_api import sync_playwright

import config
import notifier

IST = timezone(timedelta(hours=5, minutes=30))


def check_stock_for_pincode(browser, pincode: str) -> dict:
    """Check product stock by navigating to the product page with location set."""
    coords = config.PINCODE_COORDS.get(pincode)
    if not coords:
        return {"pincode": pincode, "in_stock": False, "inventory": 0, "error": f"No coordinates for pincode {pincode}"}

    try:
        context = browser.new_context(
            viewport={"width": random.randint(1280, 1440), "height": random.randint(800, 900)},
            user_agent=(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                "AppleWebKit/537.36 (KHTML, like Gecko) "
                "Chrome/131.0.0.0 Safari/537.36"
            ),
            locale="en-IN",
            timezone_id="Asia/Kolkata",
            geolocation={"latitude": float(coords["lat"]), "longitude": float(coords["lon"])},
            permissions=["geolocation"],
        )

        page = context.new_page()

        # Inject lat/lon into all Blinkit API requests made by the page
        def handle_route(route):
            headers = route.request.headers.copy()
            headers["lat"] = coords["lat"]
            headers["lon"] = coords["lon"]
            route.continue_(headers=headers)

        page.route("**/v1/**", handle_route)
        page.route("**/v2/**", handle_route)

        page.goto(config.PRODUCT_URL, wait_until="networkidle", timeout=30000)
        time.sleep(2)

        content = page.content().lower()

        # Determine stock status from page content
        out_of_stock_indicators = ["out of stock", "sold out", "currently unavailable", "notify me when available"]
        in_stock_indicators = ["add to cart", "add to basket", "buy now"]

        is_out_of_stock = any(indicator in content for indicator in out_of_stock_indicators)

        # The "ADD" button for similar products shouldn't count - check specifically for main product
        # Look for the main product's add button vs out of stock text
        is_in_stock = False
        if not is_out_of_stock:
            is_in_stock = any(indicator in content for indicator in in_stock_indicators)

        # Also try to check via intercepted API response
        # The page title confirms we're on the right product
        title = page.title()
        if "playstation" not in title.lower() and "ps5" not in title.lower():
            context.close()
            return {"pincode": pincode, "in_stock": False, "inventory": 0, "error": "Wrong page loaded"}

        context.close()

        if is_out_of_stock:
            return {"pincode": pincode, "in_stock": False, "inventory": 0, "error": None}
        elif is_in_stock:
            return {"pincode": pincode, "in_stock": True, "inventory": 1, "error": None}
        else:
            return {"pincode": pincode, "in_stock": False, "inventory": 0, "error": "Could not determine stock status"}

    except Exception as e:
        try:
            context.close()
        except:
            pass
        return {"pincode": pincode, "in_stock": False, "inventory": 0, "error": str(e)}


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
    failed_errors = []

    with sync_playwright() as p:
        browser = p.chromium.launch(
            headless=True,
            args=["--no-sandbox", "--disable-setuid-sandbox", "--disable-dev-shm-usage"],
        )

        for pincode in config.PINCODES:
            pincode = pincode.strip()
            result = check_stock_for_pincode(browser, pincode)

            if result["error"]:
                print(f"  [{pincode}] ERROR: {result['error']}")
                failed_pincodes.append(pincode)
                failed_errors.append(result["error"])
            elif result["in_stock"]:
                print(f"  [{pincode}] IN STOCK!")
                available_pincodes.append(pincode)
            else:
                print(f"  [{pincode}] Out of stock")

            time.sleep(random.uniform(1.0, 3.0))

        browser.close()

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
