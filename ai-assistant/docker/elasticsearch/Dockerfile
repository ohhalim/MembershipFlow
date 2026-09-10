# 내부 RAG 검색용 Elasticsearch 이미지.
# analysis-nori 를 이미지 빌드 시 설치한다 (런타임 설치·재시작 없음).
# 근거: https://www.elastic.co/docs/reference/elasticsearch/plugins/analysis-nori
FROM docker.elastic.co/elasticsearch/elasticsearch:9.5.3
RUN bin/elasticsearch-plugin install --batch analysis-nori
