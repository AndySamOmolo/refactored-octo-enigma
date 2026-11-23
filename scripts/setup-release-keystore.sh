#!/usr/bin/env bash
# EdgeLedger Persistent Release Keystore Generator
# Run this ONCE on your machine to generate a permanent signing key so every
# GitHub Actions APK release installs cleanly IN-PLACE (updating your app
# without uninstalling or losing Room bets/history/settings).
set -euo pipefail

KEYSTORE_FILE="${1:-edgeledger-release.jks}"
KEY_ALIAS="${2:-edgeledger}"

if [ -f "$KEYSTORE_FILE" ]; then
  echo "Error: $KEYSTORE_FILE already exists. Refusing to overwrite an existing signing key!"
  echo "If you want to re-export its GitHub Secrets, run:"
  echo "  base64 -w 0 \"$KEYSTORE_FILE\"   # (macOS: base64 -i \"$KEYSTORE_FILE\" | tr -d '\\n')"
  exit 1
fi

read -rsp "Enter new Keystore Password (min 8 chars): " STORE_PASS
echo
read -rsp "Confirm Keystore Password: " STORE_PASS_CONFIRM
echo
if [ "$STORE_PASS" != "$STORE_PASS_CONFIRM" ] || [ ${#STORE_PASS} -lt 8 ]; then
  echo "Error: Passwords must match and be at least 8 characters."
  exit 1
fi

read -rsp "Enter Key Password (press Enter to reuse Keystore Password): " KEY_PASS
echo
if [ -z "$KEY_PASS" ]; then
  KEY_PASS="$STORE_PASS"
fi

keytool -genkeypair \
  -v \
  -storetype PKCS12 \
  -keystore "$KEYSTORE_FILE" \
  -alias "$KEY_ALIAS" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass "$STORE_PASS" \
  -keypass "$KEY_PASS" \
  -dname "CN=EdgeLedger, OU=Mobile, O=EdgeLedger, L=Nairobi, ST=Nairobi, C=KE"

echo
echo "=== Signing Certificate SHA-256 Fingerprint (save this for verification) ==="
keytool -list -v -keystore "$KEYSTORE_FILE" -alias "$KEY_ALIAS" -storepass "$STORE_PASS" | grep -E "SHA256:|Alias name:" || true

if base64 --help 2>&1 | grep -q -- "-w"; then
  KEYSTORE_B64="$(base64 -w 0 "$KEYSTORE_FILE")"
else
  KEYSTORE_B64="$(base64 -i "$KEYSTORE_FILE" | tr -d '\n')"
fi

echo
echo "=========================================================================="
echo "Keystore created: $KEYSTORE_FILE (KEEP THIS FILE PRIVATE — NEVER COMMIT IT!)"
echo "Add these 4 repository secrets in GitHub -> Settings -> Secrets -> Actions:"
echo "=========================================================================="
echo "1. ANDROID_KEYSTORE_BASE64   = <paste from clipboard / file below>"
echo "2. ANDROID_KEYSTORE_PASSWORD = <your keystore password>"
echo "3. ANDROID_KEY_ALIAS         = $KEY_ALIAS"
echo "4. ANDROID_KEY_PASSWORD      = <your key password>"
echo
echo "Or, if you have GitHub CLI (gh) installed, run:"
echo "  gh secret set ANDROID_KEYSTORE_BASE64   --body \"$KEYSTORE_B64\""
echo "  gh secret set ANDROID_KEYSTORE_PASSWORD --body '<your-keystore-password>'"
echo "  gh secret set ANDROID_KEY_ALIAS         --body '$KEY_ALIAS'"
echo "  gh secret set ANDROID_KEY_PASSWORD      --body '<your-key-password>'"
echo
echo "Note: Your very first install signed with this new key requires one final"
echo "reinstall over any old debug-signed build. Every update after that will"
echo "install directly IN-PLACE without losing any bets, history, or settings."
