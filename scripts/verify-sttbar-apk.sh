#!/usr/bin/env bash
set -euo pipefail

apk=${1:?Pass the APK path}
version=${2:?Pass the expected version name}
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z "$sdk" ]]; then
    echo "ANDROID_HOME or ANDROID_SDK_ROOT is required" >&2
    exit 2
fi

tools_dir=$(python3 - "$sdk" <<'PY'
from pathlib import Path
import sys
root = Path(sys.argv[1]) / "build-tools"
versions = [path for path in root.iterdir() if path.is_dir()]
if not versions:
    raise SystemExit("No Android build tools found")
print(max(versions, key=lambda path: tuple(int(part) for part in path.name.split('.'))))
PY
)

badging=$("$tools_dir/aapt" dump badging "$apk")
expected_package="com.razeeman.util.simpletimetracker.debug"
if ! grep -Fq "package: name='$expected_package'" <<< "$badging"; then
    echo "APK package does not match the installed STTbar build" >&2
    exit 1
fi
if ! grep -Fq "versionName='$version'" <<< "$badging"; then
    echo "APK version does not match $version" >&2
    exit 1
fi

signer=$("$tools_dir/apksigner" verify --print-certs "$apk" 2>/dev/null |
    sed -nE 's/.*certificate SHA-256 digest: ([0-9a-fA-F]+).*/\1/p' | head -1 |
    tr '[:upper:]' '[:lower:]')
expected_signer=3c4e05f91b1f2cebe887a238a85d17c0d838b2cbd6f96f2d5f5ae8e02a0b6200
if [[ "$signer" != "$expected_signer" ]]; then
    echo "APK signer differs from the installed STTbar build" >&2
    exit 1
fi

printf 'Verified %s, version %s, signer %s\n' "$expected_package" "$version" "$signer"
