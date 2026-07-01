# SoulRoles

A Paper 26.2 test plugin for a role-based hearts game mode.

## Requirements

- Java 25 or newer
- Gradle
- Paper 26.2 server

## Build

```bash
gradle build
```

The plugin jar will be created at:

```text
build/libs/SoulRoles-0.1.0.jar
```

## Install

Copy the jar into your Paper server's `plugins/` directory and restart the server.

The plugin creates:

```text
plugins/SoulRoles/config.yml
plugins/SoulRoles/data.yml
```

## Commands

All commands require `soulroles.admin`.

```text
/soulroles status [player]
/soulroles reset
/soulroles reload
/soulroles set <player> <A|B|NORMAL>
/soulroles give <soul|heart|resetter|swap|add_a> [amount]
```

## Gameplay Prototype

- Group A players have 5 hearts by default.
- Group B players have 20 hearts by default.
- Normal players have 10 hearts by default.
- A role survives logout and server restart.
- If a Group A player kills another player:
  - the victim becomes Group A,
  - the killer becomes normal,
  - Group B is reshuffled with normal players.
- If a Group A player dies, they drop a Soul item.
- Craftable Heart adds one permanent heart to the user.
- Class Resetter randomly reassigns all tracked players.
- Class Swap swaps all Group A and Group B players.
- Add Class A randomly picks one normal player and makes them Group A.

Custom textures are not included. The plugin tags items with persistent data and assigns custom model data values so a resource pack can retexture them later.
