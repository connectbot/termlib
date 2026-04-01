#!/usr/bin/env bash
# Regenerates the root sitemap_index.xml from all version sitemap.xml files.
#
# Usage: generate-sitemap-index.sh <deploy_dir> <base_url>
#   deploy_dir  Root of the gh-pages deployment tree (e.g. gh-pages-deploy)
#   base_url    GitHub Pages base URL (e.g. https://connectbot.github.io/termlib)

set -euo pipefail

DEPLOY_DIR="$1"
BASE_URL="$2"

{
    echo '<?xml version="1.0" encoding="UTF-8"?>'
    echo '<sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'
    find "${DEPLOY_DIR}" -mindepth 2 -maxdepth 2 -name "sitemap.xml" \
        | sort \
        | while read -r f; do
            rel="${f#${DEPLOY_DIR}/}"
            echo "  <sitemap><loc>${BASE_URL}/${rel}</loc></sitemap>"
          done
    echo '</sitemapindex>'
} > "${DEPLOY_DIR}/sitemap_index.xml"
