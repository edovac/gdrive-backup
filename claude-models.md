# Claude Model Selection Guide

Which Claude model to use in Claude Code for each task on the
[plan.md](docs/plan.md) roadmap, on the **Claude Pro**
subscription.

**Constraint: use only models included in the Pro plan. Never use usage
credits.**

Last reviewed: 2026-09-13. Availability changes over time: `/model` shows what
your account can use right now.

---

## 1. Models

### Allowed

| Model | Alias | Pro usage | Context | Primary strength |
|---|---|---|---|---|
| **Claude Sonnet 5** | `sonnet` | Included; **default on Pro** | 1M (always, no credits needed) | Most coding work: implementing slices, tests, UI code |
| **Claude Opus 5** | `opus` | Included, but uses noticeably more quota | Standard window only | Deeper reasoning: design, port contracts, cross-layer changes, hard debugging |
| **Claude Haiku 4.5** | `haiku` | Included | 200K | Fast, cheap searches, lookups and mechanical edits |

### Not allowed (these need usage credits on Pro)

| Option | Why it's excluded |
|---|---|
| **Claude Fable 5.1** (and Fable 5) | Not part of Pro's included usage; runs only on credits |
| **Fast mode** (`/fast`) | Bills credits even when plan usage remains |
| **Opus with 1M context** | The 1M Opus variant needs credits on Pro; don't pick it in `/model` |

**Safeguard:** keep usage credits turned off under **Settings > Usage** on
claude.ai. With credits off, none of these options can bill: `/fast` reports
"Fast mode requires usage credits" instead of running. You can also set
`CLAUDE_CODE_DISABLE_FAST_MODE=1` to remove fast mode entirely.

### How Pro limits work

- Usage is metered in a **5-hour session window** plus a **weekly limit**
  across all models.
- Claude Code shares that pool with claude.ai and Claude Desktop, so chatting
  and coding draw from the same allowance.
- Sonnet is the intended everyday model. Anthropic's guidance for Opus is to
  "switch to it when you need it rather than leaving it on by default".

### Controls that stretch the allowance

| Control | How | Use it for |
|---|---|---|
| **`opusplan`** | `/model opusplan` | Uses Opus while in plan mode, then switches to Sonnet to execute. This matches the "design → build" pattern below without changing models by hand. |
| **Effort level** | `/effort low`/`medium`/`high`/`xhigh`, or the slider in `/model` | Lower effort for routine work spends less quota. Raise it for design and debugging. |
| **Subagent model** | `model: haiku` (or `sonnet`) in `.claude/agents/*.md` frontmatter | Keeps searches and file reading off the expensive model |
| **Model switch** | `/model <alias>`, or `claude --model <alias>` at launch | |

---

## 2. Roadmap tasks

Legend:
- 🟢 Sonnet 5
- 🔵 Opus 5 (heavier on quota)
- 🧭 `opusplan`: Opus plans, Sonnet builds
- ⚪ Haiku 4.5

### P0 — usable and safe v1

| Task | Model | Rationale |
|---|---|---|
| **Runtime backup-destination and database-location selection** | 🧭 | Needs a new inbound port, a configuration-backed outbound port, validation, and UI copy that makes "different database = different history" clear. Plan the ports across layers, then the adapter and UI code are routine. |
| **Full vs. incremental backup selection** | 🧭 | Changes the `DriveBackupUseCase` contract and job state. Full mode must ignore the saved cursor without corrupting `sync_state`. |
| **Interruptible backups and recovery policy** (design) | 🔵 at `xhigh` | The highest-stakes design on the roadmap. Cancellation points, SQLite transaction/checkpoint behavior, temporary archive naming and resume/cleanup must stay consistent. An interrupted run must never look complete. Follow the safeguards in §4. |
| **Interruptible backups and recovery policy** (implementation) | 🔵 | Crosses `SqliteDatabase` (a new connection per operation), the storage adapter, both sync services and the FX thread. Too entangled to hand to Sonnet safely. |
| **Progress bar and current-operation status** | 🧭 | The domain needs a progress port that doesn't depend on JavaFX, which `HexagonalArchitectureTest` checks. Binding it to the UI through `Platform.runLater` is routine. |

### P1 — complete the backup product

| Task | Model | Rationale |
|---|---|---|
| **Self-contained archive with manifest** | 🧭 | The format and manifest layout need a decision; ZIP is the candidate. Streaming ZIP writing in an adapter is then routine. |
| **All-revisions vs. latest-only selection** | 🔵 | The roadmap flags this as needing Google API validation: what `revisions` retrieval supports for binary files versus Google-native exports. Write the research down before implementing. |
| **Partial-failure handling and completion summary** | 🧭 | Plan how to classify errors from the Google adapters (suspended accounts, revoked access, quota) and keep one user's failure from stopping the run. Implementation is routine once that's set. |

