# Orbit Extensions

Orbit Extensions add reviewed, declarative actions to saved Routines. An extension is a UTF-8 JSON file with the `.orbitext` extension. It contains data only: Orbit does not load extension code, scripts, APKs, Android components, arbitrary intents, files, or reflection targets. Schema v1 remains supported unchanged; schema v2 adds Orbit-rendered setup fields and Routine parameters without adding executable plugin code.

## Manifest schema

```json
{
  "schemaVersion": 1,
  "id": "org.example.orbit.web-tools",
  "name": "Example Web Tools",
  "version": "1.0.0",
  "author": "Example Author",
  "description": "Harmless actions for testing Orbit Extensions.",
  "actions": [
    {
      "id": "open-orbit-releases",
      "name": "Open Orbit releases",
      "description": "Opens the public Orbit Releases page.",
      "type": "open_url",
      "url": "https://github.com/lpnovi/Orbit-Assistant/releases"
    },
    {
      "id": "test-https-get",
      "name": "Test HTTPS GET",
      "description": "Makes a bounded GET request to a public test endpoint.",
      "type": "https_request",
      "endpoint": "https://httpbin.org/get",
      "method": "GET",
      "timeoutSeconds": 8
    }
  ]
}
```

Top-level fields are required. IDs are stable lowercase identifiers; changing an extension ID or action ID creates a different Routine reference. A manifest may contain 1–20 actions and is limited to 64 KiB.

## Supported actions

### `open_url`

Opens one explicit public `http` or `https` URL through Android's normal browser handling. Local, private-network, credential-bearing, fragmented, and non-web URLs are rejected. This action needs a foreground browser and automatic Routine triggers use Orbit's existing notification-continuation behavior when required.

### `https_request`

Makes a `GET` or `POST` request to one explicit public HTTPS endpoint. Optional `timeoutSeconds` is 1–10 seconds. `POST` may include a fixed JSON-object `body` of at most 16 KiB. Credential-like fields such as passwords, tokens, API keys, cookies, and authorization values are rejected.

Orbit sends only the fixed body declared in the installed manifest. It never adds chats, Orbit Memory, screen context, notification history, location, files, account data, or device identifiers. Redirects are not followed, resolved private/local addresses are rejected, and responses are read only up to 64 KiB. Extensions v1 reports a concise HTTP success/error result and does not expose the response body to other Orbit systems.

## Schema v2: configuration and parameters

Schema v2 retains the same identity and action model while allowing a top-level `setupFields` array and an HTTPS action's `parameters` and `headers` declarations. Orbit validates immutable manifests, renders all controls itself with native UI, and never accepts extension-declared layouts or code.

Setup field types are:

- `text` — non-secret text, optionally backed up.
- `url` — a public HTTPS URL, optionally backed up and revalidated before use.
- `secret` — an opaque credential encrypted with Android Keystore-backed AES/GCM.
- `secret_url` — a credential-bearing public HTTPS URL, encrypted and shown only as a safe configured-host status after saving.

Every field has a stable ID and label, with optional description, `required`, `maxLength`, and a non-secret `default`. Secret fields cannot declare defaults. Credential-like fields must use a secure type. If Keystore encryption fails, Orbit saves no plaintext fallback and reports that the configuration could not be saved securely.

Action parameters use only `text` or a finite `choice` list. They may declare a stable ID, label, description, required state, bounded maximum length, and valid default. Parameter values are stored with the Routine step; the schema deliberately has no secret action-parameter type. Orbit rejects credential-like parameter IDs so Routine JSON cannot become a credential store.

### Restricted templates

Schema-v2 HTTPS endpoints, JSON string values, and declared header values may reference only exact placeholders:

```text
{{config.field_id}}
{{param.parameter_id}}
```

There are no expressions, loops, conditions, JavaScript, shell commands, reflection, or arbitrary evaluation. Placeholders are forbidden in JSON object keys. Orbit walks the JSON structure and replaces values as JSON strings, so inserted content remains correctly escaped rather than being concatenated into raw JSON. The rendered request body is checked against the 16 KiB limit after substitution.

Secure fields have narrower placement: `secret_url` may supply a request endpoint and `secret` may supply a declared request header. Secret values cannot be inserted into Routine parameters or ordinary JSON body templates.

### Request headers

Headers are a manifest-declared object with at most 12 validated names and bounded values. Runtime users cannot create header names. Orbit rejects CR/LF injection, duplicate names, `Host`, `Content-Length`, `Cookie`, proxy headers, and connection/transfer overrides. Credential headers such as `Authorization` and `X-API-Key` must be derived from a declared `secret` field; static credential literals and parameter/non-secret credential values are rejected.

### Network boundary

Every final resolved endpoint must remain public HTTPS. Orbit validates the rendered URL immediately before opening the connection, resolves DNS again, blocks localhost, loopback, link-local, private IPv4, private/local IPv6, `.local`, user-info URLs, credential-like query fields, and redirects. Response bodies remain hidden and bounded to 64 KiB. v0.7.1.0 does not support LAN/private-network integrations.

