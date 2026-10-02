# Model Library and provider adapters

Orbit Assistant 0.8.3.0 Beta 4 adds Anthropic Claude and xAI/Grok without changing the existing
ChatGPT account path or Orbit Local. This document records the provider boundary and the model
metadata rules that code and tests must preserve.

## Official sources reviewed for Beta 4

Reviewed on October 2, 2026:

- OpenAI: [model catalog](https://developers.openai.com/api/docs/models). The six ChatGPT models
  already exposed by Orbit continue to use the official 1,050,000-token context limit.
- Anthropic: [model overview](https://platform.claude.com/docs/en/models/overview),
  [model list API](https://platform.claude.com/docs/en/api/models/list),
  [Messages API](https://platform.claude.com/docs/en/api/messages/create),
  [streaming](https://platform.claude.com/docs/en/build-with-claude/streaming), and
  [errors](https://platform.claude.com/docs/en/api/errors).
- xAI: [model overview](https://docs.x.ai/developers/models),
  [language-model list API](https://docs.x.ai/developers/rest-api-reference/inference/models),
  [Chat Completions API](https://docs.x.ai/developers/rest-api-reference/inference/chat-completions),
  [reasoning controls](https://docs.x.ai/developers/model-capabilities/text/reasoning),
  [streaming](https://docs.x.ai/developers/model-capabilities/text/streaming), and
  [error guidance](https://docs.x.ai/developers/debugging).

Orbit uses developer API keys for Anthropic and xAI. It does not scrape consumer applications,
reuse consumer sessions, or imitate ChatGPT browser authentication for either provider.

## Request architecture

The normalized route is:

`AiRequest -> AiModelSpec validation -> AiProvider -> ProviderRequestMapper -> transport -> AssistantReply`

- `ChatGptProvider` keeps the existing browser ChatGPT authentication and `ChatGptClient` path.
- `AnthropicProvider` maps requests to the official Messages API. Its system instruction is a
  top-level field, conversation content uses Anthropic content blocks, and streaming consumes
  `text_delta` events.
- `XaiProvider` maps requests to the official Chat Completions API. Its system instruction is a
  system message and streaming consumes OpenAI-compatible choice deltas.
- `OrbitLocalProvider` and `RelayProvider` keep their established paths.

No provider may choose a different provider or model after `AiSelections` resolves a request.
Authentication, unavailable models, rate limits, overloads, request size and capability failures
are returned as user-facing categories without raw provider payloads.

## Catalog invariants

`AiModelSpec` is the one model fact record. It can represent provider ID, user-facing name, family,
description, context limit, reasoning controls and default, vision, native files, Orbit-extracted
documents, tools, web/search, streaming, availability, account-dependent access and metadata
source. Unknown capability values stay conservative rather than being guessed.

ChatGPT and fallback Claude/Grok definitions are trusted static metadata. Anthropic and xAI can
replace those fallbacks with their authenticated account catalog. Dynamic catalogs are bounded,
filtered to suitable text-chat models, sorted deterministically and cached outside portable backup.
A failed or empty refresh cannot erase the last-known-good cache. A successful refresh that no
longer reports a model retains an unavailable tombstone so old chats, Favorites and Response
Details keep their original model identity.

The context meter reads `contextWindowTokens` from the actual selected model. Zero means unknown
and produces no percentage. It never borrows another provider's limit.

## User state and backup

Favorites keep exact provider/model identity and survive refreshes. Recents contain at most six
deduplicated conversational selections, newest first. Only the ordinary user conversation dispatch
records a Recent; automatic titles, summaries, Smart Vault enrichment and catalog work do not.
Each provider remembers its last explicit model and reasoning strength without changing the global
default or existing conversations.

Favorites, Recents and provider defaults follow Orbit's ordinary preference backup rules. Dynamic
catalog caches do not. API keys and encrypted key material never enter backup.

## Credentials

Anthropic and xAI keys use separate aliases in `SecureStore` and Android Keystore-backed AES/GCM.
There is no plaintext fallback. A saved key is not redisplayed, logged, copied into diagnostics,
placed in conversations, included in Response Details, used as catalog metadata, or sent to the
other provider. Removing a key immediately makes that provider require setup again.

## Deliberate limits

Beta 4 does not add arbitrary provider endpoints, user-defined providers, Fusion or judge
synthesis, benchmark rankings, pricing comparisons, or a broad chat redesign.

## OpenRouter (Beta 6)

**Connection.** *Sign in with OpenRouter* is the primary path: OpenRouter's documented OAuth PKCE
flow (`https://openrouter.ai/auth` with an S256 `code_challenge`, then `POST /api/v1/auth/keys` with
the `code_verifier`), returning a user-controlled OpenRouter API key. `OpenRouterAuth` owns it:

- a fresh 86-character verifier and 256-bit state per attempt, from `SecureRandom`, in memory only;
- the callback is `http://localhost:<ephemeral port>/orbit/openrouter/<state>` (a documented
  OpenRouter callback form). OpenRouter documents no `state` parameter, so the state travels in the
  path and must match exactly, compared in constant time;
- the receiver binds to `127.0.0.1` (and `::1` on the same port when available) only for the
  transaction, closes on success, failure, cancel, replacement and after ten minutes (OpenRouter's
  code lifetime), and claims a code atomically so it is exchanged at most once;
- the key goes straight into `SecureStore`; nothing is logged, shown, backed up, or put in
  diagnostics, chats or Response Details.

*Use API key instead* remains as the advanced fallback and is how keys saved before Beta 6 arrived.
Both share one encrypted slot, so a Beta 5 key keeps working untouched; the provider card says
*Connected with OpenRouter* or *Connected with API key* (a key with no recorded source is an API
key). *Disconnect* removes the key, its source and OpenRouter's Auto permission, and keeps chats,
their answers and Favorites.

**Catalog.** `ProviderCatalogRepository` reads the account-filtered `GET /api/v1/models/user` when
the key may, otherwise the public `GET /api/v1/models`, checks connections with `GET /api/v1/key`,
and caches a bounded (800) last-known-good list in the no-backup files directory. A failed or empty
refresh never erases it; a model that disappears is kept as an unavailable historical entry; a
trusted baseline (OpenRouter Auto plus Auto's four curated slugs) covers a phone that has never
refreshed. Only chat models are kept: text in and text out only (no image generators, embeddings
or audio models), no `:batch` variants, and from OpenRouter's own namespace only OpenRouter Auto
(Fusion, Free and other routers are excluded). Capabilities come only from structured fields:
images from `architecture.input_modalities`, the window from `context_length`, strengths only from
`reasoning.supported_efforts` (when `supported_parameters` lists `reasoning`; `minimal` has no
Orbit equivalent and is dropped), retirement from `expiration_date`. Nothing is inferred from a
model's name. Tools, web search and native file input stay false: Orbit sends none of them to
OpenRouter, and PDFs reach OpenRouter models as Orbit-extracted text. OpenRouter Auto has no fixed
window and no strengths, because its downstream model varies.

**Route identity.** A model reached directly and the same model through OpenRouter are different
routes: `anthropic/claude-sonnet-5.5` on provider `openrouter` is not `claude-sonnet-5-5` on
provider `anthropic`. Favorites, Recents, provider defaults and per-chat selections all key on
provider plus model id, labels add *· OpenRouter*, and Model Library rows lead with
*OpenRouter · Anthropic*.

**Scale.** The quick picker still shows Favorites, a few Recents and at most six of a provider's
models (Auto's curated routes, then OpenRouter Auto; choosing OpenRouter starts on GPT-6 Luna, never on the router). Browse Models searches every word against
name, maker, route and slug, has Vision and Reasoning filters, and builds at most 60 rows, with a
"Showing 60 of N" line.

**Requests.** OpenRouter uses the OpenAI-compatible Chat Completions shape through
`ProviderRequestMapper.openRouter` and `ApiKeyProviderClient` (never `ChatGptClient`). It sends
`reasoning.effort` (with `exclude: true`) only when the model lists that effort, images only to
vision models, and no tools. The stream reader skips keep-alive comments, treats a mid-stream
`error` chunk as an error, stops reading on Stop (closing the stream cancels the request and its
billing on providers that support it), and keeps the reported `model` so OpenRouter Auto answers
can show what actually answered. Errors (401 per connection type, 402 credits, 403, 404, 408, 413
or context length, 429, 502, 503, unsupported attachments) become one plain sentence without the
server's body. OpenRouter models do not run Orbit's device actions.

Beta 5 adds Auto on top of this library without changing it: see `docs/SMART_ROUTING.md`. Auto
routes only to a small curated subset of these models, never to a model merely because an account
catalog reports it, and its choices never enter Recents.
