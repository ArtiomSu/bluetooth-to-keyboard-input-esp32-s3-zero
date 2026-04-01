#!/usr/bin/env bash

firmware_build_dir="../build/esp32.esp32.esp32s3"

release_out="../../.raw-firmware"

required_files=(
    "firmware.ino.bootloader.bin"
    "firmware.ino.partitions.bin"
    "boot_app0.bin"
    "firmware.ino.bin"
    "firmware.ino.merged.bin"
)

# grab the firmware version from ../firmware.ino
# its in the form of #define FIRMWARE_VERSION           0x0105
version_line=$(grep "#define FIRMWARE_VERSION" ../firmware.ino)
if [[ -z "$version_line" ]]; then
    echo "Error: FIRMWARE_VERSION not found in firmware.ino"
    exit 1
fi

version_hex=$(echo "$version_line" | awk '{print $3}')
if [[ -z "$version_hex" ]]; then
    echo "Error: Could not extract version number from firmware.ino"
    exit 1
fi

# convert to proper version string, e.g. 0x0105 -> v1.5
version_major=$(( (version_hex & 0xFF00) >> 8 ))
version_minor=$(( version_hex & 0x00FF ))
version_str="${version_major}.${version_minor}"

echo "Firmware version: $version_str"

# update the "version" field in manifest.json and manifest-clean.json
manifest_files=("./manifest.json" "./manifest-clean.json")
for manifest in "${manifest_files[@]}"; do
    if [[ -f "$manifest" ]]; then
        echo "Updating version in $manifest to $version_str"
        # Use jq to update the version field
        tmp_file=$(mktemp)
        jq --arg version "$version_str" '.version = $version' "$manifest" > "$tmp_file" && mv "$tmp_file" "$manifest"
    else
        echo "Warning: Manifest file '$manifest' not found. Skipping version update."
    fi
done

rm -rf "$release_out"
mkdir -p "$release_out"

# copy the manifest files as well
cp "./manifest.json" "$release_out/"
cp "./manifest-clean.json" "$release_out/"

for file in "${required_files[@]}"; do
    src="$firmware_build_dir/$file"
    echo "Copying $src to $release_out/"
    if [[ -f "$src" ]]; then
        cp "$src" "$release_out/"
    else
        echo "Error: Required file '$file' not found in build directory."
        exit 1
    fi
done

# also copy the apk from the android standalone app
apk_src="../../android/standalone-app/app/release/app-release.apk"
if [[ -f "$apk_src" ]]; then
    cp "$apk_src" /tmp/
    echo "Copied APK to /tmp/app-release.apk"
else
    echo "Warning: APK file not found at '$apk_src'. Skipping APK copy."
fi

echo "Release files have been copied to '$release_out'."
