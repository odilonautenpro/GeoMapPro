import base64, json, time, sys
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import load_pem_private_key

def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()

def b64url_decode(s: str) -> bytes:
    pad = "=" * (-len(s) % 4)
    return base64.urlsafe_b64decode(s + pad)

def sign_payload(priv_pem_path: str, payload_bytes: bytes) -> bytes:
    with open(priv_pem_path, "rb") as f:
        priv = load_pem_private_key(f.read(), password=None)
    return priv.sign(payload_bytes, ec.ECDSA(hashes.SHA256()))

def make_license(req_text: str, priv_pem_path: str, license_id: str, expires_at_ms: int = 0) -> str:
    if not req_text.startswith("REQ:"):
        raise ValueError("REQ inválido (não começa com REQ:)")

    req_b64 = req_text[4:].strip()
    req_json = b64url_decode(req_b64).decode("utf-8")
    req = json.loads(req_json)

    payload = {
        "pkg": req["pkg"],
        "pubkey_hash": req["pubkey_hash"],
        "license_id": license_id,
        "issued_at": int(time.time() * 1000),
        "expires_at": int(expires_at_ms),  # 0 = vitalício
    }

    payload_bytes = json.dumps(payload, separators=(",", ":"), sort_keys=True, ensure_ascii=False).encode("utf-8")

    sig = sign_payload(priv_pem_path, payload_bytes)

    return "LIC:" + b64url(payload_bytes) + "." + b64url(sig)

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Uso: python gen_license.py \"REQ:...\" L-000123 [expires_at_ms]")
        sys.exit(1)

    req = sys.argv[1]
    lic_id = sys.argv[2]
    exp = int(sys.argv[3]) if len(sys.argv) >= 4 else 0

    lic = make_license(req, "company_priv.pem", lic_id, exp)
    print(lic)
