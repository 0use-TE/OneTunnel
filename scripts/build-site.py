#!/usr/bin/env python3
"""Build the public Pages folder using only the Python standard library."""
import argparse
from html import escape
from html.parser import HTMLParser
from pathlib import Path
import re
import shutil
from urllib.parse import unquote, urlsplit
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "http://schemas.android.com/apk/res/android"


class PageCheck(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.ids = set()
        self.references = []
        self.headings = 0

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if "id" in attrs:
            if attrs["id"] in self.ids:
                raise ValueError(f"Duplicate HTML id: {attrs['id']}")
            self.ids.add(attrs["id"])
        if tag == "h1":
            self.headings += 1
        if tag == "img" and "alt" not in attrs:
            raise ValueError("Images need an explicit alt attribute")
        for key in ("src", "href"):
            if key in attrs:
                self.references.append(attrs[key])

    def check(self, folder):
        if self.headings != 1:
            raise ValueError("The landing page needs exactly one h1")
        for ref in self.references:
            url = urlsplit(ref)
            if url.scheme or url.netloc:
                if url.scheme not in ("https", "http"):
                    raise ValueError(f"Unsupported link scheme: {url.scheme}")
                continue
            if url.path:
                target = (folder / unquote(url.path)).resolve()
                if not target.is_relative_to(folder.resolve()) or not target.is_file():
                    raise ValueError(f"Missing local asset: {url.path}")
            elif url.fragment and unquote(url.fragment) not in self.ids:
                raise ValueError(f"Missing anchor: {url.fragment}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--site-url", default="https://0use-te.github.io/OneTunnel/")
    args = parser.parse_args()
    site_url = args.site_url.rstrip("/") + "/"
    url = urlsplit(site_url)
    if url.scheme not in ("http", "https") or not url.hostname or url.username or url.password or url.query or url.fragment:
        parser.error("--site-url must be a public HTTP(S) URL without credentials, query or fragment")
    output = ROOT / "_site"
    (output / "assets").mkdir(parents=True, exist_ok=True)
    source = (ROOT / "website/index.html").read_text(encoding="utf-8")
    gradle = (ROOT / "client/app/build.gradle.kts").read_text(encoding="utf-8")
    sdk = re.search(r"minSdk\s*=\s*(\d+)", gradle)
    if not sdk or sdk.group(1) != "26":
        raise ValueError("Update the Android version label when minSdk changes")
    page = source.replace("{{SITE_URL}}", escape(site_url, quote=True)).replace("{{ANDROID_MIN}}", "8")
    if "{{" in page:
        raise ValueError("Unresolved website template value")
    (output / "index.html").write_text(page, encoding="utf-8")
    for name in ("styles.css", "app.js"):
        shutil.copyfile(ROOT / "website" / name, output / name)
    # Reuse the current Android logo paths, with the brand blue instead of system tint.
    vector = ET.parse(ROOT / "client/app/src/main/res/drawable/ic_status.xml").getroot()
    group = vector.find("group")
    path = group.find("path")
    get = lambda node, name: node.attrib[f"{{{ANDROID}}}{name}"]
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {get(vector, "viewportWidth")} {get(vector, "viewportHeight")}">'
           f'<g transform="translate({get(group, "translateX")} {get(group, "translateY")})">'
           f'<path fill="#3275d0" fill-rule="evenodd" d="{escape(get(path, "pathData"), quote=True)}"/></g></svg>\n')
    (output / "assets/logo.svg").write_text(svg, encoding="utf-8")
    (output / ".nojekyll").touch()
    check = PageCheck()
    check.feed(page)
    check.check(output)
    print(f"Built and checked _site for {site_url}")


if __name__ == "__main__":
    main()
