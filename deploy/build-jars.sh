#!/usr/bin/env bash
# Builds every service jar in one Maven run inside a container (no Java or Maven needed on the server).
# Dependencies are cached in the referboard-m2 volume, so later builds are much faster.
set -euo pipefail
cd "$(dirname "$0")/.."
docker run --rm \
    -v "$(pwd)":/src -w /src \
    -v referboard-m2:/root/.m2 \
    -e MAVEN_OPTS="-Xmx1g" \
    maven:3.9-eclipse-temurin-21 \
    mvn -B -q -T 2 -DskipTests package
n=$(find services infrastructure -path '*/target/*-SNAPSHOT.jar' | wc -l)
if [ "$n" -lt 13 ]; then
    echo "Expected 13 service jars, found $n. Look for a Maven error above." >&2
    exit 1
fi
echo "    $n jars built"
