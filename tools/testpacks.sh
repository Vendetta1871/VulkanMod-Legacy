#!/bin/sh
# Feature test packs: runs each one in the fixed autotest scene with validation and compares the
# capture with testpacks/<pack>/expected.png. tools/testpacks.sh --update stores new references.
#   Needs a desktop session, as tools/autotest.sh (over ssh, export DISPLAY and friends first).
#   Prints one line per pack.
cd "$(dirname "$0")/.." || exit 1
update=$1
mkdir -p run/client/shaderpacks
scene="VULKANMOD_AUTOTEST_REPRO=192.5 80 118.5 35 45 6000"
fail=0
for dir in testpacks/*/; do
  pack=$(basename "$dir")
  rm -rf "run/client/shaderpacks/$pack"; cp -r "$dir" "run/client/shaderpacks/$pack"
  rt=0; case "$pack" in post-over-rt) rt=1;; esac
  env VULKANMOD_VALIDATION=1 VULKANMOD_RT=$rt VULKANMOD_RENDER_SCALE=1 VULKANMOD_UPSCALER=blit VULKANMOD_SHADERPACK="$pack" "$scene" \
      VULKANMOD_AUTOTEST_POOL=1 timeout 600 tools/autotest.sh > /dev/null 2>&1 < /dev/null
  rm -rf "run/client/shaderpacks/$pack"
  errors=$(grep -i "VUID\|validation layer" build/autotest.log | grep -v -i "not consumed\|duplicate_message\|never written\|VULKANMOD_VALIDATION\|validation layer enabled" | wc -l)
  shot=run/client/vm_autotest_repro.png
  if [ "$update" = --update ]; then cp "$shot" "$dir/expected.png"; fi
  diff=$(python3 -I tools/imgdiff.py "$dir/expected.png" "$shot" 2>&1)
  echo "$pack: validation=$errors $diff"
  [ "$errors" = 0 ] || fail=1
done
exit $fail
