.PHONY: tunnel grafana prometheus logs

# EC2 Prometheus/Grafana를 로컬에서 접속
# 실행 후 브라우저에서:
#   Grafana   → http://localhost:3001  (admin / .env의 GRAFANA_PASSWORD)
#   Prometheus → http://localhost:9090
tunnel:
	@echo "🔌 SSH 터널 연결 중... (종료: Ctrl+C)"
	ssh -L 3001:localhost:3001 -L 9090:localhost:9090 \
		-N -o ServerAliveInterval=60 \
		ubuntu@membershipflow.site

# Grafana만 접속
grafana:
	@echo "📊 Grafana 터널 → http://localhost:3001"
	ssh -L 3001:localhost:3001 -N ubuntu@membershipflow.site

# Prometheus만 접속
prometheus:
	@echo "📈 Prometheus 터널 → http://localhost:9090"
	ssh -L 9090:localhost:9090 -N ubuntu@membershipflow.site

# EC2 백엔드 로그 실시간
logs:
	ssh ubuntu@membershipflow.site \
		"docker compose -f /opt/membershipflow/docker-compose.yml logs -f backend"
