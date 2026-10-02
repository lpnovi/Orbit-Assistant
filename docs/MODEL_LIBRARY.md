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
synthesis, automatic routing, benchmark rankings, pricing comparisons, or a broad chat redesign.
OpenRouter chat remains deferred until it can be validated with a configured account.
