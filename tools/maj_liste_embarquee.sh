#!/usr/bin/env bash
# Met à jour l'instantané de la liste embarquée dans l'APK (hagezi Multi PRO, format Wildcard
# Domains). L'application l'utilise au premier lancement, avant toute mise à jour en ligne.
# Licence de la liste : GPL-3.0 (https://github.com/hagezi/dns-blocklists).
# Extension « .gzip » et non « .gz » : la compilation Android traite à part les assets en .gz
# (aapt les décompresse et retire l'extension) ; sous ce nom, l'application ne trouvait plus
# le fichier dans l'APK (constaté sur émulateur).
set -euo pipefail
cd "$(dirname "$0")/.."
URL="https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro-onlydomains.txt"
DEST="app/src/main/assets/blocklists/hagezi-pro.txt.gzip"
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
curl -fsSL --retry 3 -o "$TMP" "$URL"
ENTREES=$(grep -cvE '^\s*(#|$)' "$TMP")
if [ "$ENTREES" -lt 50000 ]; then
  echo "Liste anormalement courte ($ENTREES entrées) : abandon." >&2
  exit 1
fi
gzip -9 -n -c "$TMP" > "$DEST"
echo "$DEST : $ENTREES domaines, $(wc -c < "$DEST") octets compressés"
grep -m3 -E '^# (Version|Last modified|Number of entries)' "$TMP"
