#!/data/data/com.termux/files/usr/bin/bash
set -u
cd "$(dirname "$0")" || exit 1

MSG="${1:-v1.4 move human module from bside to human}"
BASE="${2:-a1be7f0}"

if [ ! -d .git ]; then
  printf '%s\n' "ERROR: .git not found. Run inside the SugorokuApp repo."
  exit 1
fi
if [ ! -d app ]; then
  printf '%s\n' "ERROR: app/ not found. Run inside the SugorokuApp repo."
  exit 1
fi
if ! git cat-file -e "${BASE}^{commit}" 2>/dev/null; then
  printf '%s\n' "ERROR: base commit not found: ${BASE}"
  printf '%s\n' "Pass the commit of the A-side B-module as the 2nd argument."
  exit 1
fi

restore_or_remove() {
  if git cat-file -e "${BASE}:$1" 2>/dev/null; then
    git checkout "${BASE}" -- "$1"
    printf '%s\n' "restored: $1"
  else
    git rm -q -f --ignore-unmatch "$1"
    printf '%s\n' "removed : $1"
  fi
}

restore_or_remove "bside/build.gradle.kts"
restore_or_remove "bside/src/main/AndroidManifest.xml"
restore_or_remove "bside/src/main/assets/charas_human.json"
restore_or_remove "bside/src/main/assets/events_human.json"
restore_or_remove "bside/src/main/kotlin/com/appathy/sugoroku/human/MainActivity.kt"
restore_or_remove "bside/src/main/res/drawable/ic_launcher.png"
restore_or_remove "bside/src/main/res/raw/intro_03.mp4"

for n in 01 02 03 04 05 06 07 08 09 10 11 12; do
  restore_or_remove "bside/src/main/res/drawable/chara_kid${n}.png"
  restore_or_remove "bside/src/main/res/drawable/chara_jhs${n}.png"
done

git rm -q -f --ignore-unmatch "tools/validate_bside.py"
git rm -q -f --ignore-unmatch "BSIDE_HANDOFF.md"
git rm -q -f --ignore-unmatch ".github/workflows/build_bside.yml"
git rm -q -f --ignore-unmatch "deploy_bside.sh"

find bside -type d -empty -delete 2>/dev/null

if grep -q ':human' settings.gradle.kts; then
  printf '%s\n' "settings.gradle.kts already includes :human"
else
  printf '%s\n' 'include(":human")' >> settings.gradle.kts
  printf '%s\n' "settings.gradle.kts patched: include(\":human\")"
fi

if grep -q ':bside' settings.gradle.kts; then
  printf '%s\n' "settings.gradle.kts still includes :bside (A-side module kept)"
fi

git add -A
git commit -m "${MSG}" || printf '%s\n' "nothing to commit"
git pull --rebase origin main || printf '%s\n' "WARN: rebase failed. Resolve conflicts, then run: git rebase --continue"
git push origin main
