# 0002 — One active platform project per IDE window

- Status: Accepted
- Date: 2026-09-23

## Context

The platform UI works on one open project at a time, and that project is the scope of its MCP server and hosted LSP.
A JetBrains window could show every project of the account side by side, which leaves no answer to "which project do
the IDE agents in this window work on?".

## Decision

Each IDE window has at most one platform project (`ActiveProject`): the one its folder is a copy of
([ADR 0003](0003-local-project-folder.md)). The folder's `.datamimic/workspace.json` names it, so it survives
restarts and needs no other setting. *Open Project* on another project opens that project's folder in its own
window; a window never switches projects.

```mermaid
flowchart LR
  O["Open Project downloads the folder"] --> J["Write Junie token and routing before IDE open"]
  J --> A["Activate: sync, locks, live updates"]
  A --> T["Project token, 24 h"]
  T --> C["Register Claude Code"]
  X["Window closes"] --> U["Unregister token-based agents"]
  U --> D["Deactivate: locks back, token revoked if no window uses the project"]
```

- Files, locks, live updates, generation (including its local Run Configurations) and agents of a window belong to its
  project. A window whose folder belongs to another platform than the one signed in stays unconnected and says so.
- Header-based IDE agents reach the platform MCP server with a project access token: the MCP endpoint does not accept
  the session cookie ([ADR 0001](0001-platform-session-auth.md)). One token per IDE process and project is shared by
  its windows. A replacement is created and published four hours before expiry; the prior token is left to expire so
  another open window is never cut off. Plugin-owned tokens are revoked when the last window leaves the project.
- Agents get their own client binding, never the IDE's, so their file locks are separate from the editor's.
- Registration, per agent:
  - **Claude Code:** `claude mcp add --scope local` in the project directory. The token stays in the user's Claude
    configuration, not in the repository.
  - **Junie:** before the first IDE open, write the project URL and token headers as the `datamimic-platform` entry
    in `.junie/mcp/mcp.json`. In a project without user guidance, publish the routing as `.junie/AGENTS.md` for the
    supported Junie runtime. With combined root, playbook, or rule guidance, publish `.junie/rules/datamimic.md` instead.
    Exclusive and legacy user guidance is left unchanged and requires manual inclusion. The guidance combines
    Junie-only routing and generation limits with the generic workflow projected from Platform
    `MCP_SERVER_INSTRUCTIONS`; the packaged projection is checked with `scripts/sync-agent-guidance.sh`.
  - **AI Assistant:** the default-off project setting writes the managed entry to `.ai/mcp/mcp.json`, removes only
    that exact entry on disable or disconnect, and leaves **Automatically enable new and changed MCP servers** and
    **Pass custom MCP servers** to the user.
- Closing the window or signing out removes token-based registrations, including Junie's managed token entry, before
  revocation. The routing rule remains local. An expired plugin session removes token-based registrations; local edits
  stay in the folder and upload after signing in again. Closing a window disconnects synchronously (at most 10 s).
- If a replacement token cannot be created, agents retain the current valid token and the plugin reports the failure.

## Consequences

- Agent sessions that are already running pick up a new or renewed token only after a restart. The previous token has
  less than four hours left when the replacement is published, so ongoing sessions are not cut off by renewal.
- While `claude mcp add` runs, the token is visible in the process list to other users of the same machine.
- A crashed IDE leaves registrations behind; their token expires within 24 hours and the next activation overwrites them.
- AI Assistant requires **Automatically enable new and changed MCP servers** and **Pass custom MCP servers** before a
  chat uses the published MCP.
  Junie's current ACP integration does not surface a stable public hook for remote-MCP OAuth authorization, so it uses
  the same temporary project-token lifecycle as Claude Code.
- Unverified: whether Claude Code applies the local scope when started from a subdirectory of the project.

## Exceptions / Deviations

- `.junie/mcp/mcp.json`, `.junie/AGENTS.md`, and `.junie/rules/datamimic.md` are Junie host integration files, not stable
  JetBrains plugin APIs. Recheck them against a real supported Junie version before every release that changes agent
  setup.
- The generic workflow is a generated delivery projection, not a second policy owner. Platform
  `MCP_SERVER_INSTRUCTIONS` remains authoritative. Its `datamimic_build_model` step is the bounded authoring dry run;
  it never persists XML or starts data generation.

## Verification

- `McpTest`: Junie and AI Assistant configuration ownership, token replacement and removal, routing publication,
  migration of older plugin rules, symlink/tracked-file safety, and the canonical build/dry-run/commit instructions.
- `AgentConnectionTest`: renewal and last-window cleanup ordering.
- `AgentDiscoveryTest`, `AgentVfsTest`, and `AiAssistantSettingsTest`: pre-open publication, IDE file refresh, and the
  default-off project setting.
- Manual release check: open a fresh DATAMIMIC project and start new Junie and AI Assistant chats; existing chats may
  cache earlier MCP and guidance state.
