#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
sbt "service/packageService"
echo
echo "Fat jar: service/target/scala-3.7.1/service-assembly-0.1.0-SNAPSHOT.jar"
echo "Run with: java -jar <jar>   (needs Mongo + local.properties or env keys)"
echo "Or deploy: sbt \"service/deploy\"   (SSH to ttk.deploy.host, default 192.168.0.156)"
