"use strict";
(async () => {
    const script = document.querySelector("script[data-docs-root]");
    const select = document.getElementById("docs-version");
    if (!script || !select) return;
    const root = new URL(script.dataset.docsRoot, location.href);
    const current = script.dataset.docsVersion;
    select.addEventListener("change", async () => {
        const chosen = select.value;
        select.disabled = true;
        const versionRoot = new URL(encodeURIComponent(chosen) + "/", root);
        let target = new URL("index.html", versionRoot);
        try {
            const response = await fetch(new URL("versions.json", root));
            if (!response.ok) throw new Error("Version index unavailable");
            const versions = await response.json();
            const prefix = new URL(encodeURIComponent(current) + "/", root).pathname;
            const relative = decodeURIComponent(location.pathname.slice(prefix.length)) || "index.html";
            if (location.pathname.startsWith(prefix) && versions.pages[chosen].includes(relative)) {
                target = new URL(relative.split("/").map(encodeURIComponent).join("/"), versionRoot);
                if (location.hash) {
                    const page = await fetch(target);
                    if (page.ok) {
                        const doc = new DOMParser().parseFromString(await page.text(), "text/html");
                        const anchor = decodeURIComponent(location.hash.slice(1));
                        if (doc.getElementById(anchor) || Array.from(doc.getElementsByName(anchor)).length) {
                            target.hash = location.hash;
                        }
                    }
                }
            }
        } catch (_) {
            // The selected version's homepage remains usable without the index.
        }
        location.assign(target.href);
    });
})();
