# Orbit Local models

Orbit Local is optional. Nothing here is bundled into either APK, nothing is downloaded without an
explicit request, and each model can be removed on its own. Both models are held by the
`com.orbit.assistant.local` component, never by Orbit itself, so uninstalling the component removes
them with it.

Both are pinned in [`ComponentModelSpec`](../local/src/main/java/com/orbit/assistant/local/ComponentModelSpec.java)
by exact byte count and SHA-256, and are verified before a downloaded file is ever promoted to being
the model.

## Chat model (since v0.7.7.0)

| | |
| --- | --- |
| Model | Qwen 2.5 1.5B Instruct |
| Publisher of this export | [`litert-community/Qwen2.5-1.5B-Instruct`](https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct) |
| File | `Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task` |
| Format / runtime | LiteRT `.task`, MediaPipe LLM Inference |
| Quantisation | 8-bit |
| Context | 4096 tokens |
| Size | 1,598,556,720 bytes (~1.60 GB) |
| SHA-256 | `82968d0a6c3872cf016fdbcfc591571605f4c7fd2b0f64d2533df502cc6596b3` |
| Licence | Apache-2.0 |
Used for Orbit Local chat, and for nothing else.

### What it reads (since v0.8.2.0, Orbit Local 2.0)

Orbit builds every local prompt with `LocalContextBudget`, which fits a turn into the 4096-token
window with about 1,024 tokens held back for the answer. In priority order: Orbit's instructions,
the current question, the evidence the turn is about (Ask Vault passages, notification history,
attachment text or screen text), Orbit Memory, then the most recent conversation. Long evidence is
reduced to the passages that share the most words with the question - or sit closest in meaning,
when Smart Vault's on-device meaning model is on - instead of being cut at a fixed length. Ask Vault
gives the local model at most three saved items. Every block of user or third-party content is
neutralised and marked untrusted, so it cannot close its block or pose as a turn of conversation.

Token counts are estimated pessimistically (3.3 characters per token for ASCII, one per character
otherwise). The shares scale with the window, so a future model with a larger window gets a larger
budget from the same rules.

The model cannot see images. A picture with text Orbit recognised on the phone contributes that
text, labelled as such; a picture with none is answered by Orbit itself, without the model.

### Why it is still this model (reviewed for v0.8.2.0)

Reviewed against the candidates published for LiteRT in September 2026:

| Candidate | Runtime format | Size | Licence | Verdict |
| --- | --- | --- | --- | --- |
| Qwen 2.5 1.5B Instruct (current) | MediaPipe `.task`, 4K | 1.60 GB | Apache-2.0 | Keep |
| Qwen 3.5 2B | LiteRT-LM `.litertlm` only | 2.1 GB | Apache-2.0 | Needs the LiteRT-LM runtime |
| Gemma 4 E2B | `.litertlm` (a `.task` for web only) | 2.0-2.6 GB | Apache-2.0 | Needs the LiteRT-LM runtime |
| MiniCPM5 2B | `.litertlm` only | 1.6-2.5 GB | Apache-2.0 | Needs the LiteRT-LM runtime |
| Gemma 3 1B | `.task` | 0.7-1.8 GB | Gemma terms, gated | Gated terms Orbit would have to pass on; smaller than today's model |
| Phi-4 mini | `.task`, 4K | 3.9 GB | MIT | Too large to be the default |
| Qwen 3 0.6B | `.litertlm` only | 0.3-1.2 GB | Apache-2.0 | Smaller than today's model |

None is a drop-in improvement. The promising 2B-class models ship only for LiteRT-LM, which would
mean replacing the component's inference runtime, a change that cannot be validated without a
device, and every existing user would download 2 GB again. Qwen 2.5 1.5B stays, and nobody
redownloads anything for this release.

**Standard / Enhanced.** The architecture is ready for a second, larger chat model on capable
phones: the component already holds models in independent slots with their own download, removal
and memory, and the context budget takes the window size as a parameter. The missing piece is a
larger model in a runtime the component supports, validated on a real phone, so no catalog is shown
yet.

