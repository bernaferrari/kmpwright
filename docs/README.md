# Documentation

Start with the [KMPwright README](../README.md) for dependencies and a shared
workflow. The [full documentation](../website/content/docs/index.mdx) covers APIs,
agent commands and platform behavior.

| Topic | Guide |
| --- | --- |
| Browser workflows and advanced features | [Feature guide](browser-features.md) |
| Browser pooling and leases | [Browser farm](browser-farm.md) |
| Contributor setup and checks | [Development](development.md) |
| Tested toolchain | [Dependencies](dependencies.md) |
| Maven and Swift artifact verification | [Releasing](releasing.md) |
| Platform limits | [Capability matrix](../website/content/docs/platforms/capabilities.mdx) |

## Upstream compatibility

The project compares against a pinned agent-browser revision. These records
explain ancestry and coverage; dated validation checkpoints are historical
evidence rather than a promise about every current backend.

- [Comparison and change review](compatibility/upstream-review.md)
- [Machine-readable API mapping](compatibility/agent-browser-compatibility.json)
- [Validation checkpoint](compatibility/validation-checkpoint.json)
- [Implementation notes](compatibility/parity-work.md) and [reliability review](compatibility/reliability-review.md)

Dependency licenses and provenance are kept in [licenses/](licenses/). See the
[third-party notices](../THIRD_PARTY_NOTICES.md) for attribution.
