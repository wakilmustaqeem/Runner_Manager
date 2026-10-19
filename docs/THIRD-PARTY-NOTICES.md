# Third-party notices and distribution checklist

## DroidRunner

- Upstream project: https://github.com/m96-chan/DroidRunner
- License: GNU General Public License v2.0 only (GPL-2.0-only)
- License text: [COPYING.DroidRunner](../COPYING.DroidRunner)
- This branch has permission to begin GPL-compliant integration planning. It does **not** claim that upstream source has already been copied or that the app currently contains the upstream runtime.
- Before shipping any adapted DroidRunner code or a combined APK, retain the upstream copyright notices, mark every modified upstream file with a prominent modification notice and date, provide the complete corresponding source and build/install scripts as required by GPLv2, and include the license in the distributed product.
- Before shipping any runtime bundle, inventory each component and version and satisfy its own license/source requirements. DroidRunner's upstream documentation specifically identifies PRoot (GPL-2.0), talloc (LGPL-3.0), the GitHub Actions Runner (MIT), and Ubuntu rootfs packages with their own licenses. Do not assume the DroidRunner license covers every bundled component.
- Keep an exact record of the upstream commit/tag and corresponding source archive for every release. Do not redistribute a runtime bundle until its source offer and notices are complete.

## Apache Commons Compress

- Dependency: `org.apache.commons:commons-compress:1.28.0`.
- License: Apache License 2.0. The project uses the library to decode TAR.GZ streams; FM-specific path validation, extraction limits, symlink handling, and cleanup policy are implemented in `RuntimeTarGzExtractor.kt`.
- Include the applicable Apache-2.0 license and notices in any distributed APK, and review transitive dependency notices during release packaging.

## Current status

This file is a compliance gate, not legal advice and not proof that a distributable build is compliant. No upstream DroidRunner implementation has been copied by this change. The Android app still has no verified runner process adapter.
