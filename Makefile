.PHONY: logs

# EC2 백엔드 로그 실시간
logs:
	ssh -i ~/.ssh/membershipflow-key.pem ubuntu@membershipflow.site \
		"docker compose -f /opt/membershipflow/docker-compose.yml logs -f backend"
