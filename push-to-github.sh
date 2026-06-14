#!/bin/bash
# Run this from the Replit Shell to push all commits to GitHub:
#   bash push-to-github.sh
#
# Requires the Git secret to be set in Replit Secrets (lock icon → Secrets).
# The CI workflow (.github/workflows/build.yml) will then build the debug APK.

set -e
REMOTE="https://${Git}@github.com/kumarclaude4-hash/DuoShield-.git"
git push "$REMOTE" main
echo "Pushed to GitHub. CI build triggered."
