#!/bin/sh
# usage: gen-application-yml.sh <module> <port> <db> <dbuser> <kafka:true|false> <outbox:true|false> [fincore-extra-file|-] [top-level-extra-file]
# Generates src/main/resources/application.yml with the conventions shared by all servlet services.
M=$1; PORT=$2; DB=$3; USER=$4; KAFKA=$5; OUTBOX=$6; FEXTRA=$7; TEXTRA=$8
OUT=/home/claude/fincore/$M/src/main/resources/application.yml
cat > "$OUT" <<YML
spring:
  application:
    name: $M
  datasource:
    url: \${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/$DB}
    username: \${SPRING_DATASOURCE_USERNAME:$USER}
    password: \${SPRING_DATASOURCE_PASSWORD:}
    hikari:
      maximum-pool-size: 10
      connection-timeout: 3000
      max-lifetime: 900000
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate          # schema is owned by Flyway, never by Hibernate
    properties:
      hibernate.jdbc.time_zone: UTC
  flyway:
    enabled: true
    locations: classpath:db/migration,classpath:db/platform
  kafka:
    bootstrap-servers: \${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      acks: all
      properties:
        enable.idempotence: true
        delivery.timeout.ms: 10000
        request.timeout.ms: 5000
        max.block.ms: 5000
    consumer:
      enable-auto-commit: false
      auto-offset-reset: earliest
      max-poll-records: 50
    listener:
      ack-mode: record
      observation-enabled: true
    template:
      observation-enabled: true

server:
  port: $PORT
  shutdown: graceful

fincore:
  kafka.enabled: $KAFKA
  outbox.enabled: $OUTBOX
  security:
    jwt-secret: \${JWT_SECRET:}
    allowed-origins: \${CORS_ALLOWED_ORIGINS:}
__FEXTRA__
management:
  endpoints.web.exposure.include: health,info,prometheus,metrics
  endpoint.health.probes.enabled: true
  endpoint.health.show-details: never
  metrics.tags.application: \${spring.application.name}
  tracing.sampling.probability: \${TRACING_SAMPLING:1.0}
  otlp.tracing.endpoint: \${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:http://localhost:4318/v1/traces}

logging:
  level:
    root: INFO
    org.apache.kafka: WARN
YML
if [ -n "$FEXTRA" ] && [ "$FEXTRA" != "-" ]; then
  python3 - "$OUT" "$FEXTRA" <<'PY'
import sys
out, extra = sys.argv[1], sys.argv[2]
s = open(out).read().replace("__FEXTRA__\n", open(extra).read().rstrip("\n") + "\n\n")
open(out, "w").write(s)
PY
else
  sed -i '/^__FEXTRA__$/d' "$OUT"
fi
if [ -n "$TEXTRA" ]; then printf '\n' >> "$OUT"; cat "$TEXTRA" >> "$OUT"; fi
cp /home/claude/fincore/ledger-service/src/main/resources/logback-spring.xml /home/claude/fincore/$M/src/main/resources/logback-spring.xml 2>/dev/null || true
