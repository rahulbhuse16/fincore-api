#!/usr/bin/env bash
# Compiles and runs every framework-free unit test (JDK 21 + JUnit 5 console only: no Maven, Docker or network
# needed once the JUnit console jar is present). These tests cover the financial core: ledger, state machine,
# risk rules, reconciliation, idempotency fingerprinting, KYC rules, password policy.
#
# Usage:  scripts/verify-domain.sh                (downloads the JUnit console jar if JUNIT_CONSOLE is unset)
#         JUNIT_CONSOLE=/path/to/junit-platform-console-standalone.jar scripts/verify-domain.sh
set -euo pipefail
cd "$(dirname "$0")/.."

JUNIT_VERSION=1.10.3
JUNIT_CONSOLE="${JUNIT_CONSOLE:-.verify/junit-platform-console-standalone-${JUNIT_VERSION}.jar}"
if [ ! -f "$JUNIT_CONSOLE" ]; then
  mkdir -p .verify
  echo "Downloading JUnit console launcher ${JUNIT_VERSION}..."
  curl -fsSL -o "$JUNIT_CONSOLE" \
    "https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/${JUNIT_VERSION}/junit-platform-console-standalone-${JUNIT_VERSION}.jar"
fi

OUT=.verify/classes
rm -rf "$OUT" && mkdir -p "$OUT"

# Pure domain sources: packages named "domain" plus the Kafka event contract records.
find . -path ./.verify -prune -o -type f -name '*.java' \
  \( -path '*/src/main/java/*/domain/*' -o -path '*/src/main/java/com/fincore/platform/contracts/*' \
     -o -path '*/src/test/java/*/domain/*' \) -print > .verify/sources.txt

echo "Compiling $(wc -l < .verify/sources.txt) files with $(javac -version 2>&1)..."
javac --release 21 -Xlint:all,-serial -Werror -cp "$JUNIT_CONSOLE" -d "$OUT" @.verify/sources.txt

echo "Running domain tests..."
java -jar "$JUNIT_CONSOLE" execute --class-path "$OUT" --scan-class-path "$OUT" \
  --details=summary --disable-banner

# Stage 2: the event codec (needs Jackson only). JACKSON_CP can point at existing jars; otherwise they are downloaded.
if [ -z "${JACKSON_CP:-}" ]; then
  JV=2.17.2
  JACKSON_CP=""
  for a in "com/fasterxml/jackson/core/jackson-core" "com/fasterxml/jackson/core/jackson-annotations" \
           "com/fasterxml/jackson/core/jackson-databind" "com/fasterxml/jackson/datatype/jackson-datatype-jsr310"; do
    jar=".verify/$(basename "$a")-${JV}.jar"
    [ -f "$jar" ] || curl -fsSL -o "$jar" "https://repo1.maven.org/maven2/${a}/${JV}/$(basename "$a")-${JV}.jar"
    JACKSON_CP="${JACKSON_CP:+$JACKSON_CP:}$jar"
  done
fi
OUT2=.verify/classes-codec
rm -rf "$OUT2" && mkdir -p "$OUT2"
javac --release 21 -Xlint:all,-serial -Werror -cp "$JUNIT_CONSOLE:$JACKSON_CP" -d "$OUT2" \
  platform-commons/src/main/java/com/fincore/platform/contracts/*.java \
  platform-commons/src/main/java/com/fincore/platform/events/EventEnvelope.java \
  platform-commons/src/main/java/com/fincore/platform/events/EventCodec.java \
  platform-commons/src/main/java/com/fincore/platform/events/InvalidEventException.java \
  platform-commons/src/test/java/com/fincore/platform/events/EventCodecTest.java
echo "Running codec tests..."
java -cp "$JUNIT_CONSOLE:$JACKSON_CP:$OUT2" org.junit.platform.console.ConsoleLauncher execute --scan-class-path "$OUT2" \
  --details=summary --disable-banner