## Install and manage

Orbit provides two explicit installation paths:

### First-party extensions

1. Open **Orbit Settings → Extensions → First-party extensions**.
2. Choose **Install** beside Orbit Web Tools, Developer Tools, or Quick Links.
3. Review the extension identity, actions, contacted endpoints/domains, and isolation notice.
4. Choose **Install** explicitly.

The official manifest is read from Orbit's packaged, read-only assets and passed through the same manifest size limit and `OrbitExtension` parser/validator as an external file. It is not preinstalled, privileged, or written into user state until the user confirms the review. A matching stable extension ID is shown as Installed and cannot be duplicated. Replacement/update support remains future work; Orbit never silently overwrites an installed manifest.

### External extensions

1. Save a valid manifest with a `.orbitext` filename.
2. Open **Orbit Settings → Extensions → Import → Import extension from file**.
3. Select the file with Android's system file picker.
4. Review the same identity, actions, endpoints/domains, and isolation notice.
5. Choose **Install** explicitly.

This path remains available for user-created, community, and custom extensions. Android's Storage Access Framework supplies only the selected file; Orbit does not request broad storage access.

Installed manifests, enabled state, and validated non-secret configuration stay in Orbit's private app storage and are included in Orbit Backup & Restore. `secret` and `secret_url` values are stored separately and are never exported. After restore, affected extensions remain installed and saved Routine references remain intact, but required credentials show **NEEDS SETUP** until entered again. Disabling or removing an extension does not delete Routines; affected steps report **Extension action unavailable** until the matching extension/action is installed, configured, and enabled again.

The repository retains a generic schema sample at [`examples/orbit-extensions/example-web-tools.orbitext`](../examples/orbit-extensions/example-web-tools.orbitext) for developers and format reference. Custom copies can be tested through Import from file.

## First-party extensions

Orbit publishes five ordinary manifests for practical use and as references:

- **Orbit Web Tools** (`com.orbit.extensions.web-tools`) — opens Orbit's public Releases, repository, and Issues pages. File: [`orbit-web-tools.orbitext`](../examples/orbit-extensions/orbit-web-tools.orbitext).
- **Developer Tools** (`com.orbit.extensions.developer-tools`) — opens official Android and GitHub Actions documentation and includes one bounded, unauthenticated HTTPS GET request to GitHub's public Zen endpoint. File: [`developer-tools.orbitext`](../examples/orbit-extensions/developer-tools.orbitext).
- **Quick Links** (`com.orbit.extensions.quick-links`) — opens neutral public search, maps, and reference destinations. File: [`quick-links.orbitext`](../examples/orbit-extensions/quick-links.orbitext).
- **Discord Webhook** (`com.orbit.extensions.discord-webhook`) — schema v2; securely stores one user-provided webhook URL and sends a Routine-configured message through the generic HTTPS template engine. File: [`discord-webhook.orbitext`](../examples/orbit-extensions/discord-webhook.orbitext).
- **ntfy Notifications** (`com.orbit.extensions.ntfy`) — schema v2; configures a public ntfy server/topic and sends a title, message, and finite priority choice through the same generic engine. File: [`ntfy-notifications.orbitext`](../examples/orbit-extensions/ntfy-notifications.orbitext).

These files are not bundled installed defaults, a marketplace, or executable plugins. Current releases package read-only copies so users can start the normal review from **First-party extensions** without manually downloading GitHub files. The repository copies remain available for developers and reference. First-party and external installs use the same parser, validation, review, private storage, Routine catalog, Action Engine, enable/disable/remove behavior, and safe unavailable state. Authors can use the same format to create their own declarative extensions without adding arbitrary code or Orbit-data access.

## Routine integration

Enabled and fully configured extension actions appear under the **Extensions** group in the normal Routine step catalog. When an action declares parameters, Orbit opens its normal action form before adding the step and allows those parameters to be edited later. A Routine stores only stable extension/action IDs, display labels, and bounded non-secret text/choice parameters. Setup values and credentials are resolved separately at execution time from the current validated extension.

HTTPS actions can run headlessly from manual Routines, compatible widgets, the configured Routine Quick Settings tile, and time/location triggers. URL actions open the reviewed browser destination when foreground launching is allowed; automatic triggers preserve Orbit's existing user-handoff rules.

## Versioning and security limits

- `schemaVersion` is the compatibility contract. Orbit accepts v1 unchanged and v2 through a separate strict parser path; installed v1 manifests are not rewritten.
- Extension `version` is author-supplied display metadata. Updating an installed manifest still requires removing, reviewing, and installing the replacement.
- Extensions cannot load code, Java/Kotlin/JavaScript, shell commands, APKs, reflection targets, arbitrary Android intents, files, or an Orbit personal/context-data API.
- Schema v2 headers, setup, parameters, templates, URL resolution, manifest/action counts, field lengths, request size, timeout, and response size are bounded.
- Extension actions remain saved-Routine actions. They are not language-model tools, chat plugins, or a route for one extension to read another extension's configuration.
