# LGPL editor source and replacement

NovaScale's own source is Apache-2.0. The editor libraries below retain their
upstream LGPL licenses; they have not been relicensed. NovaScale currently uses
their unmodified Maven binaries. Any future library modifications must retain
the applicable LGPL terms and be published with matching source, which may be a
separate public fork. Application-level adapters remain in this repository.

| Component | Source and release inputs |
| --- | --- |
| Sora Editor and its Tree-sitter adapter, LGPL-2.1-or-later | [Pinned source](https://github.com/Rosemoe/sora-editor/tree/1e199e11dcbf9e6526cdcbdf1bdb25b74140496a), version 0.24.4; `../sora-editor/README.md` records AAR hashes |
| Android Tree-sitter binding and LGPL grammars, LGPL-2.1 | [Pinned source](https://github.com/AndroidIDEOfficial/android-tree-sitter/tree/797150222de664181e31be53e9a5b253056ba9b0), version 4.3.2; `../android-tree-sitter/README.md` records the Tree-sitter/grammar submodule commits, AAR hashes and query changes |

`../LGPL-2.1.txt` contains the license and is included in the app's third-party
notice document. Recipients may modify these components and rebuild/relink the
application for their own use, including reverse engineering to debug those
modifications. NovaScale adds no restriction on that activity. Other bundled
components, including Google's unmodified Play Billing SDK, retain their terms.

## Rebuilding with a modified library

1. Check out the corresponding NovaScale release tag and the exact library
   sources above, including the pinned Tree-sitter grammar submodules. Build
   the modified library with its upstream build recipe.
2. Publish the resulting AAR/JAR and POM into a local Maven directory, retaining
   the original group/artifact/version coordinates. Preserve all transitive
   dependencies in the POM. This repository's optional
   `scripts/local-lgpl.init.gradle` directs only the provided editor coordinates
   to that directory; all other modules retain their normal repositories.
3. Rebuild either application flavor, for example:

   ```sh
   ./gradlew -I scripts/local-lgpl.init.gradle \
     -PNOVASCALE_LGPL_MAVEN=/absolute/path/to/local-maven \
     --refresh-dependencies :app:assembleCommunityDebug
   ```

   Use `:app:assemblePlayDebug` to retain the unmodified Play SDK. Live Play
   checkout needs the documented Play setup; it is not required to replace or
   exercise the editor. No NovaScale private signing key, account or secret is
   required to build, sign with your own key and run a modified application.
4. Release builds also accept the same substitution. Follow the public release
   build/signing instructions, and provide the modified library sources under
   their LGPL license with your resulting distribution. An upstream-compatible
   library replacement does not require changes to NovaScale's Rust SFTP core.

## Distribution checklist

For each distributed APK/AAB, keep its immutable application source tag, complete
matching library source (including patches and exact submodules), license text,
dependency artifacts/coordinates and rebuild instructions available together.
Provide the corresponding source bundle through the same release download
location; an upstream project homepage alone is not the source bundle. Include
the LGPL grammar source for the native libraries as well as the Java/Kotlin
editor, and retain notices for the MIT grammar components. Keep the source
screen's release link accurate. Historical GPL NovaScale tags keep their original
terms and source obligations.
