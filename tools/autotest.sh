#!/bin/sh
# Automated rendering test (mirrors the tacz-legacy autotest): launches the client,
# joins a generated creative world, saves Vulkan swapchain captures as
# run/client/vm_autotest_*.png and quits.
#   tools/autotest.sh                       - default run
#   VULKANMOD_AUTOTEST_WARMUP=240 tools/autotest.sh
#   VULKANMOD_AUTOTEST_STAY=1 tools/autotest.sh   - keep the game open after captures
cd "$(dirname "$0")/.." || exit 1
rm -f run/client/vm_autotest_*.png
VULKANMOD_AUTOTEST=1 ./gradlew runClient "$@" 2>&1 | tee /tmp/opencode/vm-autotest.log
echo "--- captures ---"
ls -la run/client/vm_autotest_*.png 2>/dev/null || echo "none produced"
