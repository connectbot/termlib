#!/usr/bin/env bash
# Generates sitemap.xml for a single version's HTML pages.
#
# Usage: generate-version-sitemap.sh <deploy_dir> <subdir> <base_url>
#   deploy_dir  Root of the gh-pages deployment tree (e.g. gh-pages-deploy)
#   subdir      Version subdirectory name (e.g. main or 0.0.24)
#   base_url    GitHub Pages base URL (e.g. https://connectbot.github.io/termlib)

set -euo pipefail

DEPLOY_DIR="$1"
SUBDIR="$2"
BASE_URL="$3"

{
    echo '<?xml version="1.0" encoding="UTF-8"?>'
    echo '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'
    find "${DEPLOY_DIR}/${SUBDIR}" -name "*.html" \
        ! -name "navigation.html" \
        | sort \
        | while read -r f; do
            rel="${f#${DEPLOY_DIR}/}"
            encoded="$(python3 -c "import urllib.parse, sys; print(urllib.parse.quote(sys.argv[1], safe='/:'))" "${rel}")"
            echo "  <url><loc>${BASE_URL}/${encoded}</loc></url>"
          done
    echo '</urlset>'
} > "${DEPLOY_DIR}/${SUBDIR}/sitemap.xml"
