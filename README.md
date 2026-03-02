# NCI (Nexo Creative Inventory)

NCI is a client-side Fabric mod that integrates with the Nexo plugin to show Nexo-defined items and blocks directly in the vanilla Creative inventory. It removes the need to browse `/nexo inventory` for most Creative workflow use cases.

NCI updates dynamically when the server sends refreshed registry data (for example, after `/nexo reload`).

## Features

- Adds Nexo item groups to Creative inventory tabs.
- Adds synced Nexo items to the Creative search tab.
- Refreshes tab contents automatically after server-side Nexo registry changes.
- Works client-side only (server runs Nexo plugin; client runs this mod).

## Installation

1. Install the appropriate Fabric Loader for the selected NCI version.
2. Download the appropriate Fabric API for the selected NCI version.
3. Download and place the NCI `.jar` in your client `mods` folder, along with the Fabric API `.jar`.
4. Join a server running Nexo `1.21+`.
5. Ensure the player account has the `nexo.command.inventory` permission node, then use `/n rl items` to rescan and resend items to NCI clients.

## Server Requirements

- Nexo inventory sync must be enabled (default behavior for Nexo `1.21+`).
- The player must have permission node `nexo.command.inventory`.
- The feature is intended for Creative users.

## Usage

1. Join a compatible multiplayer server.
2. Enter Creative mode.
3. Open Creative inventory and use the page selector to move between vanilla tabs and Nexo tabs.
4. Use the Creative search tab to find synced Nexo items quickly.

## Versioning

Release artifacts are named with both mod version and minecraft version values:

- `NCI-v<mod_version>-<minecraft_version>.jar`

## Protocol and Behavior Notes

- Client/server registry sync uses protocol negotiation by version range.
- Client advertises its minimum and maximum supported protocol versions.
- Server responds with the highest mutually supported protocol version.
- If there is no shared protocol version, sync is rejected as incompatible.
- Permission denial is handled separately and also prevents sync.

## Troubleshooting

- No Nexo tabs visible:
  - Confirm server is running Nexo `1.21+`.
  - Confirm your client is in Creative mode.
  - Confirm your account has `nexo.command.inventory`.
  - Confirm Fabric Loader/Fabric API and Minecraft versions match this release.
- Items outdated after reload:
  - Reconnect if the server did not push a fresh registry packet.
- Mod loads but nothing syncs:
  - Check client logs for Nexo handshake/protocol warnings.

## Development

Build locally with:

```bash
./gradlew clean build
```

Output artifacts are written to `build/libs/`.

## Credits and Distribution

Suggested/developed by [ZoeWithTheE](https://github.com/ZoeWithTheE) and implemented by [Boy0000](https://github.com/Boy0000).

Public release channels:

- [Modrinth](https://modrinth.com/mod/nci)
- [GitHub](https://github.com/ZoeWithTheE/NCI)
- [Patreon Early Access](https://www.patreon.com/cw/MNIZ/membership)

## License

This repository is currently under a proprietary license. See the [LICENSE](https://github.com/ZoeWithTheE/NCI/blob/master/LICENSE) agreement for usage and redistribution terms.
