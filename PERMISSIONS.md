# Exemption command permissions

The exemption commands use Fabric Permissions API. If no compatible permission manager is installed, they require operator permission level 2.

LuckPerms and other compatible permission managers can grant these nodes to non-operators:

| Node | Command |
| --- | --- |
| `hardcoreworldreset.exemptions.add` | `/hwr exemptions add <player>` |
| `hardcoreworldreset.exemptions.remove` | `/hwr exemptions remove <player>` |
| `hardcoreworldreset.exemptions.list` | `/hwr exemptions list` |
| `hardcoreworldreset.exemptions.enable` | `/hwr exemptions on` |
| `hardcoreworldreset.exemptions.disable` | `/hwr exemptions off` |

For example, grant a player permission to view the exemption list:

```text
/lp user <player> permission set hardcoreworldreset.exemptions.list true
```

Grant the same permission to a LuckPerms group:

```text
/lp group <group> permission set hardcoreworldreset.exemptions.list true
```
