# GitHub connector (UX20)

## Authentication and lifecycle

Jarvys uses the public-client GitHub Device Flow and the official hosted MCP endpoint, `https://api.githubcopilot.com/mcp/`. Each connector-detail owner has exactly one cancellable authorization attempt. Recomposition keeps that owner; cancellation, leaving the detail, replacement, and completion invalidate old callbacks. Code/token HTTP responses are bounded, and cancellation closes active I/O. The UI distinguishes a disabled OAuth App, an invalid client, expiry, denial, cancellation and network failure.

The initial request remains `read:user offline_access`; repository access is an explicit optional `repo` choice. Workflow-file access is a separate explicit `workflow` choice and requires repository access. Required scopes from a challenge are shown for review; they are never selected automatically. The public OAuth client ID is not a secret. Its ownership and the external GitHub App configuration (including Device Flow enablement) cannot be verified by these host tests. No replacement application, account grant, token or client secret is created by this implementation.

Access and refresh tokens, scopes, expiry and authorization epoch are kept in the encrypted vault and bound to the exact endpoint/client. Refresh preserves the epoch and optional omitted metadata; a new explicit grant rotates it. Expired refresh grants require renewed consent. A scope challenge does not automatically expand access or replay a pending action. Local disconnect erases local credentials; GitHub grant revocation is a separate user action in GitHub settings.

## Capabilities and authorization

Only reviewed capability groups are sent in `X-MCP-Toolsets`: context, repositories, issues, pull requests and Discussions; Actions is optional. No `all` or insiders configuration is generated. MCP discovery is paginated and bounded; newly discovered tools stay disabled. Existing choices are remembered, with at most 40 tools exposed per server. Remote annotations and descriptions remain untrusted data.

Reads use a reviewed name/method allowlist. GitHub writes require a frozen repository and method-specific review, even if an older general Allow preference exists. The review includes target, branch/number/SHA, changed paths, content previews and explicit truncation/digests. Choosing a capability does not authorize an external action or grant GitHub account scopes. Permission, configuration, connection generation and account epoch are checked again at dispatch and authenticated retry boundaries.

Ordinary branch, file, issue, PR, review and Discussion-comment operations use the official MCP tools. File content follows the current MCP literal UTF-8 contract, not the older Base64 input convention. A draft PR is the default when omitted. Merge requires a reviewed head SHA; updating a PR branch requires its expected head SHA. Workflow changes, destructive methods, reviews and merges remain individually reviewed. Branch protection or organization policy is never bypassed.

## Narrow native API gaps

The fixed GitHub GraphQL bridge adds only:

- `jarvys_create_discussion`: validates the repository and category before creation
- `jarvys_update_discussion`: validates repository, discussion and observed `updatedAt`; the preflight is not atomic and a concurrent edit can still race
- `jarvys_commit_files`: bounded UTF-8 additions/deletions using `createCommitOnBranch` and required `expectedHeadOid`; updates are atomic with respect to that head and never force-push
- `jarvys_commit_checks`: reads a specific commit OID and paginated check/status contexts, never silently substituting a moving branch or PR head; no checks is not a success verdict

No arbitrary GraphQL, URLs or credentials are accepted as tool arguments. The native path verifies returned repository/commit/discussion identity and URLs. Node-only MCP comment/review-thread mutations have a native read-only target-binding preflight. Credential-like content and unsafe paths are rejected by both native and direct-file paths; this is a bounded guard, not a guarantee that every possible secret encoding can be detected.

## Failures and replay

Authentication failures allow at most one refresh; permission/scope failures require user action. Safe reads have bounded rate-limit/transient retries. Mutations have no transient retry. Once a mutating MCP POST is accepted, response loss or failed SSE resumption is ambiguous, including a resumed GET returning 401. Fixed-length POST bodies avoid transparent buffered-body replay.

A lazy persistent journal stores only intent digests before dispatch. An ambiguous write, malformed result or uncertain GraphQL execution remains blocked across restart and account changes. A matching retry must be reconciled manually against GitHub; there is no automatic journal reset. A definite rejection can release its reservation. Host fixtures perform no real remote PRs, issues, comments, commits, OAuth grants or repository changes.

## Validation boundary

The stage uses protocol, fake-vault, Compose lifecycle, bridge and integration tests. A host test is not proof of real OAuth App configuration, organization access, consent, Android networking or physical-device behavior. Development APKs keep the original package/name and remain unsigned; the approved original identity is used only for the final consolidated delivery.

## Primary references

- [Official MCP server](https://github.com/github/github-mcp-server) and [remote configuration](https://github.com/github/github-mcp-server/blob/main/docs/remote-server.md)
- [OAuth Device Flow and refresh](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps)
- [MCP scope behavior](https://github.com/github/github-mcp-server/blob/main/docs/scope-filtering.md)
- [GitHub commit GraphQL contract](https://docs.github.com/en/graphql/reference/commits) and [CommittableBranch input](https://docs.github.com/en/graphql/reference/git#committablebranch)
- [Discussions GraphQL contract](https://docs.github.com/en/graphql/reference/discussions)

The Kotlin bridge is an original implementation against documented APIs; no Go or JavaScript runtime/library was copied into the Android application.
