#!/usr/bin/env bash
set -euo pipefail
./gradlew build && scp build/libs/crypt-*.jar root@fig.skrub.dev:/home/vish/sky-pvp/plugins/
