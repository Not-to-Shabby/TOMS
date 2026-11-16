#!/usr/bin/env python3
"""TOMS Return Machine — Production Signing Key Generator (Phase 6 / Security)

Generates an offline, private ECDSA P-256 keypair for field units to resolve
security finding S1 (replacing MCUboot's public development key).

The generated PEM file is automatically excluded from git by firmware/.gitignore (*.pem).
NEVER commit this file to version control.
"""

import argparse
import os
import subprocess
import sys
from pathlib import Path

IMGTOOL_CANDIDATES = [
    Path("D:/zephyrproject/.venv/Scripts/imgtool.exe"),
    Path(sys.prefix) / "Scripts" / "imgtool.exe",
    Path("imgtool"),
]

def find_imgtool() -> str:
    for candidate in IMGTOOL_CANDIDATES:
        if candidate.is_file() or candidate.name == "imgtool":
            return str(candidate)
    return "imgtool"

def main():
    parser = argparse.ArgumentParser(description="Generate private production ECDSA P-256 keypair for MCUboot")
    parser.add_argument(
        "--out", "-o",
        default="D:/TOMS/firmware/keys/production-ec-p256.pem",
        help="Destination PEM path (default: firmware/keys/production-ec-p256.pem)"
    )
    parser.add_argument(
        "--force", "-f",
        action="store_true",
        help="Overwrite destination if it already exists"
    )
    args = parser.parse_args()

    out_path = Path(args.out).resolve()
    if out_path.exists() and not args.force:
        print(f"Error: {out_path} already exists. Pass --force to overwrite.", file=sys.stderr)
        sys.exit(1)

    out_path.parent.mkdir(parents=True, exist_ok=True)
    imgtool = find_imgtool()

    print(f"Generating ECDSA P-256 keypair with {imgtool}...")
    try:
        subprocess.run([imgtool, "keygen", "-t", "ecdsa-p256", "-k", str(out_path)], check=True)
    except Exception as e:
        print(f"Failed to generate key: {e}", file=sys.stderr)
        sys.exit(1)

    print(f"SUCCESS: Private production key written to:\n  {out_path}")

    # Dump public key hash
    try:
        res = subprocess.run([imgtool, "getpubhash", "-k", str(out_path)], capture_output=True, text=True, check=True)
        print(f"\nPublic key SHA-256 hash:\n  {res.stdout.strip()}")
    except Exception:
        pass

    print("\nHow to use in production builds:")
    print(f"  west build -p always -b esp32s3_devkitc/esp32s3/procpu firmware/return-machine \\")
    print(f"    --sysbuild -- \\")
    print(f"    -DSB_CONFIG_MCUBOOT_MODE_SWAP_SCRATCH=y \\")
    print(f"    -DSB_CONFIG_BOOT_SIGNATURE_TYPE_ECDSA_P256=y \\")
    print(f"    -DSB_CONFIG_BOOT_SIGNATURE_KEY_FILE=\"{out_path.as_posix()}\" \\")
    print(f"    -Dreturn-machine_CONFIG_TOMS_DEV_MODE=n")
    print("\nIMPORTANT: Keep two encrypted offline backups of this private key.")

if __name__ == "__main__":
    main()
