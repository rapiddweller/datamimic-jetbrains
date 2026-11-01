# 0006 — Create DATAMIMIC files from the IDE (file creation wizard)

- Status: Accepted
- Date: 2026-11-01
- Issues: #63 (files from platform templates), #64 (model from a JSON, XML or CSV file)

## Context

A DATAMIMIC project is a set of typed files. Each type has a format: entity data (`.ent.csv`), weighting
(`.wgt.csv`), scripts (`.scr.py`, `.scr.sql`), templates (`.template.json`, `.template.xml`), operate files
(`.opctl.csv`), properties, and the model (`.xml`). A user who writes such a file from an empty editor must know the
format. A wrong format can show only when a generation run fails.

The platform web UI has a *File Wizard*. The user picks a file type and gets a new file with valid example content. The
wizard can also turn an existing JSON, XML or CSV file into a DATAMIMIC model. The IDE has no such function. ADR 0003
removed the platform's file templates, so the Project view's *New* actions create plain files only.

Business goal:

- A new user creates a first valid file quickly and starts from working example content. This shortens the way to
  the first successful generation run.
- A user who works in the IDE does not switch to the browser for this task. A customer reported that the web UI makes
  it easier to find what to add, because it shows only the valid choices.
- The IDE and the web UI create the same files from the same templates. The platform owns the templates, so both
  stay equal without a second copy.

Constraints:

- The platform is read-only for this repository. The plugin adapts to the platform API.
- `core/` must not import `com.intellij`. A test enforces this.
- The plugin supports IDEs from 2024.2 (ADR 0005).
- A file that the platform creates reaches the local folder through the existing sync (ADR 0003). The plugin must not
  create the same file a second time.

## Decision

Add *New > DATAMIMIC File…* to the Project view. One dialog has a type list, a name field and a preset folder. Issue #64
adds *Generate DATAMIMIC Model…* for JSON, XML and CSV files on the same architecture.

Architecture. It follows the Street, Truck and Container rules of the web UI wizard
(`FileWizardV2` in the platform repository). Kotlin needs fewer layers than React, so only the rules and the layers
with a real user exist.

- **Container** (`core/creation/container`): one file per flow. A flow is plain data and builds a `CreationRequest`. It
  does no I/O.
- **Street** (`core/creation/street`): `createFile` runs a `CreationRequest` against the platform API, then waits
  until the file exists in the local folder. It chooses its action by the type of the request, never by a flow name.
- **Truck** (`ide/creation/truck`): the dialog. It shows any flow and names no flow.
- **Composition root**: `NewDatamimicFileAction` is the only class that imports the containers. It replaces the registry
  of the web UI.

Dependency rules, enforced by one text-scan test in the style of `SessionTest`:

1. `core/**` never imports `com.intellij`.
2. Only `NewDatamimicFileAction` imports `core.creation.container`.
3. Street and Truck never name a flow or an extension literal.
4. Containers do no I/O.

Behavior:

1. The file types come from `GET /workspace/templates`. The plugin keeps no list of types.
2. `createFile` posts to `files/from-template` with `If-None-Match: *`. Then it calls `refreshTree()` and
   `sync.syncNow()`, resolves the local path, and opens the file. It never writes the file locally.
3. The result reports two steps: "created on the platform" and "pulled to the local folder". If the pull fails or is
   late, the user sees a notice and not a failure. A retry meets HTTP 412.
4. The platform owns the name rules. The dialog checks only "not blank" and "no slash". It shows the `detail` text of a
   4xx answer. If the name exists (HTTP 412), the dialog stays open. No overwrite in #63.
5. The platform call runs in modal progress without a cancel button, because a running HTTP call cannot be cancelled.
   The pull runs in a second modal progress that the user can cancel. A cancel there stops only the wait: the user gets
   the notice that the file is created and that the sync goes on. The open runs after the pull.
6. The action is active only when the active project has a session and a local folder that contains the target
   directory. The code checks again before the call.

For #64: a sealed `Field` type for the dialog (exhaustive `when`), an upload task API with polling, a longer timeout and
extra form fields in `RequestBody.Multipart`, and an overwrite check. The upload task overwrites existing files without
a message. The overwrite check reads the tree fresh, not from the cache. If the deadline passes, the plugin stops waiting
and reports "still running", because the platform task goes on.

Alternatives not chosen:

- A fixed list of file types copied from the web UI (`DATAMIMICFILE`). It is a second copy that drifts.
- A full port of the React control system (`ControlKind`, `StatePath`, variants as tabs). It has nine control kinds, two
  of them dead code. The plugin needs three widgets.
- A hand-written dialog for each flow with no shared rules. It is simpler for one flow, but #64 then copies the
  logic five times.

## Consequences

- ADR 0003 no longer holds for file templates. The platform's templates are offered again, through the new action.
- The type labels come from the server (for example "Ent Csv (.ent.csv)"). They are machine-made and have no groups.
  A better label needs a change on the platform or a label table in the plugin (a second copy of knowledge).
- The tests need new routes in `FakePlatform`. The route for `files/from-template` must be an exact context. The
  existing `/files/` prefix handler takes any POST as a file create and hides a wrong route.
- A new pair of API calls and a new error code (`FILE_DESTINATION_EXISTS`) enter `core/workspace`.
- Some paths never sync, for example local-only folders and names that differ only in case. For these, the user gets a
  notice and the file exists on the platform only.
- The dialog is a Swing dialog built for 2024.2 and later. The Plugin Verifier matrix (ADR 0005) must run by hand for
  the UI change.

## Verification

- `WorkspaceTest`: the type list, the create call with its path, HTTP 412 with `FILE_DESTINATION_EXISTS`, an empty
  directory, and proof that the exact route was hit.
- `CreationTest`: create, refresh, sync, and the file on disk; HTTP 412; the event stream not live; a pull that fails
  after a successful create.
- The import-scan test for the four dependency rules.
- `PluginRegistrationTest`: the action id.
- Not verified yet: the dialog in a real IDE, the look from 2024.2 to 2026.2, and the Plugin Verifier run.
