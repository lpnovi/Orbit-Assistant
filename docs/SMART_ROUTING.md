# Smart Routing (Auto)

Orbit Assistant 0.8.3.0 Beta 5 added **Auto**, and Beta 6 (Smart Routing 1.1, policy 2) widened its
curated candidates. Auto is one Orbit-level AI selection that lets Orbit choose the provider, model
and reasoning level for each request. It sits beside the exact models of the
Model Library; it does not bring back the retired Auto, Fast, Balanced, Deep and Custom modes, and
there is no per-provider Auto.

This document records the architecture and the invariants that code and tests must preserve.

## Invariants

1. **Explicit model = exact model.** A request sent with GPT-5.6 Terra goes to GPT-5.6 Terra or
   fails and says so. Nothing outside Auto ever routes.
2. **Auto = permission to route.** Only `AiSelection.AUTO` lets Orbit choose.
3. **Auto picks one model per request.** One provider, one model, one attempt. No fan-out, no
   judging, no cross-provider failover. A failure is reported; Retry asks Auto again.
4. **Metered providers are off by default.** Anthropic, xAI and (since Beta 6) OpenRouter are never
   Auto candidates until the user enables them in *Settings > Intelligence > Auto can use*. A saved
   key or an OpenRouter sign-in does not enable them, migration never enables them, a backup never
   restores the opt-in, and removing a key or disconnecting turns the permission back off.
5. **Routing resolves before dispatch.** Auto runs once, when the request is queued.
6. **The resolved selection is persisted with the request.** The worker sends exactly that and
   refuses an unrouted Auto. Activity recreation, process death, WorkManager delays, Auto settings
   changes, chat selection changes and catalog refreshes never re-route a queued request.
7. **Auto routing does not pollute Recents.** Recents are the models the user picked.
8. **Internal metadata jobs do not use Auto.** Automatic titles, Continue in new chat summaries,
   Smart Vault enrichment and catalog work keep their fixed explicit policies.
9. **Routing never sends user content anywhere to decide.** It is local and deterministic.
10. **No silent substitution outside Auto.**

## Components

| Piece | Role |
| --- | --- |
| `AiSelection.AUTO` | The stored per-chat selection (`1|auto|auto|`). Never dispatchable. |
| `AiSelections` | Treats Auto as legal; `newChatSelection` adds an optional "new chats start on Auto" default while `globalDefault` stays explicit for planning and other non-chat work. |
| `AutoPermissions` | The five provider switches and their safe defaults. Only the user's switches write them. |
| `SmartRouter` | The one routing component: request description, curated candidates, eligibility, selection, reasons, policy version. |
| `OrbitRequestManager.enqueueFrozen` | The only caller of `SmartRouter.route`. Routes an Auto request and freezes the result in `PendingRequestStore`. |
| `PendingRequestStore.Item.route` | `{requested: auto, reason, policy, error}` beside the exact `aiSelection`. Null for explicit requests. |
| `AssistantClient.Routing` | Tells dispatch the turn was routed (no Recents) or failed to route (error, no provider call). |
| `ResponseDetails` | Records `requested`, `routeReason` and `routerPolicy` with an Auto answer. |

## Routing inputs

All computed on the phone when the request is queued:

- the message's length, structure (code, several questions) and a short list of reasoning words;
- whether it reads as an Orbit phone instruction (`OrbitLocalActionRouter.looksActionable`);
- whether it asks about current events (a short time-sensitivity word list);
- image count; extracted document text length;
- the estimated size of the assembled request, measured by the same request builder in measuring
  mode as the context meter, over the active branch only, with kept context counted once;
- the Model Library's structured capabilities (vision, context window, strengths, availability);
- provider capabilities (`AiCapabilities.images`, `deviceActions`, `hostedWebSearch`);
- provider readiness (`AiProvider.status == READY`), Auto permissions, known-unavailable models and
  Favorites.

Web search is used only through Orbit's real integration: a model counts as search-capable only
when its spec says so *and* its provider's Orbit integration offers hosted search (today ChatGPT).

## Curated candidates (policy 2, Smart Routing 1.1, Beta 6)

`SmartRouter.POLICY_VERSION` is **2**. Beta 5 answers were routed by policy 1 and keep saying so:
the number is stored with every routed request and answer and is never reinterpreted, and a stored
record without a number reads as policy 1.

| Candidate | Suited to (fit by demand: simple / normal / complex / very difficult) |
| --- | --- |
| Orbit Local | Simple requests whose whole content fits its window (0 / – / – / –) |
| GPT-6 Luna | Simple and ordinary conversation (0 / 0 / 2 / 3) |
| GPT-6.1 Sol | Complex and very difficult work (3 / 2 / 0 / 0) |
| GPT-5.6 Luna | Same tier as GPT-6 Luna (0 / 0 / 2 / 3) |
| GPT-5.6 Terra | The balanced tier between Luna and Sol (1 / 0 / 1 / 1) |
| GPT-5.6 Sol | Same tier as GPT-6.1 Sol (3 / 2 / 0 / 0) |
| Claude Haiku 4.5 | Simple requests |
| Claude Sonnet 5.5 | Ordinary conversation |
| Claude Opus 5.5 | Complex and very difficult work |
| Grok 4.7 | Ordinary and complex work |
| OpenRouter: `openai/gpt-6-luna` | As GPT-6 Luna |
| OpenRouter: `openai/gpt-6.1-sol` | As GPT-6.1 Sol |
| OpenRouter: `anthropic/claude-sonnet-5.5` | As Claude Sonnet 5.5 |
| OpenRouter: `anthropic/claude-opus-5.5` | As Claude Opus 5.5 |

