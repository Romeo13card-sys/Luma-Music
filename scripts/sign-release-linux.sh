#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

usage() {
  cat <<'EOF'
Usage:
  scripts/sign-release-linux.sh --input-dir DIR --output-dir DIR [--key-dir DIR]

Environment overrides:
  APKSIGNER_JAR  Path to apksigner.jar
  ZIPALIGN       Path to zipalign (Linux or Windows .exe)
  ANDROID_HOME   Android SDK root (used for automatic discovery)
EOF
}

INPUT_DIR=""
OUTPUT_DIR=""
KEY_DIR="${HOME}/.luma-signing"

while (($#)); do
  case "$1" in
    --input-dir)  (($# >= 2)) || { usage >&2; exit 2; }; INPUT_DIR="$2"; shift 2 ;;
    --output-dir) (($# >= 2)) || { usage >&2; exit 2; }; OUTPUT_DIR="$2"; shift 2 ;;
    --key-dir)    (($# >= 2)) || { usage >&2; exit 2; }; KEY_DIR="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

[[ -n "$INPUT_DIR" && -n "$OUTPUT_DIR" ]] || { usage >&2; exit 2; }
command -v java >/dev/null || { echo "ERROR: Java is required." >&2; exit 1; }
command -v keytool >/dev/null || { echo "ERROR: keytool is required." >&2; exit 1; }
command -v sha256sum >/dev/null || { echo "ERROR: sha256sum is required." >&2; exit 1; }
command -v openssl >/dev/null || { echo "ERROR: openssl is required to generate the keystore password." >&2; exit 1; }

JAVA_VERSION="$(java -version 2>&1 | awk -F '"' '/version/ {print $2; exit}')"
JAVA_MAJOR="${JAVA_VERSION%%.*}"
[[ "$JAVA_MAJOR" == "21" ]] || { echo "ERROR: JDK 21 is required; found ${JAVA_VERSION:-unknown}." >&2; exit 1; }

INPUT_DIR="$(realpath "$INPUT_DIR")"
mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR="$(realpath "$OUTPUT_DIR")"
KEY_DIR="$(realpath -m "$KEY_DIR")"
[[ -d "$INPUT_DIR" ]] || { echo "ERROR: input directory does not exist: $INPUT_DIR" >&2; exit 1; }
[[ "$OUTPUT_DIR" != "$INPUT_DIR" && "$OUTPUT_DIR" != "$INPUT_DIR/"* ]] || {
  echo "ERROR: output directory must be outside the input directory." >&2; exit 1;
}
if find "$OUTPUT_DIR" -mindepth 1 -maxdepth 1 -print -quit | grep -q .; then
  echo "ERROR: output directory is not empty: $OUTPUT_DIR" >&2
  exit 1
fi

if [[ -z "${APKSIGNER_JAR:-}" ]]; then
  for candidate in \
    "${ANDROID_HOME:-}/build-tools/36.1.0/lib/apksigner.jar" \
    "/mnt/c/Users/Даниил/AppData/Local/Android/Sdk/build-tools/36.1.0/lib/apksigner.jar" \
    "${ANDROID_HOME:-}/build-tools/36.0.0/lib/apksigner.jar"; do
    if [[ -f "$candidate" ]]; then APKSIGNER_JAR="$candidate"; break; fi
  done
fi
[[ -n "${APKSIGNER_JAR:-}" && -f "$APKSIGNER_JAR" ]] || {
  echo "ERROR: set APKSIGNER_JAR to a valid apksigner.jar." >&2; exit 1;
}

if [[ -z "${ZIPALIGN:-}" ]]; then
  for candidate in \
    "${ANDROID_HOME:-}/build-tools/36.1.0/zipalign" \
    "${ANDROID_HOME:-}/build-tools/36.1.0/zipalign.exe" \
    "/mnt/c/Users/Даниил/AppData/Local/Android/Sdk/build-tools/36.1.0/zipalign.exe" \
    "${ANDROID_HOME:-}/build-tools/36.0.0/zipalign"; do
    if [[ -x "$candidate" ]]; then ZIPALIGN="$candidate"; break; fi
  done
fi
[[ -n "${ZIPALIGN:-}" && -x "$ZIPALIGN" ]] || {
  echo "ERROR: set ZIPALIGN to an executable zipalign binary." >&2; exit 1;
}

EXPECTED=(
  "Luma-Music-2.0.0-armeabi-v7a.apk"
  "Luma-Music-2.0.0-arm64-v8a.apk"
  "Luma-Music-2.0.0-x86.apk"
  "Luma-Music-2.0.0-x86_64.apk"
  "Luma-Music-2.0.0-universal.apk"
)

mapfile -t FOUND_APKS < <(find "$INPUT_DIR" -maxdepth 1 -type f -name '*.apk' -printf '%f\n' | sort)
if (("${#FOUND_APKS[@]}" != 5)); then
  printf 'ERROR: expected exactly five APK files; found %s.\n' "${#FOUND_APKS[@]}" >&2
  printf 'Found: %s\n' "${FOUND_APKS[@]:-<none>}" >&2
  exit 1
fi
for name in "${EXPECTED[@]}"; do
  [[ -f "$INPUT_DIR/$name" ]] || { echo "ERROR: missing $name" >&2; exit 1; }
done
for name in "${FOUND_APKS[@]}"; do
  printf '%s\n' "${EXPECTED[@]}" | grep -Fxq "$name" || {
    echo "ERROR: unexpected APK: $name" >&2; exit 1;
  }
done

mkdir -p "$KEY_DIR"
chmod 700 "$KEY_DIR"
KEYSTORE="$KEY_DIR/luma-family.p12"
PASSWORD_FILE="$KEY_DIR/password"
PIN_FILE="$KEY_DIR/certificate.sha256"

if [[ -e "$KEYSTORE" || -e "$PASSWORD_FILE" ]]; then
  [[ -f "$KEYSTORE" && -f "$PASSWORD_FILE" ]] || {
    echo "ERROR: incomplete signing-key directory. Nothing was changed; inspect $KEY_DIR manually." >&2
    exit 1
  }
else
  if find "$KEY_DIR" -mindepth 1 -maxdepth 1 -print -quit | grep -q .; then
    echo "ERROR: signing-key directory is not empty and contains no complete key. Refusing to overwrite it." >&2
    exit 1
  fi
  openssl rand -base64 48 | tr -d '\n' > "$PASSWORD_FILE"
  chmod 600 "$PASSWORD_FILE"
  keytool -genkeypair \
    -keystore "$KEYSTORE" -storetype PKCS12 \
    -alias luma-family -keyalg RSA -keysize 3072 -validity 10000 \
    -dname "CN=Luma Music Family" \
    -storepass:file "$PASSWORD_FILE"
  chmod 600 "$KEYSTORE" "$PASSWORD_FILE"
fi

mkdir -p "$OUTPUT_DIR"
for name in "${EXPECTED[@]}"; do
  echo "Aligning and signing $name"
  ALIGNED="$OUTPUT_DIR/.aligned-$name"
  "$ZIPALIGN" -f -P 16 4 "$INPUT_DIR/$name" "$ALIGNED"
  java -jar "$APKSIGNER_JAR" sign \
    --ks "$KEYSTORE" --ks-key-alias luma-family \
    --ks-pass "file:$PASSWORD_FILE" \
    --out "$OUTPUT_DIR/$name" "$ALIGNED"
  rm -f "$ALIGNED"
  java -jar "$APKSIGNER_JAR" verify --verbose --print-certs "$OUTPUT_DIR/$name" \
    > "$OUTPUT_DIR/.verify-$name.txt" 2>&1 || {
      cat "$OUTPUT_DIR/.verify-$name.txt" >&2
      echo "ERROR: signature verification failed for $name" >&2
      exit 1
    }
  "$ZIPALIGN" -c -P 16 4 "$OUTPUT_DIR/$name"
done

FINGERPRINTS=()
for name in "${EXPECTED[@]}"; do
  fp="$(sed -nE 's/.*certificate SHA-256 digest: ([0-9A-Fa-f:]+).*/\1/p' "$OUTPUT_DIR/.verify-$name.txt" | head -n 1 | tr -d ':' | tr '[:upper:]' '[:lower:]')"
  [[ "$fp" =~ ^[0-9a-f]{64}$ ]] || {
    echo "ERROR: could not extract SHA-256 certificate fingerprint from $name" >&2
    exit 1
  }
  FINGERPRINTS+=("$fp")
done
rm -f "$OUTPUT_DIR"/.verify-*.txt
FIRST_FP="${FINGERPRINTS[0]}"
for fp in "${FINGERPRINTS[@]}"; do
  [[ "$fp" == "$FIRST_FP" ]] || { echo "ERROR: APKs do not share one signing certificate." >&2; exit 1; }
done

if [[ -f "$PIN_FILE" ]]; then
  EXISTING_PIN="$(tr -d '[:space:]:' < "$PIN_FILE" | tr '[:upper:]' '[:lower:]')"
  [[ "$EXISTING_PIN" == "$FIRST_FP" ]] || {
    echo "ERROR: signing certificate differs from the existing pin. Refusing to replace it." >&2
    exit 1
  }
else
  printf '%s\n' "$FIRST_FP" > "$PIN_FILE"
  chmod 600 "$PIN_FILE"
fi

(
  cd "$OUTPUT_DIR"
  sha256sum "${EXPECTED[@]}" > SHA256SUMS.txt
)

printf '\nSigning completed successfully.\n'
printf 'Certificate SHA-256: %s\n' "$FIRST_FP"
printf 'Signed APKs and SHA256SUMS.txt: %s\n' "$OUTPUT_DIR"
printf 'Private key material remains in: %s\n' "$KEY_DIR"
echo "IMPORTANT: back up the entire signing-key directory securely. Never share it."
