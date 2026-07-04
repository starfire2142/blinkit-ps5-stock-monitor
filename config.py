import os


TELEGRAM_BOT_TOKEN = os.environ.get("TELEGRAM_BOT_TOKEN", "")
TELEGRAM_CHAT_ID = os.environ.get("TELEGRAM_CHAT_ID", "")

PRODUCT_URL = os.environ.get(
    "PRODUCT_URL", "https://blinkit.com/prn/x/prid/763266"
)
PRODUCT_ID = os.environ.get("PRODUCT_ID", "763266")
PRODUCT_NAME = os.environ.get(
    "PRODUCT_NAME", "PlayStation 5 Gaming Console Standard Edition E Chassis (White)"
)

PINCODES = os.environ.get("PINCODES", "411040,411045,411060").split(",")

NOTIFICATION_COOLDOWN_MINUTES = int(
    os.environ.get("NOTIFICATION_COOLDOWN_MINUTES", "30")
)

PINCODE_COORDS = {
    "411040": {"lat": "18.5992", "lon": "73.927"},
    "411045": {"lat": "18.5599", "lon": "73.7793"},
    "411060": {"lat": "18.4765", "lon": "73.9098"},
}

BLINKIT_API_URL = "https://blinkit.com/v1/layout/product/{product_id}"
