# OakOverflow

Smart inventory overflow storage system for Paper servers. When a player's inventory is full, items from plugins and commands are automatically stored and can be claimed later.

## Features

- Automatic interception of plugin/command-spawned items when inventory is full
- Rescue of items the server would otherwise destroy when a protection plugin blocks a drop
- GUI-based item claiming with stacking, pagination, sorting, and sound effects
- Join notifications with clickable claim links
- Action bar reminders for unclaimed items
- Item expiry warnings when auto-deletion is approaching
- Admin tools to view, clear, purge, and manage overflow storage
- Database migration between SQLite and MySQL
- PhoenixCratesPro integration (intercepts crate rewards)
- PlaceholderAPI placeholders
- Fully customizable MiniMessage-formatted messages

## Requirements

- Paper 26.1.2+
- Java 25+

## Installation

1. Drop the JAR into your `plugins/` folder
2. Restart the server
3. Edit `plugins/OakOverflow/config.yml` to your liking
4. Use `/oakoverflow reload` to apply changes

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/overflow` | Open the overflow GUI | `oakoverflow.use` |
| `/overflow help` | Show available commands | `oakoverflow.use` |
| `/overflow count` | Show unclaimed item count | `oakoverflow.use` |
| `/overflow claimall` | Claim all items at once | `oakoverflow.use` |
| `/oakoverflow view <player>` | View a player's overflow items | `oakoverflow.admin.view` |
| `/oakoverflow clear <player>` | Clear a player's overflow items | `oakoverflow.admin.clear` |
| `/oakoverflow clearall` | Clear ALL overflow items | `oakoverflow.admin.clearall` |
| `/oakoverflow stats` | View database statistics | `oakoverflow.admin.stats` |
| `/oakoverflow reload` | Reload configuration | `oakoverflow.admin.reload` |
| `/oakoverflow purge` | Remove corrupted items | `oakoverflow.admin.purge` |
| `/oakoverflow migrate <sqlite\|mysql>` | Migrate database | `oakoverflow.admin.migrate` |

**Aliases:** `/of`, `/unclaimed`, `/uc` for player commands; `/oof`, `/oa` for admin commands.

## Permissions

| Permission | Description | Default |
|------------|-------------|---------|
| `oakoverflow.use` | Access basic overflow features | Everyone |
| `oakoverflow.notify` | Receive item notifications | Everyone |
| `oakoverflow.bypass` | Items always go to inventory | false |
| `oakoverflow.bypass.limits` | Bypass max items limit | false |
| `oakoverflow.admin.*` | All admin permissions | OP |
| `oakoverflow.admin` | Base admin permission | OP |
| `oakoverflow.admin.view` | View other players' items | OP |
| `oakoverflow.admin.clear` | Clear a player's items | OP |
| `oakoverflow.admin.clearall` | Clear all items | OP |
| `oakoverflow.admin.stats` | View statistics | OP |
| `oakoverflow.admin.reload` | Reload configuration | OP |
| `oakoverflow.admin.purge` | Purge corrupted items | OP |
| `oakoverflow.admin.migrate` | Migrate database | OP |
| `oakoverflow.*` | All permissions | OP |

## Configuration

Key settings in `config.yml`:

- **Performance**: Interception radius and ambiguity threshold for multi-player scenarios
- **Drop rescue**: Catch items the server deletes when a protection plugin blocks a drop
- **Limits**: Max items per player, auto-delete after X days
- **Notifications**: Join notifications, receive notifications, action bar updates, expiry warnings
- **GUI**: Size, items per page, sorting, capacity indicator, sounds
- **Database**: SQLite (default) or MySQL with connection pooling
- **Messages**: All player-facing messages are customizable with MiniMessage formatting

See the generated `config.yml` for full documentation of all options.

## Stacking

Storage writes one row per item received. That insert-only design is deliberate: merging into an existing row means read-then-write, which races against concurrent adds and claims and can duplicate or swallow items.

Stacking is therefore a display concern. The GUI gathers entries holding the same item from the same source and repacks them into full stack-sized slots, so it reads exactly like a chest: a player who picks up 300 emeralds with a full inventory sees 64/64/64/64/44 across five slots, not 300. Every slot shows its true count.

Slots fill to the stack size regardless of where entry boundaries fall, so an entry can straddle two slots (three 40-item entries lay out as 64 + 56, with the middle entry split between them). Claiming a slot hands over its portion and writes the rest of the consumed entry back to overflow, where it reappears in the refreshed view.

This can't duplicate items: an entry is only ever given from after a claim wins its atomic `claimed = 0` guarded mark, an entry marks exactly once, and everything the winner doesn't hand over is written back in full. A second click on another slice of the same entry loses the mark and gives nothing.

Counts (`%oakoverflow_unclaimed_count%`, notifications, `limits.max-items-per-player`) measure items rather than rows, so they agree with what the GUI shows.

## Blocked Drop Rescue

When a container closes with items still inside it (a villager's payment slots, a crafting grid, an anvil) and the player's inventory is full, the server tries to drop the leftovers on the ground. If a protection plugin blocks item drops in that area, the server hands back only what fits and **silently deletes the rest**.

With `drop-rescue.enabled` (on by default), the part that would be deleted goes to overflow storage instead. Items that fit still go straight to the player, so nothing duplicates, and normal player drops (Q key) are never affected.

`oakoverflow.bypass` does not apply here: it means "let my items drop normally", and on this path they cannot drop at all.

## PhoenixCratesPro Integration

OakOverflow can intercept PhoenixCratesPro crate rewards and redirect overflow items to the `/overflow` system. Enable `integration.phoenixcrates.enabled` in config.yml.

PhoenixCrates' reward event is not cancellable, so items will exist in both databases. Disable PhoenixCrates' built-in `/crate claim` command in its own config to prevent players from claiming duplicates.

## Placeholders

Requires [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/).

| Placeholder | Description |
|-------------|-------------|
| `%oakoverflow_unclaimed_count%` | Number of unclaimed items |
| `%oakoverflow_has_items%` | `true`/`false` if player has items |
| `%oakoverflow_has_items_yes_no%` | `Yes`/`No` if player has items |
