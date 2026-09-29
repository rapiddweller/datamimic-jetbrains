<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# DATAMIMIC Changelog

## [Unreleased]

## [0.5.0]

### Features

- Browse generation task history and reopen status, logs, preview samples, and artifacts from successful tasks.
- Save individual artifacts, open saved text files in the IDE, or download all artifacts as a ZIP.
- Connect Junie to the active project's MCP server with a short-lived project access token created from the existing
  platform session, renewed automatically, and kept in local Git-ignored configuration.

### Improvements

- Show artifacts in results from native **DATAMIMIC Generation** Run Configurations as well as in task history.
- Consolidate preview samples into one selectable Preview tab, rename the tool window to **Generation Tasks**, and use
  distinct native icons plus a compact gears-only plugin logo.
- Keep newly accepted tasks visible while the platform persists them, and prevent artifact saves from overwriting
  concurrent IDE edits.
- Keep Junie on project-scoped DATAMIMIC MCP tools and leave data generation as an explicit user action.

### Fixes

- Fix generation task history with the Platform 4.0.1 task API and show useful validation details for rejected
  requests.
- Refresh plugin-managed Junie files in the IDE after registration, renewal, and cleanup.
- Make the development artifact downloaded from GitHub Actions directly installable in JetBrains IDEs.

## [0.4.0]

- Browse paginated generation task history and reopen task status, logs, and preview samples.
- View artifacts from successful tasks; save individual files, open text files in the IDE, or download all artifacts
  as a ZIP.
- Keep newly accepted tasks observable until the platform persists them, and prevent artifact saves from overwriting
  concurrent IDE edits.

## [0.3.0]

- Show live platform generation status and logs, surface refresh and stop errors, and show product preview samples
  when available; stopping requests platform cancellation.
- Add a local native **DATAMIMIC Generation** Run Configuration for each opened DATAMIMIC project. It supports the
  platform task types and uses the same save, sync, warning, and dispatch path as the project-window action.
- Give every non-tag CI artifact a traceable SemVer version based on the latest release tag and GitHub run number.

## [0.2.1]

- Keep project opening alive when the Welcome screen closes, so the IDE no longer returns to Welcome.
- Route Junie project requests through DATAMIMIC MCP and stop instead of falling back to local project tools.

## [0.2.0]

- Open DATAMIMIC projects from the Welcome screen and File | Open, including sign-in and project selection without an already open project.
- Failed sign-in retains entered values for retry and logs the failure reason to the IDE log.
- Supports JetBrains IDEs from 2024.2 onward; the optional language-server integration requires 2025.2.1+.
- Reuse local folders safely after project renames, reject occupied or ambiguous locations, and prevent concurrent sync of one folder.
- Keep sync state unchanged after failed local writes, reconnect live updates after transient failures, and clean up workspaces reliably during shutdown.
- Write a project-level DATAMIMIC MCP entry for Junie and protect its token-bearing configuration from Git.

## [0.1.0]

First public version. Requires a DATAMIMIC Enterprise Platform.

- Sign in, browse projects, and open a platform project as a synced local project.
- Edit locks, conflict banners, and live updates from the platform.
- Completion and checks from the platform's language server, with a status bar switch.
- Data generation with log and preview; MCP access for IDE agents.
