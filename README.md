# LitePaths

LitePaths gradually wears eligible ground beneath travelers into paths. It targets Paper 1.21.11 and later, uses the Paper API without version-specific internals, and compiles to Java 21 bytecode.

## Commands

- `/litepaths toggle` — enable or disable automatic path wear.
- `/litepaths reload` — reload configuration.
- `/litepaths status` — show current state and detected claim plugins.
- `/litepaths create` — mark the first endpoint of a manual straight path; run it again at the second endpoint to preview and create the route.
- `/litepaths cancel` — cancel a pending manual route.

All commands require `litepaths.admin` (granted to operators by default). Manual routes use the same eligible-ground rules and protection checks as automatic paths. The route between endpoints is straight and follows the sampled surface.

## Claim protection

Towny, WorldGuard, and GriefPrevention are optional integrations. The plugin detects installed integrations and avoids changing claimed/protected locations. WorldEdit is not a claim plugin; WorldGuard regions provide the protection boundary for WorldEdit-managed areas.

## Update checking

Set `update-check.repository` in `plugins/LitePaths/config.yml` to a GitHub repository such as `owner/repository`. Checks use the GitHub Releases API asynchronously and only notify operators when a newer release is available. Checking is disabled until a repository is configured.

## Lightweight storage

Location wear counts are held in memory and saved to one compact binary file, `plugins/LitePaths/usage.dat`. No per-block files or player-placement history are kept. `storage.max-locations` caps stored locations; reaching the cap stops adding new ones while existing paths remain usable. World block inspection and changes run on the server thread; network update checks and file serialization run asynchronously.
