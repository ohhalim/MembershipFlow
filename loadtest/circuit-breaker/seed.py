import argparse
import base64
import hashlib
import hmac
import json
import subprocess
import time
from datetime import datetime, timedelta
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
DEFAULT_SECRET = "dev-only-secret-please-change-0123456789-0123456789-0123456789"
COURSE_ID = 900001
MEMBER_ID_START = 900001


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def sign_jwt(secret: str, claims: dict) -> str:
    key = secret.encode()
    bits = len(key) * 8
    if bits >= 512:
        alg, digest = "HS512", hashlib.sha512
    elif bits >= 384:
        alg, digest = "HS384", hashlib.sha384
    elif bits >= 256:
        alg, digest = "HS256", hashlib.sha256
    else:
        raise SystemExit("JWT secret must be at least 256 bits")
    header = b64url(json.dumps({"alg": alg}, separators=(",", ":")).encode())
    payload = b64url(json.dumps(claims, separators=(",", ":")).encode())
    signing_input = f"{header}.{payload}".encode()
    signature = b64url(hmac.new(key, signing_input, digest).digest())
    return f"{header}.{payload}.{signature}"


def build_sql(members: int, now: datetime) -> str:
    last_member_id = MEMBER_ID_START + members - 1
    lines = [
        "SET NAMES utf8mb4;",
        f"DELETE FROM paddle_checkout_attempt WHERE member_id BETWEEN {MEMBER_ID_START} AND {last_member_id};",
        f"DELETE FROM price_history WHERE course_id = {COURSE_ID};",
        "INSERT IGNORE INTO membership_course (id, name, region, course_type, membership_type, active) "
        f"VALUES ({COURSE_ID}, 'circuit-breaker-loadtest-course', 'loadtest', 'GOLF', 'REGULAR', 1);",
    ]
    rows = []
    for source_id in range(1, 9):
        for hours_ago in range(0, 47):
            collected_at = (now - timedelta(hours=hours_ago, minutes=source_id)).strftime("%Y-%m-%d %H:%M:%S")
            price = 50_000_000 + source_id * 100_000 + hours_ago * 1_000
            rows.append(f"({COURSE_ID}, {price}, '{collected_at}', {source_id})")
    lines.append(
        "INSERT INTO price_history (course_id, price, collected_at, source_id) VALUES "
        + ",".join(rows) + ";"
    )
    member_rows = []
    for member_id in range(MEMBER_ID_START, last_member_id + 1):
        member_rows.append(
            f"({member_id}, 'cb-loadtest-{member_id}@example.test', 'GOOGLE', "
            f"'cb-loadtest-{member_id}', 'cb-loadtest-{member_id}', 'USER')"
        )
    lines.append(
        "INSERT IGNORE INTO member (id, email, provider, provider_id, name, role) VALUES "
        + ",".join(member_rows) + ";"
    )
    return "\n".join(lines) + "\n"


def run_sql(sql: str) -> None:
    subprocess.run(
        ["docker", "compose", "-f", str(HERE / "docker-compose.yml"), "exec", "-T", "mysql",
         "mysql", "-uroot", "-proot", "--default-character-set=utf8mb4", "membershipflow"],
        input=sql.encode(), check=True,
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--members", type=int, default=150)
    parser.add_argument("--secret", default=DEFAULT_SECRET)
    parser.add_argument("--token-hours", type=int, default=6)
    parser.add_argument("--out", default=str(ROOT / "build" / "loadtest" / "paddle-test-users.json"))
    args = parser.parse_args()

    run_sql(build_sql(args.members, datetime.now()))

    issued_at = int(time.time())
    users = []
    for member_id in range(MEMBER_ID_START, MEMBER_ID_START + args.members):
        token = sign_jwt(args.secret, {
            "sub": str(member_id),
            "email": f"cb-loadtest-{member_id}@example.test",
            "name": f"cb-loadtest-{member_id}",
            "role": "USER",
            "iat": issued_at,
            "exp": issued_at + args.token_hours * 3600,
        })
        users.append({"name": f"cb-loadtest-{member_id}", "accessToken": token})

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(users, indent=2))
    print(f"seeded course={COURSE_ID} members={args.members} tokens={out}")


if __name__ == "__main__":
    main()
