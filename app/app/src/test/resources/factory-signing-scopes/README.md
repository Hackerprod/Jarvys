# Frozen signing-scope fixtures

These files contain literal UTF-8 bytes emitted by the historical FactorySigningScope writer, using its own historical ResourceBudgets contract and JSON adapters, under Robolectric SDK 34 (Android JSONObject semantics). They are not serialized from the current writer. There is deliberately no trailing newline. Tests pin each SHA-256 digest.

Each writer signs the synthetic app org.example.notebook, version 1, certificate a repeated 64 times and APK hash b repeated 64 times. The plan declares documents, speech and storage. The default case uses the historical legacy budgets. The custom case sets documents.openHandles to 8 and audio.durationMs to 45000. No real identity, credentials or signed APK data are used.

Writer provenance:

- v3: f920a08b654b2f3d68df9634ca7758f0e4d5872a
  - v3-custom.json: a1ea1f9586eaf7996030f6d44dd7582aaa86bdee2a4a7976d10e9714a73097ae
  - v3-default.json: 265d242b2adaab415e7605c9c12584b89666c5143eaece08799eaf47aa39e5bc

- v4: 88d15232e02ae7128897ac42917f765e6d3ccee0
  - v4-custom.json: e9426b1847750936cf37b8f17cc8fc2d39d7c8a644508e8c1d9680c12ce949b0
  - v4-default.json: 4a268b13a8ad554635b473efd829dbd30026525841e05ba24a2165b1704429c5

- hardware: c46db06a61704ee6607220aef2465d4e32733a11
  - hardware-custom.json: 9bbf5a69bef63ae6b10a23da60fb9008633b31d55fd0d55eb6ee1da80eaea375
  - hardware-default.json: fb3fbfd2ce44f46d171a96d929b828e37d319593d6368dcaecdf938bf7c8e266

The v3 writer at f920a08 has byte-identical FactorySigningScope source to 5247f28, with the later fix for constructing recognition query actions. The earlier commit cannot initialize ManifestPlan, so it cannot produce a runnable end-to-end fixture as-is.

When evolving the resource contract, retain these exact files and digests. Add a new profile and new independent writer fixtures instead of refreshing historical expectations.
