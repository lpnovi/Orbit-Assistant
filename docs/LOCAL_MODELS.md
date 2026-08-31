# Orbit Local models

Orbit Local is optional. Nothing here is bundled into either APK, nothing is downloaded without an
explicit request, and each model can be removed on its own. Both models are held by the
`com.orbit.assistant.local` component, never by Orbit itself, so uninstalling the component removes
them with it.

Both are pinned in [`ComponentModelSpec`](../local/src/main/java/com/orbit/assistant/local/ComponentModelSpec.java)
by exact byte count and SHA-256, and are verified before a downloaded file is ever promoted to being
the model.

## Chat model — since v0.7.7.0

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

## Device-action model — since v0.7.8.0 Beta 1

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
allowed at all, and an output carrying more than one action, an unknown action, an out-of-range
value, or a field such as `intent`, `component`, `url` or `package` is rejected outright.

The model receives one instruction, the current brightness and media-volume readings, and nothing
else: no conversation history, no screen content, no memory, and no attachments.

### Removing it

**Settings → AI & account → AI Providers → Orbit Local → Device actions → Delete action model.**
This deletes the file and its partial downloads, frees the storage, and unloads it from memory. The
chat model, Orbit Local chat, Orbit's own deterministic command recognition, and every cloud
provider are unaffected.
