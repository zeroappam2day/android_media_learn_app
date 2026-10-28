# Remaining checklist coverage

71 scenarios remain partially verified. Every runnable existing Android method has a passing receipt; these rows require additional scope beyond those method assertions. A partial row is not a failed app claim or a runtime pass.

The direct Cloud account route is separately unconfigured; the approved local Ollama → DeepSeek route passed.

## Navigation and readability

| Scenario | User goal | Still unproven |
|---|---|---|
| T008 | [U15](../../QA.md#u15) — Choose colors that make screens and panels easy to read | Both-theme semantics and older Home/system-bar pixels do not establish visual legibility in all panels/screens. |
| T031 | [U04](../../QA.md#u04) — See my different review choices reflected consistently across screens | Current review UI/counts/Bin tests cover main distinctions; restart every review decision and verify Sources totals after each remains incomplete. |
| T076 | [U15](../../QA.md#u15) — Resize, hide, reopen and close my summary settings | Settings control-cycle test omits explicit scroll and a full persisted-settings equality oracle for the same resize/minimize/close cycle. |
| T154 | [U12](../../QA.md#u12) — Recognize items in Bin and return without recovering or deleting any | No direct Bin count/combined-size/scroll/placeholder/Back scenario oracle; repair-thumbnail test covers a narrower path. |
| T168 | [U15](../../QA.md#u15) — Leave a main screen with Android Back and return to my saved information | Actual main-Activity Android Back to launcher and relaunch with retained data is not implemented by modal Back or host force-stop tests. |
| T210 | [U15](../../QA.md#u15) — Read long filenames and choose the correct action on a small screen | Large-font/long-name fixture proves exact action target and no mutation; full small-screen visual readability still requires visual acceptance. |
| T230 | [U19](../../QA.md#u19) — Choose a save location without changing my files | Current SAF helper performs sorting/view/cancel, not the exact List→overflow-dismiss→Show roots→writable-location sequence. |

## Permissions and inventory

| Scenario | User goal | Still unproven |
|---|---|---|
| T013 | [U03](../../QA.md#u03) — Find all my allowed photos and videos on the first search | Full-access opt-in phase checks real source visibility, but exact mixed-media first Scan→Sources totals/size within chosen scope is not one complete UI scenario. |
| T014 | [U02](../../QA.md#u02) — Refresh the item list twice without making duplicate entries | Repeated-scan seam is empty; real populated MediaStore Refresh twice with exact dedup/size invariants remains. |
| T015 | [U03](../../QA.md#u03) — Refuse photo access and allow it later when I am ready | Denied/full are separate reset phases; deny→read message→retry→grant→Sources in same lifecycle is not covered. |
| T016 | [U03](../../QA.md#u03) — Let the app see only the photo I select | Selected-media phase proves one readable/one withheld and recreation; exact Sources/Home presentation assertions for initial selected fixture remain. |
| T017 | [U03](../../QA.md#u03) — Allow one additional photo without sharing the rest | Actual selected-item expansion tested through Android UI; post-expansion Sources totals/rows not independently asserted. |
| T019 | [U03](../../QA.md#u03) — Take access away and allow it again without losing my saved choices | Actual host revoke/regrant preserves Keep, notes, tag, original bytes and visible Source counts. Saved-summary retention and the exact Android App-info UI route remain unverified. |
| T020 | [U03](../../QA.md#u03) — Allow photos while keeping videos private, then allow videos later | Photos-only count 1 then photos+video count 2 passed with explicit kernel-grant setup and Activity recreation. The exact end-user permission-dialog sequence remains unverified. |
| T022 | [U02](../../QA.md#u02) — Avoid duplicate items when I tap Refresh inventory repeatedly | Single-flight injected empty source does not prove repeated real Refresh with existing populated IDs stays duplicate-free. |
| T023 | [U02](../../QA.md#u02) — Return to an item search after using another app | Permission suite backgrounds after completed scan; no Home excursion while real populated scan is still active. |
| T189 | [U16](../../QA.md#u16) — Find new screenshots after using another app or reopening this one | Observer background/reopen uses recordings; exact newly created screenshot on background and process restart needs its own fixture oracle. |
| T206 | [U02](../../QA.md#u02) — Hide unavailable files and find their saved information when access returns | Actual revoke/regrant passed for Source counts and saved Keep/notes/tag/bytes. Saved-summary and all-list/count parity across that permission cycle remain unverified. |

## Review and processing

| Scenario | User goal | Still unproven |
|---|---|---|
| T026 | [U04](../../QA.md#u04) — Keep my Keep decision when I return later | Navigation+Room Keep transition is covered; phased persistence starts Keep directly and does not re-open Sources category after that exact review UI transition. |
| T027 | [U04](../../QA.md#u04) — Keep my Later decision when I return later | Later UI persists across navigation; restart and reopen same category with Later remains untested. |
| T028 | [U04](../../QA.md#u04) — Mark an item Remove without deleting its file | Remove UI preserves bytes and avoids automatic Bin; restart same review state remains untested. |
| T029 | [U04](../../QA.md#u04) — Return an item to New so I can review it again | New UI transition is checked before leaving; restart plus category review totals remain untested. |
| T039 | [U06](../../QA.md#u06) — Add only kept items from my selected folders to the waiting list | Folder UI/Keep-only queue state covered; operation toast count is not independently tied to changed eligible row count. |
| T053 | [U04](../../QA.md#u04) — Keep Later items from being worked on through either entry point | Queue and Preview non-Keep rejection tested; exact Home Process Next plus Preview Later matrix still requires the same Later fixture. |
| T061 | [U06](../../QA.md#u06) — Pause a group of items and resume to finish them | Pause/Resume now uses real visible banner controls but only one held item; multi-item batch preservation not covered. |
| T063 | [U06](../../QA.md#u06) — Avoid starting the same work twice when I tap repeatedly | No actual rapid Queue Process double-tap plus Home Start Batch during active work with exact single result/request oracle. |
| T180 | [U05](../../QA.md#u05) — Keep an image, get its summary from Queue, and find it after returning | Live photo uses visible Preview Process/Output, but Keep is seeded and same-result app restart is absent. |
| T181 | [U05](../../QA.md#u05) — Cancel opening details, then return and deliberately start a summary | Live Preview process path lacks an initial explicit close-without-processing then reopen/Process step. |
| T186 | [U02](../../QA.md#u02) — Find my item after a failed search is repaired and retried | Source failure/retry uses injected empty inventory; exact recovered item/category after restoration is not verified. |
| T187 | [U04](../../QA.md#u04) — Stop an item waiting for work by changing Keep to Later | Waiting Keep→Later is applied through VM rather than actual Sources review UI, and no corresponding Overview count oracle. |
| T188 | [U04](../../QA.md#u04) — Understand what happens if I change Keep to Later while work is underway | Active Later/notes preservation now has held transport oracle; edits still enter through VM, not Sources controls. |
| T219 | [U06](../../QA.md#u06) — Use work controls with nothing eligible without changing any item | Home empty-eligibility controls tested; exact full Home/Queue/Preview zero-eligible control matrix is incomplete. |

## Storage and failure recovery

| Scenario | User goal | Still unproven |
|---|---|---|
| T032 | [U19](../../QA.md#u19) — Save a chosen watch folder and see whether other folders are still included | Actual folder picker save and independent scope-scan contract tests exist; same chosen UI folder followed by actual scope scan/relaunch with sibling exclusion is not unified. |
| T036 | [U19](../../QA.md#u19) — Choose a folder again after access to it was taken away | Actual SAF release/regrant candidate omits visible failed read/access state before reselecting the folder. |
| T159 | [U13](../../QA.md#u13) — Know which available Bin files a deletion request includes | Delete-all test has available own Bin files/outside-Bin survivor; unavailable/pending/trashed Bin entries are not included to prove exclusion. |
| T160 | [U14](../../QA.md#u14) — Save a separate readable copy of my notes and summaries | Actual SAF save parses own item but fixture lacks edited notes/summary; field export proof is a separate intercepted-result data path. |
| T164 | [U14](../../QA.md#u14) — Save my information somewhere else after the first location refuses it | VM unwritable→writable callback/JSON seam is partial; actual SAF refusal/failure toast→new destination retry remains. |
| T183 | [U13](../../QA.md#u13) — Keep my file safe when Android cannot open its deletion question | Actual failure toast and bytes preserved; repaired request is only constructed programmatically, not visibly launched/canceled in same failure/retry scenario. |
| T184 | [U13](../../QA.md#u13) — Know when the app cannot confirm whether a removal happened | Unqueryable reconciliation is a direct VM seam plus actual toast; real uncertain Android-result callback is not exercised. |
| T222 | [U13](../../QA.md#u13) — Retry Delete all after its confirmation could not open without losing files | Bin failure surface preserved; repair→request construction and finishBinDelete(false) is not actual visible system retry/Cancel. |
| T237 | [U19](../../QA.md#u19) — Find the right save location by changing the folder window’s order and view | Observed Name/view control clicks do not independently verify actual directory ordering and target identity after view changes. |

## Rename and media formats

| Scenario | User goal | Still unproven |
|---|---|---|
| T057 | [U18](../../QA.md#u18) — Rename the same item after it becomes available again | No unavailable-file visible Rename failure followed by restored access/refresh and UI retry on same identity. |
| T205 | [U18](../../QA.md#u18) — Find the same renamed item in every list and in a newly saved copy | Real rename parity passed for physical media, Queue, Sources, Tags, saved notes/summary, stable identity and immutable earlier export. The same renamed item in archive and Bin views remains unverified. |

## Editing, archive and saved results

| Scenario | User goal | Still unproven |
|---|---|---|
| T067 | [U08](../../QA.md#u08) — Remove a video from the app list while keeping its original file | No archived-video Delete selected index entries→original bytes retained→Refresh rediscovery scenario; image-only deletion cannot substitute. |
| T069 | [U08](../../QA.md#u08) — Remove an image from the app list while keeping its original file | Archived-image index removal/physical bytes checked; subsequent production Refresh rediscovery not executed. |
| T071 | [U02](../../QA.md#u02) — Understand whether sorting changes the order of set-aside items | Archive Name/Size uses two images. Status selection plus independently known three-item initial scan-order matrix missing; old no-op expectation superseded. |
| T072 | [U08](../../QA.md#u08) — Know whether restoring from Images also restores a video selected earlier | Current-archive restore isolation uses synthetic Room rows; both archive/Queue inspection plus real original-byte preservation not asserted in that test. |
| T080 | [U07](../../QA.md#u07) — Keep a long note containing Arabic, emoji and several lines | The 10,000-character Unicode note survives a real host force-stop, reopens exactly and exports exact values via an intercepted destination. Actual DocumentsUI saving of that exact edited payload remains unverified. |
| T082 | [U07](../../QA.md#u07) — Write and save my own information before asking for a summary | Manual save/reopen on unfinished item checked; exact export of pre-analysis fields in that same scenario not asserted. |
| T085 | [U04](../../QA.md#u04) — Mark a finished item as a removal candidate and change my mind | Preview candidate/unmark preserves status/bytes; Sources Remove-count and Bin parity after unmark remain. |
| T165 | [U14](../../QA.md#u14) — Save the latest names, review choices, notes and summaries together | Latest field export checked; one flow combining rename, changed decision, latest notes/summary, names/status and parsed export lacks full oracle. |
| T207 | [U05](../../QA.md#u05) — Understand why an unfinished item has no found words yet | Unfinished status/disabled Output shown; no exact empty-OCR guidance plus Android Back without mutation for the same unfinished video. |
| T208 | [U07](../../QA.md#u07) — Leave an unsaved note with Android Back without replacing my saved note | Unsaved Preview Close/minimize covered; actual Android Back while editing notes with saved-data equality not directly tested. |
| T214 | [U12](../../QA.md#u12) — Recover a Bin item after its file becomes available again | Unavailable availability seam and actual trash restore exist separately; revoke file permission→Refresh→Bin Recover exact UI sequence missing. |
| T227 | [U07](../../QA.md#u07) — Reopen my saved notes after stopping the app and save a matching separate copy | Seven fields, long Unicode notes, Keep, settings, theme and Output/export values passed across an actual host force-stop. Export uses an intercepted destination; the exact Documents/QA location flow with the same edited item remains unverified. |

## Provider settings and accounts

| Scenario | User goal | Still unproven |
|---|---|---|
| T098 | [U17](../../QA.md#u17) — Save Device choices and use them to summarize a kept image | Device selection/save tests and separate fallback-processing test do not exercise exact Timeout30/Temp0.20/custom Prompt save/reopen→kept image in one path. |
| T099 | [U17](../../QA.md#u17) — Connect to PC, choose a summary option, and get a saved result | PC connection/model-selection UI is separate from real DeepSeek Preview inference configured by fixtures; no single Settings connect/sync/save→real process path. |
| T102 | [U17](../../QA.md#u17) — Close choice lists without changing how summaries are prepared | No exact service dropdown Back and model dropdown outside-dismiss equality oracle. |
| T111 | [U17](../../QA.md#u17) — Restore the standard Prompt without accidentally changing my saved instructions | Reset usability/default save tests do not prove unsaved reset→Close→reopen retains original saved prompt. |
| T113 | [U17](../../QA.md#u17) — Correct a missing or rejected Cloud access key and connect successfully | ACTIVE Cloud feature: missing/invalid synthetic key→valid supplied account key→Cloud test/sync remains unexecuted. PC/local Ollama cloud route is approved but does not retire this feature. |
| T115 | [U06](../../QA.md#u06) — Get a clearly identified simpler summary when a service takes too long, then retry | Injected immediate SocketTimeoutException tests fallback/retry, not actual configured5s elapsed deadline/slow service and UI Reset on that exact path. |
| T116 | [U06](../../QA.md#u06) — Get a saved result after the connection drops and retry when it returns | No connection-drop-during-response transport seam; timeout/malformed responses are different failures. |
| T117 | [U06](../../QA.md#u06) — Recover when the summary service sends an unusable answer | Malformed provider fallback/retry exists, but Settings Host/Model/Timeout save and visible Reset are bypassed by fixture setup/VM reset. |
| T167 | [U17](../../QA.md#u17) — Hide settings while editing without mistaking a lost draft for saved choices | Shared modal Host draft survives minimize; exact Prompt draft minimize/restore/Close/reopen equality still incomplete. |
| T176 | [U17](../../QA.md#u17) — Close an unsaved Device choice and keep my saved PC choice | Historical Device→Close→PC retained evidence needs current APK repetition; newer presence-only provider draft test does not explicitly execute this same close/reopen scenario. |
| T179 | [U17](../../QA.md#u17) — Save Cloud choices and get a result prepared by the chosen service | ACTIVE direct Cloud provider save/real service result remains separate from approved PC→local Ollama→DeepSeek cloud pipeline. Do not label Cloud retired. |
| T191 | [U17](../../QA.md#u17) — Wait for a slow but successful service and receive its answer | A two-second response succeeds under a five-second configured timeout and saves Output. The exact 30-second-settings/progress scenario remains unverified. |
| T192 | [U17](../../QA.md#u17) — Keep known summary choices during a connection problem and refresh them later | Failed same-endpoint sync retains choices, but server restoration offering QA-model-B→resync→choose/save/reopen not completed in same scenario. |
| T193 | [U17](../../QA.md#u17) — Choose a suitable summary option after changing services | Endpoint/provider model provenance has PC/Device and host normalization oracles; direct Cloud sync/model/save/reopen with account not covered. |
| T194 | [U06](../../QA.md#u06) — Get a clearly identified simpler summary when a service refuses the request, then retry | Visible Reset/retry after a dropped connection passed. The specific 403 response followed by visible Reset/retry remains unverified; the separate 403 test resets through the VM. |
| T195 | [U06](../../QA.md#u06) — Get a clearly identified simpler summary when a service returns no answer, then retry | Visible Reset/retry after a dropped connection passed. The specific empty-response followed by visible Reset/retry remains unverified; the separate empty-response test resets through the VM. |