**Why GPT-5.6 is here, and when it wins.** All six ChatGPT models share one 1,050,000-token window
and the same capabilities, so tier is what separates them. A default account keeps routing exactly
as in Beta 5: GPT-5.6 Luna and Sol tie with their GPT-6 tier-mates and are listed after them.
Each GPT-5.6 model has real, tested routes:

- **GPT-5.6 Luna** answers ordinary requests when this account has refused GPT-6 Luna (Orbit's
  first-hand `ModelAvailability` record, which expires after a day), or when it is the user's
  Favorite and GPT-6 Luna is not.
- **GPT-5.6 Sol** takes complex work when GPT-6.1 Sol is unavailable to the account, or as the
  Favorite of the two.
- **GPT-5.6 Terra** takes complex work when neither Sol is reachable (it is the closest fit left),
  and ordinary conversation when the user has made it a Favorite. It never takes simple requests
  from Luna or complex ones from an available Sol.

None of these wins by artificially beating GPT-6 on an ordinary request.

**OpenRouter candidates.** Only these four exact slugs, each fitting like the same model reached
directly. OpenRouter is metered (OpenRouter credits) and off for Auto until switched on. Its live
catalog lists hundreds of models; none becomes a candidate by appearing there, and **OpenRouter
Auto (`openrouter/auto`) is never a candidate**: Orbit Auto chooses an exact model itself and never
hands a request to another router (`SmartRouter.route` also refuses it defensively).

Left out deliberately: GPT-6 Astra (access varies by account), Claude Fable 5.1, the relay (it
spends an operator's metered key) and any model a provider's live catalog reports that is not in
this table. A candidate also needs a current catalog entry, `active` availability and a known
context window.

## Orbit Auto is not OpenRouter Auto

| | Orbit Auto | OpenRouter Auto |
| --- | --- | --- |
| What it is | `AiSelection.AUTO`: permission for Orbit to route | An explicit OpenRouter model, `openrouter/auto` |
| Who chooses the model | `SmartRouter`, on the phone, from the curated table | OpenRouter's router, inside OpenRouter |
| Response Details | Selection Auto, the exact provider and model, Why, Router policy | Selection OpenRouter Auto, Model OpenRouter Auto, and *Answered by* the downstream model OpenRouter reported (omitted when it reports none) |
| Context meter | Neutral until routed | No fixed window (the downstream model varies) |

## Eligibility, then selection

**Eligibility** removes a candidate when its provider is not permitted or not ready, the model is
missing, inactive or known unavailable, its context window is unknown, the request does not fit
(`tokens + 8,192 <= 90% of the window`), it cannot read a required image, or (Orbit Local) the
request is not simple, has more than 1,600 content tokens, or asks about current events.

**Selection** orders the rest by: can carry out a requested phone action; can search when the
request is time-sensitive; how well the model suits the request's demand; non-metered before
metered; a direct route before the same tier through OpenRouter (policy 2); Favorite (only as a
tie-breaker); then the fixed table order. There is no randomness. So OpenRouter credits are never
spent on what an enabled ChatGPT or Orbit Local route covers equally well.

**Reasoning level**: Low for simple, Medium for ordinary, High for complex, Extra High for very
difficult, moved to the nearest strength the model accepts. Auto never asks for Max.

**Reasons** shown in Response details are at most two of: Simple request, Normal conversation,
Complex reasoning, Image input required, Large context, Local model sufficient, Device action,
Current information, Preferred eligible model.

## Failure

When nothing permitted can take a request, the request is still queued so the chat shows a normal
failed answer with Retry, but no provider is called. The message names a connected provider that
could have answered but is not enabled ("Anthropic supports this request, but Anthropic is not
enabled for Auto."), or says no Auto-enabled model can handle the image or fit the context, and
points to manual model choice and the Auto settings.

## Context meter

With Auto the next model is not known, so the ring stays in its neutral, unknown state and the
details sheet says *Auto chooses per request*. Each routed request is checked against the window of
the model Auto actually chooses.

## Backup

Conversations keep `1|auto|auto|` selections and Auto answers keep their routing metadata. The
ChatGPT and Orbit Local Auto switches and the "new chats start on Auto" choice are ordinary
preferences and are backed up. The Anthropic, xAI and OpenRouter Auto opt-ins are not, and no credential ever
is. An older backup restores with the default permissions.

## Deferred

Fusion (including OpenRouter Fusion), multi-model comparison or judging, automatic failover,
Fast/Balanced/Deep, leaderboards, user-authored routing rules, OpenRouter server tools and web
search, and arbitrary custom providers.
