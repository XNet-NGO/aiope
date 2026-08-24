#!/usr/bin/env bash
# Baseline build aiope -> APK debug
set -x
cd /home/bsracc/aiope
export JAVA_HOME=${JAVA_HOME:-$(dirname $(dirname $(readlink -f $(which java))))}
sh gradlew :app:assembleDebug -x spotlessCheck -x spotlessKotlinCheck --no-daemon 2>&1
echo "BUILD_EXIT=$?"
