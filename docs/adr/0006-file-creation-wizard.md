# 0006 — Create DATAMIMIC files from the IDE (file creation wizard)

- Status: Accepted
- Date: 2026-11-01
- Issues: #63 (files from platform templates), #64 (model from a JSON, XML or CSV file), #74 (GWA and AMA migration)

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
adds *New > DATAMIMIC Model from File…* for JSON, XML and CSV files, on the same architecture and with the same dialog. A
file chooser asks for the source file. The source can be outside the project, because the platform needs only its
content. The platform does not detect the delimiter of a CSV source and uses the project default. The dialog
detects the delimiter from the first lines instead. The user can change it.

Architecture. It follows the Street, Truck and Container rules of the web UI wizard
(`FileWizardV2` in the platform repository). Kotlin needs fewer layers than React, so only the rules and the layers
with a real user exist.

- **Container** (`core/creation/container`): one file for each group of flows (`TemplateFlows`, `UploadFlows`). A flow
  is plain data. It has a label, a list of `Choice` values (fixed options, for example the generation mode), an initial
  name and a name rule, and it builds a `CreationRequest`. It does no I/O.
- **Street** (`core/creation/street`): two steps. `createOnPlatform` runs a `CreationRequest` against the platform API.
  `pullCreated` brings the created files into the local folder. The street chooses its action by the type of the
  request (`FromTemplate`, `FromUpload`), never by a flow name.
- **Truck** (`ide/creation/truck`): the dialog. It shows any flow and names no flow.
- **Composition roots**: `NewDatamimicFileAction` (template flows) and `GenerateModelFromFileAction` (upload flows) are
  the only classes that import the containers. They replace the registry of the web UI.

Dependency rules, enforced by one text-scan test in the style of `SessionTest`:

1. `core/**` never imports `com.intellij`.
2. Only `NewDatamimicFileAction` and `GenerateModelFromFileAction` import `core.creation.container`. The test names
   both files and checks that they exist.
3. Street and Truck never name a flow or an extension literal.
4. Containers do no I/O.

Behavior:

1. The file types come from `GET /workspace/templates`. The plugin keeps no list of types.
2. `createOnPlatform` posts to `files/from-template` with `If-None-Match: *`. Then `pullCreated` calls `refreshTree()`
   and `sync.syncNow()` and resolves the local path, and the action opens the file. The plugin never writes the file
   locally.
3. The result reports two steps: "created on the platform" and "pulled to the local folder". If the pull fails or is
   late, the user sees a notice and not a failure. A retry meets HTTP 412.
4. The platform owns the name rules. The dialog checks only "not blank" and "no slash". It shows the `detail` text of a
   4xx answer. If the name exists (HTTP 412), the dialog stays open. No overwrite in #63.
5. The platform call runs in modal progress without a cancel button, because a running HTTP call cannot be cancelled.
   The pull runs in a second modal progress that the user can cancel. A cancel there stops only the wait: the user gets
   the notice that the file is created and that the sync goes on. The open runs after the pull.
6. The action is active only when the active project has a session and a local folder that contains the target
   directory. The code checks again before the call.

For #64 (model from a file):

1. The action shows in the *New* menu of a connected DATAMIMIC window. A file chooser asks for the source file. A selected
   source file in the Project view skips the chooser. `modelFlows` decides the extension, so the action repeats no
   extension list. The model goes into the selected folder.
2. The action saves unsaved editor changes of the source file first, because the upload sends the file on disk.
3. The flows depend on the extension: a model from JSON, XML or CSV, and a model with a template from JSON or XML. The
   model flows of JSON and XML offer the generation mode as a `Choice`. The CSV flow offers the delimiter as a `Choice`.
   The sealed `Field` type holds `Choice`, `TextInput` and `FileInput` (see #74).
4. The name starts as the source file name without its extension. The upload flows have their own name rule: letters,
   digits, `.`, `-` and `_`, a letter or a digit at both ends, no `..` and no `--`. This repeats the platform's name
   cleaning on purpose. Without it, the platform changes the name silently or fails late with a misleading message.
5. Before the upload, the action reads the tree fresh from the platform, not from the cache. If a target exists, the
   user must confirm the replacement, because the upload task overwrites files without a message. *Back* returns to
   the dialog with the same values.
6. `createOnPlatform` starts the upload task and polls its status every 2 seconds, in modal progress with a cancel
   button. After 5 minutes, or after 5 failed polls in a row, it returns "still running". A failed poll with a network
   error or HTTP 5xx is transient; a 4xx ends the wait. A cancel stops only the wait. In all three cases
   the user gets the notice that the platform still generates the files, because the platform task goes on.
7. On success, the action pulls the files and opens the model file. The model file is the first target of the request.
8. Errors: HTTP 409 `UPLOAD_GENERATE_ALREADY_RUNNING` shows a message and starts no second task. Another 4xx returns to
   the dialog with the platform text. A failed task shows the platform text and says that some files can exist.

For #74 (GWA and AMA migration):

1. A second action, *New > DATAMIMIC Migration…*, takes `.xlsm` and `.ama` files. It shares the code of the model
   action in `FromFileAction`. Each subclass names its flows, so each subclass is a composition root.
2. GWA writes one `<name>.opctl.csv` file. The field ids are the keys of the platform's `gwaConfig`. The schema version is
   required free text. Seven optional texts sit in a collapsed group. An empty text is not sent, so the platform
   default applies and the plugin keeps no copy of it.
3. AMA needs a second file, the `.ini` file. The platform takes it as Base64 in `custom_fields`. The dialog value of a
   `FileInput` is the path. The action calls `withEmbeddedFiles`, so the flow gets the content and stays free of I/O.
   The limit for such a file is 1 MB.
4. AMA writes the model and a variable number of EDI templates. The worker decides their paths. The plugin assumes the
   template folder of the dialog (start value `template`). It cannot list the templates before the run. The overwrite
   check asks when the template folder holds files, and the request names this folder in `folders`.
5. On success, the plugin pulls all files that the task reports and opens the model file.

Special cases and limits of #64, read from the platform code:

- The template file and the model file are written one after the other, without a rollback.
- Invalid JSON for a model, and invalid XML, fail late in the platform worker. The text is cut to 240 characters.
- The limit is 100 MB for each file. The plugin checks it before the upload.
- The platform reads at most 1000 CSV rows, and the first row is always the header.
- The platform has no deadline for a task. The plugin does not use the cancel call of the platform.

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
- The upload name rule is a second copy of a platform rule. If the platform changes its name cleaning, the plugin can
  refuse a name that the platform accepts. A refusal before the upload is better than a silent change or a late failure.
- "Still running" is not a result. The files reach the local folder through the normal sync when the task ends.

## Verification

- `WorkspaceTest`: the type list, the create call with its path, HTTP 412 with `FILE_DESTINATION_EXISTS`, an empty
  directory, and proof that the exact route was hit.
- `CreationTest`: create, refresh, sync, and the file on disk; HTTP 412; the event stream not live; a pull that fails
  after a successful create.
- The import-scan test for the four dependency rules.
- `CreationTest` for #64: the flows for each extension, the name rule, the request with the model file first, the
  fresh overwrite check, the size limit, upload success, failure, HTTP 409, the deadline, short and long poll outages,
  and a cancel during the poll.
- `CreationTest` for #74: the flows by extension, the GWA and AMA requests, the embedded file, the folder check, and a
  full run of each migration against the fake platform.
- `PluginRegistrationTest`: the three action ids and the *New* group.
- Not verified yet: the dialogs in a real IDE, the look from 2024.2 to 2026.2, and a run against a real platform.
