import json
import os
import time
from datetime import datetime

import requests

import config

COOLDOWN_FILE = os.environ.get(
    "COOLDOWN_FILE",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "cooldown_state.json"),
)


def _load_cooldown_state() -> dict:
    if os.path.exists(COOLDOWN_FILE):
        try:
            with open(COOLDOWN_FILE, "r") as f:
                return json.load(f)
        except (json.JSONDecodeError, IOError):
            return {}
    return {}


def _save_cooldown_state(state: dict) -> None:
    try:
        with open(COOLDOWN_FILE, "w") as f:
            json.dump(state, f)
    except IOError:
        pass


def _is_on_cooldown(pincode: str) -> bool:
    state = _load_cooldown_state()
    last_notified = state.get(pincode, 0)
    elapsed_minutes = (time.time() - last_notified) / 60
    return elapsed_minutes < config.NOTIFICATION_COOLDOWN_MINUTES


def _mark_notified(pincodes: list[str]) -> None:
    state = _load_cooldown_state()
    now = time.time()
    for pincode in pincodes:
        state[pincode] = now
    _save_cooldown_state(state)


def send_stock_alert(available_pincodes: list[str]) -> bool:
    """Send a Telegram alert for pincodes where stock is available.
    Returns True if a message was sent, False if skipped due to cooldown.
    """
    pincodes_to_notify = [
        p for p in available_pincodes if not _is_on_cooldown(p)
    ]

    if not pincodes_to_notify:
        print(f"[INFO] All pincodes on cooldown, skipping notification.")
        return False

    pincode_list = ", ".join(pincodes_to_notify)
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S IST")

    message = (
        f"🎮 *PS5 IN STOCK!*\n\n"
        f"*Product:* {config.PRODUCT_NAME}\n"
        f"*Available in pincode(s):* {pincode_list}\n"
        f"*Time:* {timestamp}\n\n"
        f"🔗 [Buy Now]({config.PRODUCT_URL})"
    )

    success = _send_telegram_message(message)
    if success:
        _mark_notified(pincodes_to_notify)
    return success


def send_error_alert(error_message: str) -> None:
    """Send a Telegram alert when the checker encounters persistent errors."""
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S IST")
    message = (
        f"⚠️ *Blinkit Stock Checker Error*\n\n"
        f"*Error:* {error_message}\n"
        f"*Time:* {timestamp}\n\n"
        f"The checker may need attention."
    )
    _send_telegram_message(message)


def _send_telegram_message(message: str) -> bool:
    if not config.TELEGRAM_BOT_TOKEN or not config.TELEGRAM_CHAT_ID:
        print("[ERROR] Telegram credentials not configured.")
        return False

    url = f"https://api.telegram.org/bot{config.TELEGRAM_BOT_TOKEN}/sendMessage"
    payload = {
        "chat_id": config.TELEGRAM_CHAT_ID,
        "text": message,
        "parse_mode": "Markdown",
        "disable_web_page_preview": False,
    }

    try:
        response = requests.post(url, json=payload, timeout=10)
        if response.status_code == 200:
            print(f"[INFO] Telegram notification sent successfully.")
            return True
        else:
            print(
                f"[ERROR] Telegram API returned {response.status_code}: "
                f"{response.text}"
            )
            return False
    except requests.RequestException as e:
        print(f"[ERROR] Failed to send Telegram message: {e}")
        return False
