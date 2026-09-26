<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# DATAMIMIC Changelog

## [Unreleased]

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
