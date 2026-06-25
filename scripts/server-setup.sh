#!/bin/bash
# EC2 Ubuntu 22.04 초기 셋업 스크립트
# 사용법: bash server-setup.sh <GITHUB_TOKEN>
set -e

GITHUB_TOKEN=${1:?"GITHUB_TOKEN 인자 필요"}

echo "=== 패키지 업데이트 ==="
sudo apt-get update && sudo apt-get upgrade -y

echo "=== Docker 설치 ==="
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker ubuntu
sudo systemctl enable docker

echo "=== Certbot 설치 ==="
sudo apt-get install -y certbot

echo "=== 앱 디렉터리 생성 ==="
sudo mkdir -p /opt/membershipflow
sudo chown ubuntu:ubuntu /opt/membershipflow

echo "=== ghcr.io 로그인 ==="
echo "$GITHUB_TOKEN" | docker login ghcr.io -u ohhalim --password-stdin

echo ""
echo "=== 완료! 다음 단계 ==="
echo "1. /opt/membershipflow 에 docker-compose.yml 과 .env 복사"
echo "2. SSL 인증서 발급:"
echo "   docker compose --profile certbot run certbot certonly --webroot -w /var/www/certbot -d membershipflow.site -d www.membershipflow.site --email ohhalim777@gmail.com --agree-tos"
echo "3. nginx.conf 를 nginx.https.conf 로 교체"
echo "4. docker compose up -d"
