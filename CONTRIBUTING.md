# Contributing to Orbit Assistant

Thanks for your interest in Orbit. It is a small independent project, so the process is deliberately light.

## Ways to help

- **Report a bug** with the [bug report form](https://github.com/lpnovi/Orbit-Assistant/issues/new?template=bug_report.yml). Your device model and Android version matter, because assistant and Side-button behavior varies by manufacturer.
- **Suggest a feature** with the [feature request form](https://github.com/lpnovi/Orbit-Assistant/issues/new?template=feature_request.yml). Describe the problem first; a clear use case helps more than a detailed implementation.
- **Fix documentation.** Small corrections can go straight to a pull request.
- **Share an Extension.** See the [Extensions guide](docs/EXTENSIONS.md) and the examples in `examples/orbit-extensions/`.

## Code changes

1. Open an Issue first for anything beyond a small fix, so scope can be agreed before you spend time on it.
2. Build a debug APK with [Building from source](docs/BUILDING.md). No private signing material is needed.
3. Keep pull requests focused on one change, and run the unit tests (`:app:testDebugUnitTest`) before submitting.
4. Match the existing style: plain Java (no Kotlin or Compose), and all app UI through Orbit's shared `UiKit` components.

Please do not change the application ID, signing configuration, version numbers, or release workflows in a pull request. Releases are cut by the maintainer.

## Privacy

Issues and pull requests are public. Never include tokens, account details, backup files, notification text, private screen content, or unredacted Diagnostics output.

## License

Orbit Assistant is licensed under the [Mozilla Public License 2.0](LICENSE). By submitting a contribution, you agree that it is provided under the same license.
