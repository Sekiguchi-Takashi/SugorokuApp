#!/data/data/com.termux/files/usr/bin/bash
set -u
cd "$(dirname "$0")" || exit 1

MSG="${1:-update bside}"

if [ ! -d app ]; then
  printf '%s\n' "ERROR: app/ not found. Run this inside the existing SugorokuApp repo."
  exit 1
fi
if [ ! -d .git ]; then
  printf '%s\n' "ERROR: .git not found. This script only updates an existing repo."
  exit 1
fi
if [ ! -f settings.gradle.kts ]; then
  printf '%s\n' "ERROR: settings.gradle.kts not found."
  exit 1
fi

if grep -q ':bside' settings.gradle.kts; then
  printf '%s\n' "settings.gradle.kts already includes :bside"
else
  printf '%s\n' 'include(":bside")' >> settings.gradle.kts
  printf '%s\n' "settings.gradle.kts patched"
fi

git add -A
git commit -m "${MSG}" || printf '%s\n' "nothing to commit"
git pull --rebase origin main || printf '%s\n' "WARN: rebase failed. Resolve conflicts, then run: git rebase --continue"
git push origin main
