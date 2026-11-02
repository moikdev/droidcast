# Repository control

Repository: [moikdev/droidcast](https://github.com/moikdev/droidcast). Configured on 2026-10-30 at the owner's request.

The access audit found only `moikdev` with write/admin access, no pending collaborator invitations and no deploy keys. Public visitors can read, fork and propose pull requests; public visibility does not grant write access.

Two active GitHub rulesets target `refs/heads/main`:

- [Protect main history](https://github.com/moikdev/droidcast/rules/26214070): block branch deletion and non-fast-forward updates, with no bypass actors.
- [Owner-controlled pull requests](https://github.com/moikdev/droidcast/rules/26214071): restrict updates, require pull requests, one approving review, code-owner review, dismissal of stale approvals, resolved review threads and the `local/android` status on an up-to-date branch.

The only bypass actor is user `moikdev` (ID 89525199), restricted to pull-request merges. Even the owner cannot use that bypass to push directly to main or bypass the separate history rule. This permits the owner to merge their own pull requests, because GitHub does not let authors approve their own PR. It also means the owner may explicitly bypass review/status requirements when merging; the project workflow still requires both independent audits and verified checks.

[CODEOWNERS](../.github/CODEOWNERS) assigns every file, including the ownership policy, to `@moikdev`. Code-owner review routing uses the version on the pull request's base branch, so this file becomes effective for future changes once PR #38 merges. The live restricted-update rule already limits main merges to the owner.

The protections were verified using GitHub's active rules-for-main endpoint and the returned bypass configuration. No destructive push or deletion was attempted. `local/android` reports local validation. PR #66 activated the Android GitHub Actions workflow through the existing app connection; report actual exact-head workflow outcomes separately from local/emulator checks. Both independent audits and verified local checks remain project requirements.

Tools and credentials authorized under `moikdev` act with that account's permissions. GitHub cannot distinguish a manual owner merge from an authorized tool using the same identity. Review rulesets before granting another administrator or application permission to change them.
