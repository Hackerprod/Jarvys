# Optional adaptive launcher icons

Factory keeps `icon` as the required legacy PNG or opaque geometric JSON path. Existing project specs, runtime configuration, manifest receipt profiles, and icon rendering defaults are unchanged when `adaptiveIcon` is absent.

```json
{
  "icon": "icon.json",
  "adaptiveIcon": {
    "foreground": "foreground.json",
    "background": "#102030",
    "insetPercent": 20
  }
}
```

`adaptiveIcon` is optional artwork data, not a native capability. `foreground` is a bounded relative project PNG or geometric JSON path. `background` is an opaque `#RRGGBB` color. Optional `insetPercent` is an integer from 0 to 40; its default is 0, preserving the full authored canvas. Padding applies equally to each side; non-square PNGs are fit without distortion or cropping. Already padded PNG artwork should normally use 0.

Foreground geometric JSON contains exactly `schemaVersion: 1` and `shapes`, using the same 192 × 192 coordinates, opaque fills, and bounded circle/rectangle/polygon primitives as legacy vector artwork. It has no background field: outside the shapes remains transparent. No SVG, executable content, image URL, arbitrary XML, resource number, or native code is accepted.

```json
{"schemaVersion":1,"shapes":[{"type":"circle","cx":96,"cy":96,"r":68,"fill":"#F3B552"}]}
```

The resulting 192px foreground canvas corresponds to Android's full 108dp adaptive layer. Android launchers choose the mask and may animate or crop edges. Keep essential artwork near the center; 20% padding places a full-square source within about 64.8dp, near the customary central 66dp safe region. Factory does not impose a mask, rounded background, or hidden inset; developers control composition through their art and `insetPercent`.

The runtime template contains a genuine precompiled `mipmap-anydpi-v26/factory_adaptive_icon.xml` with fixed foreground/background drawable references, a `mipmap-nodpi` legacy fallback, and two bounded PNG layer slots. Runtime packaging only selects the compiled resource ID and replaces raster bytes. API 24–25 use the explicitly supplied legacy icon; API 26+ use the adaptive layers. No per-app Java, XML compilation, Gradle, AAPT, DEX mutation, or native toolchain is involved.

Packaging verifies exact own-package symbol IDs and the entire resource table envelope; the icon has exactly nodpi/default PNG and anydpi/API26 XML configurations. It rejects extra qualifiers, aliases, wrong IDs, changed layer order/types, unexpected XML nodes/attributes, missing assets, malformed/non-192px layers, a background that is not one opaque color, and a legacy fallback differing from `icon`. APK auditing reports the verified icon/API26/foreground/background bindings. Both source artwork and normalized layer hashes enter provenance/receipts only when adaptive artwork is requested. Existing historical receipt verification remains opt-out.

## Verification status

Pure Java policy, synthetic binary XML, and synthetic resource-table tests are designed to run without an Android build. Integrated template and pixel-rendering tests require the normal final build/test suite. Synthetic success alone is not proof of AAPT compilation or launcher rendering; physical launcher mask/animation validation remains a separate device check.
