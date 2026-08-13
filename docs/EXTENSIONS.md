# Orbit Extensions v1

Orbit Extensions add reviewed, declarative actions to saved Routines. An extension is a UTF-8 JSON file with the `.orbitext` extension. It contains data only: Orbit does not load extension code, scripts, APKs, Android components, or arbitrary intents.

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

## Install and manage

1. Save a valid manifest with a `.orbitext` filename.
2. Open **Orbit Settings → Extensions → Install extension**.
3. Select the file with Android's system file picker.
4. Review the extension identity, actions, endpoints/domains, and isolation notice.
5. Choose **Install** explicitly.

Installed manifests and enabled state stay in Orbit's private app storage and are included in Orbit Backup & Restore. Credentials and tokens are not supported or backed up. Disabling or removing an extension does not delete Routines; affected steps report **Extension action unavailable** until the matching extension/action is installed and enabled again.

The repository also retains a generic schema sample at [`examples/orbit-extensions/example-web-tools.orbitext`](../examples/orbit-extensions/example-web-tools.orbitext). Copy any manifest to the device and follow the installation steps above. Orbit does not install or depend on repository examples automatically.

## First-party extensions

Orbit publishes three ordinary Extensions v1 manifests for practical testing and as references:

- **Orbit Web Tools** (`com.orbit.extensions.web-tools`) — opens Orbit's public Releases, repository, and Issues pages. File: [`orbit-web-tools.orbitext`](../examples/orbit-extensions/orbit-web-tools.orbitext).
- **Developer Tools** (`com.orbit.extensions.developer-tools`) — opens official Android and GitHub Actions documentation and includes one bounded, unauthenticated HTTPS GET request to GitHub's public Zen endpoint. File: [`developer-tools.orbitext`](../examples/orbit-extensions/developer-tools.orbitext).
- **Quick Links** (`com.orbit.extensions.quick-links`) — opens neutral public search, maps, and reference destinations. File: [`quick-links.orbitext`](../examples/orbit-extensions/quick-links.orbitext).

These files are not bundled defaults, a marketplace, or executable plugins. Users must obtain a file, select it through Android's system file picker, review its actions and contacted endpoints, and explicitly confirm installation. They exercise the same parser, private storage, Routine catalog, Action Engine, enable/disable/remove behavior, and safe unavailable state as any manifest built from this schema. Authors can use the same format to create their own declarative extensions without adding arbitrary code or Orbit-data access.

## Routine integration

Enabled extension actions appear under the **Extensions** group in the normal Routine step catalog. A Routine stores only the stable extension ID and action ID, plus display labels for an understandable missing-action state. Execution resolves the current validated manifest and runs through the existing `Routine → OrbitActionEngine` chain.

HTTPS actions can run headlessly from manual Routines, compatible widgets, the configured Routine Quick Settings tile, and time/location triggers. URL actions open the reviewed browser destination when foreground launching is allowed; automatic triggers preserve Orbit's existing user-handoff rules.

## Versioning and v1 limits

- `schemaVersion` is the compatibility contract for the file format. Orbit v0.7.0.x accepts schema version `1` only.
- Extension `version` is author-supplied display metadata. Updating an installed manifest is not supported in v1; remove it, review the replacement, and install again.
- There is no arbitrary code, Java/Kotlin/JavaScript loading, shell access, APK execution, reflection, custom headers/authentication, secrets store, arbitrary Android intents, file access, or Orbit personal/context-data API.
- Extension actions are intentionally available to saved Routines only in v1. They are not language-model tools or chat plugins.
