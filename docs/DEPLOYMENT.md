# 배포 가이드

## 아키텍처

```
[User] → membershipflow.site → [EC2]
                                  ├── nginx (80/443)
                                  │     ├── /api/*  → backend:8081
                                  │     ├── /oauth2/* → backend:8081
                                  │     └── /*       → frontend:3000
                                  ├── frontend (Next.js)
                                  ├── backend (Spring Boot)
                                  ├── mysql
                                  ├── prometheus (127.0.0.1:9090)
                                  └── grafana (127.0.0.1:3001)
```

## CI/CD 흐름

```
push to main
  → GitHub Actions CI (test)
  → build Docker image
  → push to ghcr.io/ohhalim/membershipflow-{backend,front}:latest
  → SSH to EC2
  → docker compose pull && docker compose up -d --no-deps {service}
```

## 1. EC2 생성

- AMI: Ubuntu 22.04 LTS
- 인스턴스: t3.small
- 보안 그룹: 80(HTTP), 443(HTTPS), 22(SSH) 인바운드 허용
- Elastic IP 발급 후 연결

## 2. Gabia DNS 설정

가비아 → DNS 관리 → membershipflow.site → 레코드 추가:

| 타입 | 호스트 | 값            | TTL  |
|------|--------|---------------|------|
| A    | @      | {EC2 Elastic IP} | 600  |
| A    | www    | {EC2 Elastic IP} | 600  |

## 3. EC2 서버 셋업

```bash
# GitHub PAT (read:packages 권한) 발급 후:
bash scripts/server-setup.sh <GITHUB_TOKEN>
```

## 4. .env 파일 생성

```bash
# EC2에서
cp /opt/membershipflow/.env.example /opt/membershipflow/.env
vim /opt/membershipflow/.env  # 실제 값 채우기
```

## 5. SSL 인증서 발급

```bash
cd /opt/membershipflow

# nginx를 HTTP 모드로 먼저 시작 (Let's Encrypt webroot challenge용)
docker compose up -d nginx

# 인증서 발급
docker compose --profile certbot run certbot certonly \
  --webroot -w /var/www/certbot \
  -d membershipflow.site -d www.membershipflow.site \
  --email ohhalim777@gmail.com --agree-tos --non-interactive

# nginx 재시작 (nginx.conf가 이미 HTTPS 설정 포함)
docker compose restart nginx
```

## 6. 전체 서비스 시작

```bash
cd /opt/membershipflow
docker compose up -d
```

## 7. GitHub Actions Secrets 설정

두 레포(MembershipFlow, MembershipFlow-front) 각각에 추가:

| Secret 이름    | 값                          |
|----------------|-----------------------------|
| EC2_HOST       | EC2 Elastic IP              |
| EC2_USER       | ubuntu                      |
| EC2_SSH_KEY    | EC2 `.pem` 파일 전체 내용   |

(GITHUB_TOKEN은 Actions가 자동 제공 — 별도 설정 불필요)

## 8. Google OAuth2 Redirect URI 추가

Google Cloud Console → 사용자 인증 정보 → OAuth 클라이언트:

승인된 리디렉션 URI에 추가:
```
https://membershipflow.site/login/oauth2/code/google
```

## SSL 자동 갱신 (cron)

```bash
# EC2 crontab
0 3 * * 0 docker compose --profile certbot run certbot renew && docker compose restart nginx
```

## 로컬 개발

```bash
# 전체 도커 스택 (로컬 빌드)
docker compose -f docker-compose.yml -f docker-compose.local.yml up --build

# 백엔드만 IDE에서 실행할 경우 (DB만 도커)
docker compose up mysql
```
