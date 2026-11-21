#!/usr/bin/env bash
# Preserve the Gradle result while collecting only the synthetic database-query benchmark tag.
set -uo pipefail
status=0
./gradlew :app:connectedGithubDebugAndroidTest --stacktrace --no-daemon || status=$?
mkdir -p evidence
if ! adb logcat -d -s RkmLongChatPerf:I '*:S' > evidence/long-chat-database-perf.log; then
  echo 'ERROR: cannot collect database-query benchmark evidence'
  if [ "$status" -eq 0 ]; then status=1; fi
fi
if [ "$status" -eq 0 ] && ! grep -Fq LONG_CHAT_DB_PERF evidence/long-chat-database-perf.log; then
  echo 'ERROR: full instrumented run did not produce the required database-query benchmark'
  status=1
fi
exit "$status"
