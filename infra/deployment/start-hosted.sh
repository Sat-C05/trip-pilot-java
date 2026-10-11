#!/bin/sh
set -eu
: "${DB_URL:?Set the fresh hosted JDBC URL}"
: "${DB_USERNAME:?Set hosted database username}"
: "${DB_PASSWORD:?Set hosted database password}"
: "${MODEL_ID:?Select an evaluated exact Gemini model version}"
: "${GEMINI_API_KEY:?Set the server-only Gemini key}"
if ! printf '%s' "$DB_URL" | grep -Eq '^jdbc:mysql://[A-Za-z0-9.-]+:[0-9]+/[A-Za-z0-9_]+$'; then
  echo 'Use a hosted JDBC host:port/schema URL without query parameters; TLS options come from the hosted profile.' >&2
  exit 1
fi
if [ ! -r /etc/secrets/mysql-ca.pem ]; then
  echo 'Missing hosted MySQL CA certificate.' >&2
  exit 1
fi
# Certificate-only trust store; this fixed password protects no private keys.
keytool -importcert -noprompt -alias hosted-mysql -file /etc/secrets/mysql-ca.pem \
  -keystore /tmp/trippilot-db.p12 -storetype PKCS12 -storepass changeit >/dev/null 2>&1
export SPRING_PROFILES_ACTIVE=hosted
exec java -Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m \
  -XX:MaxDirectMemorySize=32m -Xss512k -XX:+ExitOnOutOfMemoryError -jar /app/backend.jar
