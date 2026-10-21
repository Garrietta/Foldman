#!/usr/bin/env python3
"""Makes an Apple Music developer token (an ES256-signed JWT) from a MusicKit private key.

    python3 tools/make_apple_token.py

Reads the Team ID, Key ID and .p8 path from apple-music.properties (git-ignored; see
apple-music.properties.example), signs a token and writes it back there as apple.developerToken,
which the Android build reads. Flags override the file: --key, --key-id, --team-id. --print shows
the token instead of saving it. Needs only Python 3 and the openssl command (both ship with macOS).
Tokens can live at most six months (Apple's limit); the default is 180 days.
"""
import argparse, base64, json, pathlib, re, subprocess, sys, time

CONFIG = pathlib.Path(__file__).resolve().parent.parent / "apple-music.properties"


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def der_to_raw(der: bytes) -> bytes:
    """openssl emits an ASN.1 DER signature; JWT's ES256 wants r and s as 32 bytes each."""
    def read_int(buf, i):
        assert buf[i] == 0x02, "bad signature"
        n = buf[i + 1]
        return int.from_bytes(buf[i + 2:i + 2 + n], "big"), i + 2 + n
    assert der[0] == 0x30, "bad signature"
    i = 3 if der[1] & 0x80 else 2
    r, i = read_int(der, i)
    s, _ = read_int(der, i)
    return r.to_bytes(32, "big") + s.to_bytes(32, "big")


def read_config() -> dict:
    if not CONFIG.exists():
        return {}
    props = {}
    for line in CONFIG.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    return props


def save_token(token: str):
    text = CONFIG.read_text() if CONFIG.exists() else ""
    line = f"apple.developerToken={token}"
    if re.search(r"(?m)^apple\.developerToken=", text):
        text = re.sub(r"(?m)^apple\.developerToken=.*$", lambda _: line, text)
    else:
        text = text.rstrip("\n") + "\n" + line + "\n"
    CONFIG.write_text(text)


def main():
    cfg = read_config()
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--key", default=cfg.get("apple.keyPath"), help="the .p8 file (default: apple.keyPath)")
    p.add_argument("--key-id", default=cfg.get("apple.keyId"), help="10-character Key ID (default: apple.keyId)")
    p.add_argument("--team-id", default=cfg.get("apple.teamId"), help="10-character Team ID (default: apple.teamId)")
    p.add_argument("--days", type=int, default=180, help="lifetime in days, at most 182")
    p.add_argument("--print", action="store_true", help="print the token instead of saving it")
    a = p.parse_args()
    missing = [n for n, v in (("--key / apple.keyPath", a.key), ("--key-id / apple.keyId", a.key_id), ("--team-id / apple.teamId", a.team_id)) if not v]
    if missing:
        sys.exit(f"Missing {', '.join(missing)}. Fill in {CONFIG.name} (see {CONFIG.name}.example).")
    if not 1 <= a.days <= 182:
        sys.exit("--days must be between 1 and 182 (Apple allows six months at most)")
    if not pathlib.Path(a.key).expanduser().exists():
        sys.exit(f"Key file not found: {a.key}")

    now = int(time.time())
    header = b64url(json.dumps({"alg": "ES256", "kid": a.key_id}, separators=(",", ":")).encode())
    claims = b64url(json.dumps({"iss": a.team_id, "iat": now, "exp": now + a.days * 86400}, separators=(",", ":")).encode())
    signing_input = f"{header}.{claims}".encode()
    der = subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(pathlib.Path(a.key).expanduser())],
                         input=signing_input, capture_output=True, check=True).stdout
    token = f"{header}.{claims}.{b64url(der_to_raw(der))}"

    if a.print:
        print(token)
    else:
        save_token(token)
        expires = time.strftime("%Y-%m-%d", time.localtime(now + a.days * 86400))
        print(f"Saved a developer token to {CONFIG.name} (expires {expires}). Rebuild the app to pick it up.")


if __name__ == "__main__":
    main()
