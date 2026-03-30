# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- Migrate to OakheartLib shared library for config management (ConfigManager), message handling (MessageManager), command registration (CommandRegistrar), and debug logging (DebugLogger)
- Messages moved from config.yml to dedicated messages.yml (auto-migration on first load)
- Prefix `<prefix>` tag replaced with baked-in prefix text in all message templates
- GUI messages flattened from nested name/lore structure to flat keys (e.g. `gui.claim-all-button.name` becomes `gui.claim-all-button-name`)

- MySQL SSL config now uses `ssl-mode` (DISABLED/PREFERRED/REQUIRED/VERIFY_CA/VERIFY_IDENTITY) instead of legacy `use-ssl`/`verify-certificate` properties for MySQL Connector/J 9.x compatibility
- Removed `useUnicode` from default MySQL connection properties (handled automatically by modern Connector/J)
- PlaceholderAPI expansion now depends on the API interface instead of the implementation class
- Purge command releases the database connection between scan and delete phases to avoid holding pooled connections during lengthy operations

### Fixed

- Missing error handling on expiry warning database query — failures are now logged instead of silently swallowed
- GUI sort-by-type used a fragile magic string to sort corrupted items last; now uses a proper two-level comparator
- GUI claim-all now checks if inventory is completely full before attempting the claim, avoiding unnecessary claim/re-overflow churn

### Added

- Universal item spawn interception for overflow storage (CUSTOM and COMMAND spawn reasons)
- SQLite (default) and MySQL database backends with async operations
- Database migration command `/oakoverflow migrate sqlite|mysql` to transfer data between backends
- Paginated GUI for viewing and claiming overflow items
- GUI sorting: cycle through Newest, Oldest, By Type, and By Source sort modes
- Overflow capacity indicator in GUI when item limits are configured (shows count/max and percentage)
- Individual item claiming with inventory space checking and ownership verification
- Claim-all command and GUI button — claims as many items as fit and returns the rest to overflow
- Join notifications with clickable command link
- Item expiry warnings: notify players on join when items are nearing auto-deletion
- Action bar notifications for unclaimed items
- Item received notifications (chat or action bar)
- Configurable interception radius and ambiguity threshold
- Per-player item limits with bypass permission
- Auto-deletion of old items on a configurable schedule
- Admin commands: view, clear, clearall (with confirmation), stats, reload, purge, migrate
- Admin view-only mode for inspecting other players' overflow (online and offline)
- PhoenixCratesPro integration (reward interception when inventory overflows)
- PlaceholderAPI expansion (unclaimed count, has items)
- Corrupted item detection with graceful fallback display
- Public API interface for external plugin integration
- Custom events: ItemAddedToOverflowEvent, ItemClaimedEvent, ItemsClaimedAllEvent
- `/overflow help` subcommand showing available player commands
- `oakoverflow.admin.*` wildcard permission grouping all admin sub-permissions
- Player help messages configurable in config.yml
- Configurable GUI button materials and sounds
- MiniMessage formatting for all player-facing messages
- bStats metrics
