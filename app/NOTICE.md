# Third-party notices

## Letta Code memory format and prompt attribution

Jarvys' on-device memory implementation adapts the MemFS v2 layout, validation rules, and memory-projection prompt structure from [Letta Code](https://github.com/letta-ai/letta-code), commit `e961a2b3cf430059a27012a2472a8767b09827be`, licensed under Apache-2.0. The implementation and Spanish-language guidance have been adapted for Jarvys' on-device storage, existing file tools, Android trust boundaries, and revision journal; these are modified files, not verbatim Letta Code source. Relevant source concepts: `src/agent/memory-format.ts`, `src/memory-constraints.ts`, `src/backend/local/initial-memory.ts`, `src/backend/local/system-prompt-compilation.ts`, `src/agent/prompts/letta_root_memfs.md`.

Jarvys files containing this adaptation: `app/src/main/java/com/jarvys/agent/MemoryConstants.java`, `MemoryStore.java`, `WorkspaceStore.java`, `WorkspaceTools.java`, `CorePromptBudget.java`, and `CoreAgentRuntime.java`.

The Apache-2.0 license text is included at `LETTACODE-APACHE-2.0.txt`. Apache License §4(b) requires modified files to carry prominent notices stating that they were changed; §4(c) requires retaining applicable source copyright, patent, trademark, and attribution notices; §4(d) requires reproducing applicable upstream NOTICE attributions if the upstream Work includes a NOTICE file. Letta Code's root distribution has a LICENSE but no root NOTICE file. Letta's product names, logos, wordmarks, images, and ASCII art are excluded from that Apache grant; Jarvys does not reuse those brand assets.

`app/src/main/java/com/jarvys/agent/LucideIcons.kt` is generated from the official SVG paths in [Lucide](https://github.com/lucide-icons/lucide), tag `0.575.0`, commit `10f6d5f8c6d9a1dae5e0ddec3925456ea7eca7bd`. The generated ImageVectors use a 24×24 viewport, 2px strokes, round caps and joins, and app content color tinting.

Lucide SVGs included in the generated Kotlin source:

`accessibility`, `arrow-left`, `arrow-up`, `bell`, `bot`, `boxes`, `brain`, `calendar`, `camera`, `chevron-down`, `chevron-right`, `chevron-up`, `circle`, `circle-check`, `circle-stop`, `circle-x`, `clipboard-paste`, `clock`, `contact-round`, `copy`, `download`, `earth`, `ellipsis`, `eye`, `file-text`, `file-up`, `git-fork`, `heart`, `history`, `house`, `image`, `info`, `languages`, `layers-2`, `map-pin`, `menu`, `message-circle-plus`, `message-square`, `network`, `paperclip`, `plus`, `refresh-cw`, `rotate-ccw`, `save`, `search`, `settings`, `shield`, `smartphone`, `sparkles`, `sun-moon`, `terminal`, `trash-2`, `volume-2`, `wand-sparkles`, and `x`.

The upstream ISC license and its Feather-derived MIT notice are included at `LUCIDE-ISC-LICENSE.txt`. The ISC grant states:

> ISC License
>
> Copyright (c) for portions of Lucide are held by Cole Bemis 2013-2026 as part of Feather (MIT). All other copyright (c) for Lucide are held by Lucide Contributors 2026.
>
> Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby granted, provided that the above copyright notice and this permission notice appear in all copies.
>
> THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.

Markdown rendering uses `com.mikepenz:multiplatform-markdown-renderer` and `com.mikepenz:multiplatform-markdown-renderer-m3` version 0.37.0, licensed under Apache-2.0. It parses and renders locally; Jarvys adds no Markdown service, network requests, or telemetry.

JVM unit tests use `org.json:json` version 20240303 to exercise the same JSON connector schemas and payloads without Android framework stubs. This is test-only and is not packaged in the APK. It is distributed under the [JSON License](https://github.com/stleary/JSON-java/blob/20240303/LICENSE):

> Copyright (c) 2002 JSON.org
>
> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
>
> The Software shall be used for Good, not Evil.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

`app/src/main/java/com/jarvys/agent/BrandIcons.kt` is generated from official SVG paths and brand colors in Simple Icons 16.33.0 ([project](https://github.com/simple-icons/simple-icons)), distributed under CC0-1.0. Brand logos, names, and colors remain identifiers of their respective owners and are used nominatively only to identify the corresponding service available for connection; this use does not imply endorsement. Canva has no Simple Icons mark in this catalog and uses only a colored initial-letter fallback. Generation script: `generate_brand_icons_compose.py`.

Only the Full flavor uses `com.google.android.gms:play-services-auth:22.0.0` for Google Identity `AuthorizationClient` (`Gmail`/`Drive`). Its Maven POM identifies the [Android Software Development Kit License](https://developer.android.com/studio/terms.html). The dependency is declared as `fullImplementation`; Play does not package it.

## Unicode emoji data

The message reaction validator derives its Unicode 16.0 emoji forms from https://unicode.org/Public/emoji/16.0/emoji-test.txt. Copyright © 2024 Unicode, Inc. Distributed under Unicode License v3; see UNICODE-LICENSE.txt. No Unicode fonts are bundled.

## APK factory runtime and signing

The factory runtime uses `androidx.webkit:webkit:1.14.0` and its AndroidX dependencies, licensed under Apache-2.0. The runtime carries the Apache license and attribution in `apk-runtime/src/main/assets/factory-licenses.txt`; generated APKs preserve that asset. The local signer uses Google's AOSP `com.android.tools.build:apksig:8.13.2`, licensed under Apache-2.0; its distributed LICENSE is retained as `app/src/main/assets/apk_factory/apksig-LICENSE.txt`.

The original `TemplateApk.java` implementation reads the binary structures documented in AOSP `libs/androidfw/include/androidfw/ResourceTypes.h` (Apache-2.0). It does not copy a third-party APK editing implementation. Android platform SDK tools are build-time only and are not shipped in generated applications. No signing keys are bundled.

## Local Rive file serializer (UX40)

The original bounded `BotMascotSceneCompiler` follows the public Rive `.riv`
format and the MIT-licensed runtime's generated type/property definitions and
small binary-writer test helper. Copyright (c) 2020 Rive. The MIT notice is retained
in `app/src/main/assets/licenses/rive-runtime-MIT.txt`. Reference source:
https://github.com/rive-app/rive-runtime/tree/6f3510dcc545bc8b2a78f1004a06929d17cd022b

This serializer is not Rive CLI, an RML compiler or the Rive editor. No CLI/editor
binary or private source is included. Its staged native-core validation does not
establish Android rendering or enable animated mascots by itself.
