# Publishing a game module

The core downloads module JARs listed in a versioned GitHub-hosted JSON manifest. The current `modules/manifest.json` is empty; modules built from this repository can still be installed locally by copying their JARs into `plugins/GameCraft/modules/` and enabling them in `config.yml`.

## Release steps

1. Build the module JAR and verify it loads on the GameCraft API version it declares.
2. Publish the JAR as an asset on a GitHub release. Use a stable asset URL that returns the JAR bytes.
3. Compute the SHA-256 of the exact uploaded file:

   ```sh
   sha256sum example.jar
   ```

4. Add/update its manifest entry in `modules/manifest.json` and publish that registry at a GitHub or `raw.githubusercontent.com` URL.
5. Ask server owners to add the module ID to `modules.enabled-games` or run `/gamecraft enable example`, then restart after the JAR downloads.

Never edit the checksum after upload unless the asset bytes also change. The loader validates it before the file is moved into the module cache.

## Manifest schema

The root object requires `schemaVersion`, `apiVersion`, and `modules`. Each module entry requires `id`, `version`, `url`, and `sha256`:

```json
{
  "schemaVersion": 1,
  "apiVersion": 1,
  "modules": [
    {
      "id": "example",
      "version": "0.1.0",
      "url": "https://github.com/OWNER/REPOSITORY/releases/download/example-v0.1.0/example.jar",
      "sha256": "REPLACE_WITH_64_HEX_CHARACTERS_FROM_SHA256SUM"
    }
  ]
}
```

Use a real 64-character hexadecimal checksum in a published manifest. Module IDs must match `[a-z][a-z0-9-]{1,31}` and the module descriptor ID. The loader currently accepts only HTTPS URLs whose host is `github.com`, `raw.githubusercontent.com`, or a subdomain of `githubusercontent.com`. The manifest schema and API version must be `1` or an API version supported by the installed core; a registry API version greater than the core's version is rejected.

## Cache and activation

The module is cached as `plugins/GameCraft/modules/<id>.jar`. A first download is saved there and loaded at the next startup. If the module already exists and its checksum changes, the new JAR is staged as `<id>.pending.jar` and promoted during the next startup, then loaded. The core does not hot-reload module classes.

The loader checks the SHA-256 during download, uses an atomic file move when available, and discovers `GameModule` providers through `ServiceLoader`. It also checks that the descriptor ID matches the enabled ID and rejects API versions newer than its own. Keep old releases available if servers may still run older module versions.

## Compatibility and trust

Declare the lowest GameCraft API version the module actually needs. Compile against the separate `gamecraft-api` artifact/project and do not package duplicate API classes. Test the module JAR and its dependency packaging on a clean server before publishing. The checksum detects accidental or uncoordinated changes; it does not replace reviewing and trusting the code publisher, since the server runs downloaded module code.
