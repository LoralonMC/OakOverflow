# OakOverflow

Smart inventory overflow storage system for Paper servers. When a player's inventory is full, items from plugins and commands are automatically stored and can be claimed later.

## Features

- Automatic interception of plugin/command-spawned items when inventory is full
- GUI-based item claiming with pagination, sorting, and sound effects
- Join notifications with clickable claim links
- Action bar reminders for unclaimed items
- Item expiry warnings when auto-deletion is approaching
- Admin tools to view, clear, purge, and manage overflow storage
- Database migration between SQLite and MySQL
- PhoenixCratesPro integration (intercepts crate rewards)
- PlaceholderAPI placeholders
- Fully customizable MiniMessage-formatted messages

## Requirements

- Paper 1.21.10+
- Java 21+

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
| `oakoverflow.bypass.limits` | Bypass max items limit | OP |
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
- **Limits**: Max items per player, auto-delete after X days
- **Notifications**: Join notifications, receive notifications, action bar updates, expiry warnings
- **GUI**: Size, items per page, sorting, capacity indicator, sounds
- **Database**: SQLite (default) or MySQL with connection pooling
- **Messages**: All player-facing messages are customizable with MiniMessage formatting

See the generated `config.yml` for full documentation of all options.

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