### P2 — operational improvements

| Task | Model | Rationale |
|---|---|---|
| **History view (`file_events` + `file_captures`)** | 🟢 | Read-only queries against an existing schema plus a list/detail view; well specified in the requirements. |

### P3 — delivery and UX refinements

| Task | Model | Rationale |
|---|---|---|
| **Tabbed authenticated-screen redesign (header, Backup / Archives / Technical info tabs)** | 🧭 | `JavaFxApplication` is one ~680-line class, and AGENTS.md expects inbound adapters under `adapter.in`. Plan that split with Opus, then build and iterate on the panels with Sonnet. |
| **Windows installer with `jpackage`** | 🟢 | Maven/`jpackage` configuration and packaging scripts. Verifying on a clean machine without a JDK is a manual step no model can do. |

### Items still in progress

| Task | Model | Rationale |
|---|---|---|
| **Expose remaining backend capabilities in the UI** | 🟢 | The pattern is established: async use-case call, then an FX-thread update. |
| **Richer progress reporting in headless sync** | 🧭 | Same port design question as the P0 progress bar; do them together. |

---

## 3. Recurring work

| Task | Model | Notes |
|---|---|---|
| Codebase search, symbol tracing, "where is X used" | ⚪ | Run it as a subagent so the file reading doesn't fill the main session's context |
| Reading Surefire reports and logs to find a failing test | ⚪ | Switch to 🟢 once the failure is located and needs a fix |
| Unit tests for services and adapters (`*Test`) | 🟢 | |
| Opt-in real-account integration tests (`*IT`) | 🟢 | Writing them. Running them needs your credentials and the `-Dgoogle.*.integration=true` flags. |
| Fixing a `HexagonalArchitectureTest` violation | 🟢 | Move to 🔵 if the fix needs a new port |
| Localized bug fixes and compile errors | 🟢 at `medium` effort | |
| Non-deterministic bugs (FX thread, `CompletableFuture`, SQLite locking, WSL browser interop) | 🔵 at `xhigh` | Follow the safeguards in §4 |
| Code review before merging a P0 slice | 🔵 | |
| Updating roadmap status markers in `docs/plan.md` | ⚪ | Mechanical, but check the progress note against what was actually built |
| Setup guides (e.g. `google-admin-console-setup.md`) | 🟢 | |

---

## 4. The hardest tasks, with Opus 5 as the ceiling

Opus 5 is the most capable model available without credits. Two tasks push
against that ceiling:

1. Designing and reviewing the interruptible-backup and recovery policy.
2. Non-deterministic concurrency bugs.

Compensate with process rather than a bigger model:

- **Decide in plan mode first.** Write the cancellation points, the transaction
  boundaries and the archive temp-file lifecycle into the plan before any code.
- **Give edge cases explicitly.** For example: cancellation mid-download, disk
  full, expired page token, locked SQLite file, app closed during packaging.
  Don't rely on the model to come up with them.
- **Write the tests first.** Add tests that simulate an interrupted run and
  assert the database and archive state, then implement against them.
- **Start a fresh session for review.** A new Opus session that reads only the
  diff and the plan catches more than the session that wrote the code.
- **For concurrency bugs, collect evidence before theorizing.** Add structured
  logging or a deterministic stress test that reproduces the bug. After two
  unconfirmed hypotheses, gather more data rather than guessing again.

---

## 5. Quota rules of thumb

- **Default to Sonnet 5** and use `opusplan` instead of leaving Opus on.
- **Start a new session per roadmap slice.** Opus runs without the 1M window
  here, and long sessions resend more context every turn.
- **Delegate reading to Haiku subagents**, and keep the main session for
  decisions and edits.
- **Don't use Haiku for code that changes backup behavior.** Limit it to
  reading, searching and mechanical edits.
- **When a limit is reached, wait for the reset.** Don't turn on usage credits
  to keep going.
- **Checks are the same on every model:** run
  `./mvnw -Dtest=HexagonalArchitectureTest test` and `./mvnw test` after every
  slice, whichever model wrote it.

Sources:
- [Model configuration](https://code.claude.com/docs/en/model-config)
- [Fast mode](https://code.claude.com/docs/en/fast-mode)
- [Claude Fable models on your plan](https://support.claude.com/en/articles/15424964-claude-fable-models-on-your-plan)
- [Models, usage, and limits in Claude Code](https://support.claude.com/en/articles/14552983-models-usage-and-limits-in-claude-code)
- [How do usage and length limits work?](https://support.claude.com/en/articles/11647753-how-do-usage-and-length-limits-work)