### Memory and lifecycle

The chat model (about 1.6 GB loaded) and the action model (about 0.5 GB) are held in separate
engines and can stay loaded together on a Galaxy S25 Ultra. Requests run one at a time across both.
A failure to load or generate, including running out of memory, unloads both engines so the next
attempt starts clean. If Android ends the component's process while it is answering, Orbit now
reports a clear Orbit Local error instead of waiting for an answer that will never arrive; the next
request binds a fresh process. None of these paths ever sends the request anywhere else.

## Device-action model (since v0.7.8.0 Beta 1)

| | |
| --- | --- |
| Model | Qwen 2.5 0.5B Instruct |
| Publisher of this export | [`litert-community/Qwen2.5-0.5B-Instruct`](https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct) |
| File | `Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task` |
| Format / runtime | LiteRT `.task`, MediaPipe LLM Inference |
| Quantisation | 8-bit |
| Context | 1280 tokens |
| Size | 546,660,344 bytes (~521 MB) |
| SHA-256 | `e608953f169aeb1bd7b9155fec2559825e08453fc209b84eda3a781ed0452fd2` |
| Licence | Apache-2.0 |

### Why this one

- **Same runtime, same export, same publisher, same licence as the chat model.** Nothing new had to
  be added to the component to run it, and there is one inference path rather than two.
- **Apache-2.0.** Permissive, well understood, and imposing no acceptable-use terms Orbit would have
  to pass on to users.
- **Small enough to be reasonable.** ~521 MB on disk and roughly that in memory while loaded, which
  a Galaxy S25 Ultra can hold alongside the 1.6 GB chat model without either being swapped out
  between turns.
- **Suited to the job.** It is asked for one short, tightly constrained JSON object, never for
  conversation. Instruction-tuned Qwen models are dependable at that, and the runtime is driven at
  temperature 0 so the output is as close to deterministic as it can be made.

### What it is trusted with

Nothing. Its output is untrusted input and is validated by
[`LocalActionSchema`](../app/src/main/java/com/orbit/assistant/LocalActionSchema.java), which checks
the action against a fixed allowlist, checks every parameter against a typed range, and then builds
Orbit's own parameter object from the checked values. The executor never receives anything the model
wrote. An app name is resolved against apps actually installed on the phone before `OPEN_APP` is
allowed at all, and an unknown action, an out-of-range value, a field the action does not read
(since v0.8.2.0), or a field such as `intent`, `component`, `url` or `package` is rejected outright.

**Since v0.8.2.0 (Local Device Actions 2.0):**

- **Several requests in one sentence.** Up to three actions, each validated exactly as a single
  one, all or nothing: one bad step rejects the whole plan. No target may appear twice, only one
  step may leave Orbit's screen (opening an app, Settings or the Clock) and it must be last, and
  every step of a multi-action plan must be something the user's own words mention. Execution goes
  through the existing Action Engine, so confirmation, permission and stop-on-failure rules are
  unchanged.
- **Undo.** The model may answer `UNDO_LAST`, which carries no parameters and means only "consult
  what Orbit recorded". Orbit resolves it from `RecentActionContext` - the state it read before its
  own last change - and asks which one when that change touched more than one thing. It is accepted
  only for sentences that already sound like an undo, and only while a recent change is remembered.
- **Settings destinations.** `OPEN_SETTINGS` may name `internet` or `bluetooth`, mapped onto the
  settings screens Orbit's executor already opens.

The model receives one instruction, the current brightness and media-volume readings, and nothing
else: no conversation history, no screen content, no memory, and no attachments.

### Removing it

**Settings → AI & account → AI Providers → Orbit Local → Device actions → Delete action model.**
This deletes the file and its partial downloads, frees the storage, and unloads it from memory. The
chat model, Orbit Local chat, Orbit's own deterministic command recognition, and every cloud
provider are unaffected.
