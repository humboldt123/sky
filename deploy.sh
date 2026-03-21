#!/usr/bin/env bash
set -euo pipefail
./gradlew build && scp build/libs/sky-*.jar root@fig.skrub.dev:/home/vish/sky-pvp/plugins/
