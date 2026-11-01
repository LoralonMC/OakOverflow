# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.1.1] - 2026-11-01

### Security

- Blocked drop rescue stored drops by creative players, so a stack dragged out of the creative
  menu with a full inventory (cancelled by OakCreative) became a real overflow item claimable in
  survival. Creative and spectator players are never rescued.

### Fixed

- A player who disconnected between clicking a claim and receiving it lost the claimed rows:
  they were marked claimed and then handed to an inventory that had already been saved. The
  rows are now put back into overflow.
- A claim in flight during shutdown was marked claimed and never delivered. Shutdown now
  restores any claim still waiting for its give, and a claim that finishes while the plugin is
  disabling restores its rows instead of failing to schedule.
- An API add larger than one stack is stored as several rows, but only the first row's result
  reached the caller, so a failure on a later row went unnoticed. Every row is now awaited.
- A double-click that found its rows already taken by the first click reported a database error.

## [1.1.0] - 2026-10-20

### Added

- Identical items now stack in the overflow GUI. Storage still writes one entry per item received (that insert-only design is what keeps concurrent adds and claims race-free), but the GUI gathers matching entries and repacks them into full stack-sized slots that read exactly like a chest: 300 separately-received emeralds show as 64/64/64/64/44 across five slots instead of 300. Every slot shows its true count, and clicking one claims it
- Slots fill to the stack size regardless of where entry boundaries fall, so an entry can straddle two slots. Claiming a slot hands over its portion and writes the rest of the consumed entry back to overflow, which keeps the display dense without any entry ever being shown twice
- Stored entries are split at one stack on write, so a 100-item grant becomes entries of 64 and 36
- Blocked drop rescue (`drop-rescue.enabled`, on by default): when a protection plugin cancels an item drop and the player's inventory is full, the server silently deletes the leftovers. Those items now go to overflow storage instead. Fixes villager-trade change and crafting-grid contents vanishing inside claims that disable item drops
- Config migration support (`ConfigMigrator`), so structural config changes update an admin's file instead of orphaning old keys
- Comment sync on config updates, so documentation changes to shipped defaults reach existing config.yml files without clobbering admin-authored comments

### Changed

- Targets Paper 26.3 (`api-version: '26.3'`, built against paper-api 26.3). This build no longer loads on 26.1.2.
- **`advanced.max-query-limit` now defaults to -1 (unlimited).** The old 10000 cap truncated a newest-first query, so once a player passed it their *oldest* items silently disappeared from both `/overflow` and claimall. The v3 migration resets existing configs to the new default; a cap that is still set now logs a warning whenever it truncates
- **Claiming only takes what fits.** Previously every row was marked claimed, handed over, and the rejects written back as new rows, which churned the whole table and reset every leftover item's age on any claim with a nearly-full inventory. Rows that don't fit are now left untouched in overflow
- **Unclaimed counts report items, not storage rows.** A single waiting stack of 64 used to be announced as "1 unclaimed item". This affects `%oakoverflow_unclaimed_count%`, join and action bar notifications, and `limits.max-items-per-player`, which now measures items as its own description always said
- Claim messages report item counts rather than row counts, matching the stacked GUI
- Bump OakheartLib to 1.3.1
- Invalid `database.type` now aborts startup instead of being logged and ignored
- `advanced.async-threads` documented as MySQL-only (SQLite always uses a single writer thread)
- Default `gui.title` is no longer coloured
- Migrate to OakheartLib shared library for config management (ConfigManager), message handling (MessageManager), command registration (CommandRegistrar), and debug logging (DebugLogger)
- Messages moved from config.yml to dedicated messages.yml (auto-migration on first load)
- Prefix `<prefix>` tag replaced with baked-in prefix text in all message templates
- GUI messages flattened from nested name/lore structure to flat keys (e.g. `gui.claim-all-button.name` becomes `gui.claim-all-button-name`)

- MySQL SSL config now uses `ssl-mode` (DISABLED/PREFERRED/REQUIRED/VERIFY_CA/VERIFY_IDENTITY) instead of legacy `use-ssl`/`verify-certificate` properties for MySQL Connector/J 9.x compatibility
- Removed `useUnicode` from default MySQL connection properties (handled automatically by modern Connector/J)
- PlaceholderAPI expansion now depends on the API interface instead of the implementation class
- Purge command releases the database connection between scan and delete phases to avoid holding pooled connections during lengthy operations

### Removed

- `gui.auto-update` config key. Nothing ever read it; the v2 migration removes it from existing configs
- `DataStore#updateItemAmount`, unused since row merging was dropped
- The processed-item map and its 10-second cleanup task in the interception listener. `ItemSpawnEvent` fires once per entity, so it never deduplicated anything
- `gui.stacked-amount-lore`. Slots now always render their true contents, so there is nothing left for it to explain

### Fixed

- Endless drop loop when a player at their overflow item limit had a full inventory: the plugin dropped the item on the ground, immediately intercepted its own drop, hit the limit again, and repeated. Self-drops are now tagged and skipped
- PhoenixCratesPro rewards with more than one item measured every item against the same unclaimed inventory space, under-counting the overflow for the second item onward
- Re-adding a leftover stack to overflow after a claim could fail silently, losing an item that was already marked claimed. Failures are now logged as SEVERE
- `UnclaimedItemsGUI#getInventory` could return null before the async item load finished, despite its `@NotNull` contract
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
- Claim-all command and GUI button, claiming as many items as fit and leaving the rest in overflow
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

