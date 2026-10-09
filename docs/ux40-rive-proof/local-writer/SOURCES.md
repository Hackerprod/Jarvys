# Public sources and attribution

The serializer is an original, deliberately small implementation of the published
.riv format. It is not a port of Rive CLI, an RML parser, or an editor exporter.
No private compiler code or disassembly was used. The two scene designs and their
keyframes were authored for this proof.

Primary format documentation:
https://rive.app/docs/runtimes/advanced-topic/format

The public Rive runtime was inspected at commit
`6f3510dcc545bc8b2a78f1004a06929d17cd022b`. Relevant MIT-licensed references:

- [Small binary writer used in Rive's own tests](https://github.com/rive-app/rive-runtime/blob/6f3510dcc545bc8b2a78f1004a06929d17cd022b/tests/include/riv_bytes.hpp)
- [Runtime header and ToC reader](https://github.com/rive-app/rive-runtime/blob/6f3510dcc545bc8b2a78f1004a06929d17cd022b/include/rive/runtime_header.hpp)
- [Primitive binary reader](https://github.com/rive-app/rive-runtime/blob/6f3510dcc545bc8b2a78f1004a06929d17cd022b/src/core/binary_reader.cpp)
- [Generated type and property definitions](https://github.com/rive-app/rive-runtime/tree/6f3510dcc545bc8b2a78f1004a06929d17cd022b/include/rive/generated)
- [Comparison operators](https://github.com/rive-app/rive-runtime/blob/6f3510dcc545bc8b2a78f1004a06929d17cd022b/include/rive/animation/transition_condition_op.hpp)
- [State-machine transition behavior](https://github.com/rive-app/rive-runtime/blob/6f3510dcc545bc8b2a78f1004a06929d17cd022b/src/animation/state_machine_instance.cpp)
- [Rive runtime MIT license](https://github.com/rive-app/rive-runtime/blob/6f3510dcc545bc8b2a78f1004a06929d17cd022b/LICENSE)

The Rive copyright and MIT notice are preserved in `LICENSE-RIVE-MIT.txt`.
This notice does not confer a license to the separate CLI/editor. This proof's
generator does not incorporate or redistribute either of those tools.

Validation uses the official [`@rive-app/canvas-advanced` 2.44.1](https://www.npmjs.com/package/@rive-app/canvas-advanced/v/2.44.1)
runtime. Pixel rendering uses its Canvas2D backend with
[`@napi-rs/canvas` 1.0.10](https://github.com/Brooooooklyn/canvas) as a host-only
Canvas implementation. Those downloaded packages and native executables are not
part of this source backup. Preserve their respective notices if distributing
them in a future product.

The published writer fixes output to format **7.4** and includes a ToC. Its
existence does not establish compatibility with every Rive runtime version.
