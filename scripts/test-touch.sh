#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"
if [[ -f .env.local ]]; then source .env.local; fi
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
mkdir -p build/touch-tests
"$JAVA_HOME/bin/javac" -d build/touch-tests \
    app/src/main/java/com/example/helloworld/TouchDecoder.java tests/TouchDecoderTest.java
"$JAVA_HOME/bin/java" -cp build/touch-tests com.example.helloworld.TouchDecoderTest
