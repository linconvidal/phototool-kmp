#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
allow_skip=false
if [[ "${1:-}" == "--allow-skip" ]]; then allow_skip=true; shift; fi
if [[ $# -ne 0 ]]; then printf 'usage: %s [--allow-skip]\n' "$0" >&2; exit 64; fi
package_task=''
case "$(uname -s)" in
  Linux)
    if command -v dpkg-deb >/dev/null; then package_task=':desktopApp:packageDeb'
    elif $allow_skip; then printf 'Native package skipped explicitly: dpkg-deb is not installed.\n'; exit 0
    else printf 'Cannot package: dpkg-deb is not installed. Use --allow-skip only for non-release checks.\n' >&2; exit 69
    fi
    ;;
  *) package_task=':desktopApp:packageDistributionForCurrentOS' ;;
esac
./gradlew --no-daemon "$package_task"
printf 'Package task %s succeeded; artifacts are below desktopApp/build/compose/binaries/main\n' "$package_task"

if [[ "$package_task" == ':desktopApp:packageDeb' ]]; then
  deb=$(find desktopApp/build/compose/binaries/main/deb -maxdepth 1 -type f -name '*.deb' -print -quit)
  [[ -n "$deb" ]] || { printf 'DEB artifact missing after successful task.\n' >&2; exit 70; }
  dpkg-deb --info "$deb" >/tmp/phototool-kmp-deb-info.txt
  extraction=$(mktemp -d)
  trap 'gio trash "$extraction"' EXIT
  dpkg-deb --extract "$deb" "$extraction"
  launcher=$(find "$extraction" -type f -path '*/bin/phototool-kmp' -print -quit)
  runtime_modules=$(find "$extraction" -type f -path '*/lib/runtime/lib/modules' -print -quit)
  runtime_jvm=$(find "$extraction" -type f -path '*/lib/runtime/lib/server/libjvm.so' -print -quit)
  [[ -n "$launcher" && -x "$launcher" && -n "$runtime_modules" && -s "$runtime_modules" && -n "$runtime_jvm" && -s "$runtime_jvm" ]] || { printf 'Extracted package launcher/runtime validation failed.\n' >&2; exit 71; }
  mkdir "$extraction/smoke-library" "$extraction/smoke-cache"
  set +e
  "$launcher" --smoke --read-only --library "$extraction/smoke-library" --cache "$extraction/smoke-cache" >"$extraction/launcher-smoke.txt" 2>&1
  smoke_status=$?
  set -e
  [[ $smoke_status -eq 2 ]] || { cat "$extraction/launcher-smoke.txt"; printf 'Packaged launcher did not execute the expected empty-corpus smoke path.\n' >&2; exit 72; }
  cat "$extraction/launcher-smoke.txt"
  if ! [[ "$(cat "$extraction/launcher-smoke.txt")" == *"No supported media was verified"* ]]; then printf 'Packaged runtime failed before completing the smoke pipeline.\n' >&2; exit 73; fi
  printf 'DEB metadata, extraction, launcher execution, and bundled runtime smoke passed.\n'
fi
