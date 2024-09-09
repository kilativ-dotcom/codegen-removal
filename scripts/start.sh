#!/usr/bin/env bash
set -eo pipefail
ROOT_PATH="$(cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd)/.."
java -jar "$ROOT_PATH/out/artifacts/codegen_removal_jar/codegen-removal.jar" "$@"
