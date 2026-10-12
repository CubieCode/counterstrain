# Vendored libraries

Third-party code the content editor serves to the browser. It is a dev tool:
nothing here ships in the mod or the pack, and nothing loads from a CDN at run
time.

| File | Library | Version | Licence | Source |
| --- | --- | --- | --- | --- |
| `deepslate-0.27.2.umd.js` | [deepslate](https://github.com/misode/deepslate) (Misode) | 0.27.2 | MIT, `deepslate-LICENSE.txt` | `dist/deepslate.umd.cjs` from the npm tarball `deepslate-0.27.2.tgz`, unmodified |

SHA-256 of `deepslate-0.27.2.umd.js`: `b8ea9489e8877615bac348b2d6911b06456e363b86baa5b22096cd047c914f38`.

To update: download the new tarball from the npm registry, copy `dist/deepslate.umd.cjs`
and `LICENSE` here under the new version's name, update this table and the script tag
in `../static/index.html`, and check the 3D previews still render.
