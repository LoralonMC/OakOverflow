package dev.oakheart.oakoverflow.storage;

import dev.oakheart.oakoverflow.config.ConfigManager;
import dev.oakheart.oakoverflow.model.OverflowItem;
import dev.oakheart.oakoverflow.util.ItemSerializer;
import org.bukkit.inventory.ItemStack;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Base class for database implementations. Provides shared query logic;
 * subclasses handle connection lifecycle, DDL, and statement caching strategy.
 */
public abstract class AbstractDatabase implements DataStore {

    private static final int SCHEMA_VERSION = 1;
    private static final int BATCH_CHUNK_SIZE = 500;

    // SQL constants for cacheable prepared statements
    protected static final String SQL_INSERT_ITEM = """
            INSERT INTO overflow_items
            (player_uuid, player_name, item_data, amount, received_timestamp, source, claimed)
            VALUES (?, ?, ?, ?, ?, ?, 0)
            """;
    protected static final String SQL_GET_UNCLAIMED = """
            SELECT id, player_uuid, player_name, item_data, amount, received_timestamp, source,
                   claimed, claimed_timestamp
            FROM overflow_items
            WHERE player_uuid = ? AND claimed = 0
            ORDER BY received_timestamp DESC
            """;
    protected static final String SQL_GET_UNCLAIMED_LIMITED = SQL_GET_UNCLAIMED + " LIMIT ?";
    protected static final String SQL_GET_BY_ID = """
            SELECT id, player_uuid, player_name, item_data, amount, received_timestamp, source,
                   claimed, claimed_timestamp
            FROM overflow_items
            WHERE id = ?
            """;
    // Counts items, not rows. Storage keeps one row per receipt, so a row can hold a
    // stack of 64; counting rows told a player "1 unclaimed item" when 64 were waiting,
    // and disagreed with what the stacked GUI shows them.
    protected static final String SQL_COUNT_UNCLAIMED =
            "SELECT COALESCE(SUM(amount), 0) as count FROM overflow_items WHERE player_uuid = ? AND claimed = 0";
    protected static final String SQL_MARK_CLAIMED =
            "UPDATE overflow_items SET claimed = 1, claimed_timestamp = ? WHERE id = ? AND player_uuid = ? AND claimed = 0";
    protected static final String SQL_UNMARK_CLAIMED =
            "UPDATE overflow_items SET claimed = 0, claimed_timestamp = NULL WHERE id = ? AND player_uuid = ? AND claimed = 1";
    protected static final String SQL_CLEAR_PLAYER =
            "DELETE FROM overflow_items WHERE player_uuid = ? AND claimed = 0";
    protected static final String SQL_CLEAR_ALL =
            "DELETE FROM overflow_items WHERE claimed = 0";
    protected static final String SQL_DELETE_OLD_UNCLAIMED =
            "DELETE FROM overflow_items WHERE claimed = 0 AND received_timestamp < ?";
    protected static final String SQL_DELETE_OLD_CLAIMED =
            "DELETE FROM overflow_items WHERE claimed = 1 AND claimed_timestamp < ?";
    protected static final String SQL_COUNT_EXPIRING =
            "SELECT COUNT(*) as count FROM overflow_items WHERE player_uuid = ? AND claimed = 0 AND received_timestamp < ?";
    protected static final String SQL_EXPORT_ALL =
            "SELECT player_uuid, player_name, item_data, amount, received_timestamp, source, claimed, claimed_timestamp FROM overflow_items";
    protected static final String SQL_IMPORT_ITEM = """
            INSERT INTO overflow_items
            (player_uuid, player_name, item_data, amount, received_timestamp, source, claimed, claimed_timestamp)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    protected final Logger logger;
    protected final ConfigManager configManager;
    protected final ExecutorService executor;

    private int cachedSchemaVersion;

    protected AbstractDatabase(Logger logger, ConfigManager configManager, ExecutorService executor) {
        this.logger = logger;
        this.configManager = configManager;
        this.executor = executor;
    }

    protected abstract Connection getConnection() throws SQLException;
    protected abstract void releaseConnection(Connection conn) throws SQLException;
    protected abstract void createTables() throws SQLException;
    protected abstract void initializeConnection() throws Exception;
    protected abstract void closeResources();

    /**
     * Creates or retrieves a PreparedStatement for the given SQL.
     * Subclasses may override to implement statement caching.
     */
    protected PreparedStatement prepareStatement(Connection conn, String sql) throws SQLException {
        return conn.prepareStatement(sql);
    }

    /**
     * Creates or retrieves a PreparedStatement with the given flags (e.g. RETURN_GENERATED_KEYS).
     * Subclasses may override to implement statement caching.
     */
    protected PreparedStatement prepareStatement(Connection conn, String sql, int flags) throws SQLException {
        return conn.prepareStatement(sql, flags);
    }

    /**
     * Returns a PreparedStatement after use. Default closes the statement.
     * Subclasses with statement caching should override to keep statements open.
     */
    protected void returnStatement(PreparedStatement stmt) {
        if (stmt != null) {
            try {
                stmt.close();
            } catch (SQLException ignored) {}
        }
    }

    @Override
    public boolean initialize() {
        try {
            initializeConnection();
            createTables();

            int currentVersion = querySchemaVersion();
            if (currentVersion == 0) {
                insertSchemaVersion(SCHEMA_VERSION);
                logger.info("Initialized database schema version " + SCHEMA_VERSION);
                cachedSchemaVersion = SCHEMA_VERSION;
            } else if (currentVersion < SCHEMA_VERSION) {
                logger.info("Migrating database from version " + currentVersion + " to " + SCHEMA_VERSION);
                insertSchemaVersion(SCHEMA_VERSION);
                cachedSchemaVersion = SCHEMA_VERSION;
            } else {
                cachedSchemaVersion = currentVersion;
            }

            return true;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to initialize database", e);
            return false;
        }
    }

    @Override
    public int getSchemaVersion() {
        return cachedSchemaVersion;
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        closeResources();
    }

    private int querySchemaVersion() {
        try {
            Connection conn = getConnection();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT MAX(version) as version FROM schema_version")) {
                if (rs.next()) {
                    return rs.getInt("version");
                }
            } finally {
                releaseConnection(conn);
            }
        } catch (SQLException e) {
            return 0;
        }
        return 0;
    }

    private void insertSchemaVersion(int version) throws SQLException {
        String sql = "INSERT INTO schema_version (version, applied_at, description) VALUES (?, ?, ?)";
        Connection conn = getConnection();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, version);
            stmt.setLong(2, System.currentTimeMillis());
            stmt.setString(3, "Schema version " + version);
            stmt.executeUpdate();
        } finally {
            releaseConnection(conn);
        }
    }

    // ========================================
    //          Shared Query Methods
    // ========================================

    @Override
    public CompletableFuture<OverflowItem> addOverflowItem(UUID playerUUID, String playerName,
                                                             ItemStack item, int amount, String source) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String itemData = ItemSerializer.serialize(item);

                if (ItemSerializer.isOversized(itemData)) {
                    logger.warning("Oversized item detected for player " + playerName +
                            ": " + ItemSerializer.getSerializedSize(itemData) + " characters");
                }

                long timestamp = System.currentTimeMillis();

                Connection conn = getConnection();
                try {
                    PreparedStatement stmt = prepareStatement(conn, SQL_INSERT_ITEM, Statement.RETURN_GENERATED_KEYS);
                    try {
                        stmt.setString(1, playerUUID.toString());
                        stmt.setString(2, playerName);
                        stmt.setString(3, itemData);
                        stmt.setInt(4, amount);
                        stmt.setLong(5, timestamp);
                        stmt.setString(6, source);

                        stmt.executeUpdate();

                        try (ResultSet rs = stmt.getGeneratedKeys()) {
                            if (rs.next()) {
                                int id = rs.getInt(1);
                                return new OverflowItem(id, playerUUID, playerName, item, amount,
                                        timestamp, source, false, null);
                            }
                        }
                    } finally {
                        returnStatement(stmt);
                    }
                } finally {
                    releaseConnection(conn);
                }

                throw new SQLException("Failed to get generated ID for overflow item");
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to add overflow item for " + playerName, e);
                throw new RuntimeException(e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<OverflowItem>> getUnclaimedItems(UUID playerUUID) {
        return getUnclaimedItems(playerUUID, -1);
    }

    @Override
    public CompletableFuture<List<OverflowItem>> getUnclaimedItems(UUID playerUUID, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<OverflowItem> items = new ArrayList<>();
            boolean useLimited = limit > 0;
            String sql = useLimited ? SQL_GET_UNCLAIMED_LIMITED : SQL_GET_UNCLAIMED;

            try {
                Connection conn = getConnection();
                try {
                    PreparedStatement stmt = prepareStatement(conn, sql);
                    try {
                        stmt.setString(1, playerUUID.toString());
                        if (useLimited) {
                            stmt.setInt(2, limit);
                        }

                        try (ResultSet rs = stmt.executeQuery()) {
                            while (rs.next()) {
                                try {
                                    items.add(parseOverflowItem(rs));
                                } catch (Exception e) {
                                    int itemId = rs.getInt("id");
                                    logger.warning("Failed to deserialize overflow item ID " + itemId + ": " + e.getMessage());
                                    items.add(OverflowItem.corrupted(
                                            itemId,
                                            UUID.fromString(rs.getString("player_uuid")),
                                            rs.getString("player_name"),
                                            rs.getInt("amount"),
                                            rs.getLong("received_timestamp"),
                                            rs.getString("source")
                                    ));
                                }
                            }
                        }
                    } finally {
                        returnStatement(stmt);
                    }
                } finally {
                    releaseConnection(conn);
                }
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to get unclaimed items for " + playerUUID, e);
            }

            return items;
        }, executor);
    }

    @Override
    public CompletableFuture<OverflowItem> getItemById(int id) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Connection conn = getConnection();
                try {
                    PreparedStatement stmt = prepareStatement(conn, SQL_GET_BY_ID);
                    try {
                        stmt.setInt(1, id);

                        try (ResultSet rs = stmt.executeQuery()) {
                            if (rs.next()) {
                                try {
                                    return parseOverflowItem(rs);
                                } catch (Exception e) {
                                    logger.warning("Failed to deserialize overflow item ID " + id + ": " + e.getMessage());
                                    return OverflowItem.corrupted(
                                            id,
                                            UUID.fromString(rs.getString("player_uuid")),
                                            rs.getString("player_name"),
                                            rs.getInt("amount"),
                                            rs.getLong("received_timestamp"),
                                            rs.getString("source")
                                    );
                                }
                            }
                        }
                    } finally {
                        returnStatement(stmt);
                    }
                } finally {
                    releaseConnection(conn);
                }
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to get item by ID " + id, e);
            }

            return null;
        }, executor);
    }

    @Override
    public CompletableFuture<Integer> getUnclaimedCount(UUID playerUUID) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Connection conn = getConnection();
                try {
                    PreparedStatement stmt = prepareStatement(conn, SQL_COUNT_UNCLAIMED);
                    try {
                        stmt.setString(1, playerUUID.toString());

                        try (ResultSet rs = stmt.executeQuery()) {
                            if (rs.next()) {
                                return rs.getInt("count");
                            }
                        }
                    } finally {
                        returnStatement(stmt);
                    }
                } finally {
                    releaseConnection(conn);
                }
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to get unclaimed count for " + playerUUID, e);
            }

            return 0;
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> markAsClaimed(int itemId, UUID playerUUID) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Connection conn = getConnection();
                try {
                    PreparedStatement stmt = prepareStatement(conn, SQL_MARK_CLAIMED);
                    try {
                        stmt.setLong(1, System.currentTimeMillis());
                        stmt.setInt(2, itemId);
                        stmt.setString(3, playerUUID.toString());

                        return stmt.executeUpdate() > 0;
                    } finally {
                        returnStatement(stmt);
                    }
                } finally {
                    releaseConnection(conn);
                }
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to mark item as claimed", e);
                return false;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<Integer>> markAsClaimedBatchReturningIds(UUID playerUUID, List<Integer> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        return CompletableFuture.supplyAsync(() -> {
            List<Integer> marked = new ArrayList<>();
            try {
                Connection conn = getConnection();
                try {
                    // One UPDATE per id (same guarded statement as the single-claim
                    // path) so each row reports whether it was still unclaimed. An
                    // IN(...) update only yields a total and cannot say which rows
                    // a racing single-claim already took.
                    PreparedStatement stmt = prepareStatement(conn, SQL_MARK_CLAIMED);
                    try {
                        long now = System.currentTimeMillis();
                        for (int itemId : itemIds) {
                            stmt.setLong(1, now);
                            stmt.setInt(2, itemId);
                            stmt.setString(3, playerUUID.toString());
                            if (stmt.executeUpdate() > 0) {
                                marked.add(itemId);
                            }
                        }
                    } finally {
                        returnStatement(stmt);
                    }
                } finally {
                    releaseConnection(conn);
                }
            } catch (SQLException e) {
                // Rows marked before the failure are still returned so the caller
                // gives them out; crash semantics stay "loss, never duplication".
                logger.log(Level.SEVERE, "Failed to batch mark items as claimed (per-id)", e);
            }
            return marked;
        }, executor);
    }

    @Override
    public CompletableFuture<Integer> unmarkClaimed(UUID playerUUID, List<Integer> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        return CompletableFuture.supplyAsync(() -> unmarkClaimedNow(playerUUID, itemIds), executor);
    }

    @Override
    public int unmarkClaimedNow(UUID playerUUID, List<Integer> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return 0;
        }
        int restored = 0;
        try {
            Connection conn = getConnection();
            try {
                PreparedStatement stmt = prepareStatement(conn, SQL_UNMARK_CLAIMED);
                try {
                    for (int itemId : itemIds) {
                        stmt.setInt(1, itemId);
                        stmt.setString(2, playerUUID.toString());
                        restored += stmt.executeUpdate();
                    }
                } finally {
                    returnStatement(stmt);
                }
            } finally {
                releaseConnection(conn);
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to restore claimed rows " + itemIds + " for " + playerUUID
                    + "; those items are marked claimed but were never handed over", e);
        }
        return restored;
    }

    @Override
    public CompletableFuture<Integer> markAsClaimedBatch(UUID playerUUID, List<Integer> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }

        return CompletableFuture.supplyAsync(() -> {
            int totalUpdated = 0;

            try {
                Connection conn = getConnection();
                try {
                    // Process in chunks to avoid exceeding SQL parameter limits
                    for (int start = 0; start < itemIds.size(); start += BATCH_CHUNK_SIZE) {
                        int end = Math.min(start + BATCH_CHUNK_SIZE, itemIds.size());
                        List<Integer> chunk = itemIds.subList(start, end);

                        String idPlaceholders = String.join(",", chunk.stream().map(id -> "?").toList());
                        String sql = "UPDATE overflow_items SET claimed = 1, claimed_timestamp = ? WHERE player_uuid = ? AND id IN ("
                                + idPlaceholders + ") AND claimed = 0";

                        // Dynamic SQL cannot be cached — different param count per chunk
                        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                            stmt.setLong(1, System.currentTimeMillis());
                            stmt.setString(2, playerUUID.toString());

                            for (int i = 0; i < chunk.size(); i++) {
                                stmt.setInt(i + 3, chunk.get(i));
                            }

                            totalUpdated += stmt.executeUpdate();
                        }
                    }
                } finally {
                    releaseConnection(conn);
                }
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to batch mark items as claimed", e);
            }

            return totalUpdated;
        }, executor);
    }

    @Override
    public CompletableFuture<Integer> clearPlayerItems(UUID playerUUID) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Connection conn = getConnection();
                try {
                    PreparedStatement stmt = prepareStatement(conn, SQL_CLEAR_PLAYER);
                    try {
                        stmt.setString(1, playerUUID.toString());
                        return stmt.executeUpdate();
                    } finally {
                        returnStatement(stmt);
                    }
                } finally {
                    releaseConnection(conn);
                }
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to clear player items", e);
                return 0;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Integer> clearAllItems() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Connection conn = getConnection();
                try {
                    PreparedStatement stmt = prepareStatement(conn, SQL_CLEAR_ALL);
                    try {
                        return stmt.executeUpdate();
                    } finally {
                        returnStatement(stmt);
                    }
                } finally {
                    releaseConnection(conn);
                }
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to clear all items", e);
                return 0;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<DatabaseStats> getStatistics() {
        return CompletableFuture.supplyAsync(() -> {
            String sql = """
                    SELECT
                        COUNT(*) as total,
                        SUM(CASE WHEN claimed = 0 THEN 1 ELSE 0 END) as unclaimed,
                        COUNT(DISTINCT CASE WHEN claimed = 0 THEN player_uuid END) as players,
                        MIN(CASE WHEN claimed = 0 THEN received_timestamp END) as oldest
                    FROM overflow_items
                    """;

            try {
                Connection conn = getConnection();
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    if (rs.next()) {
                        int totalItems = rs.getInt("total");
                        int unclaimedItems = rs.getInt("unclaimed");
                        int uniquePlayers = rs.getInt("players");
                        long oldest = rs.getLong("oldest");
                        long oldestItemAge = oldest > 0 ? System.currentTimeMillis() - oldest : 0;

                        return new DatabaseStats(totalItems, unclaimedItems, uniquePlayers, oldestItemAge);
                    }
                } finally {
                    releaseConnection(conn);
                }
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to get database statistics", e);
            }
            return new DatabaseStats(0, 0, 0, 0);
        }, executor);
    }

    @Override
    public CompletableFuture<Integer> deleteOlderThan(int days) {
        return CompletableFuture.supplyAsync(() -> {
            long cutoffTime = System.currentTimeMillis() - (days * 24L * 60 * 60 * 1000);

            try {
                Connection conn = getConnection();
                try {
                    PreparedStatement stmt = prepareStatement(conn, SQL_DELETE_OLD_UNCLAIMED);
                    try {
                        stmt.setLong(1, cutoffTime);
                        return stmt.executeUpdate();
                    } finally {
                        returnStatement(stmt);
                    }
                } finally {
                    releaseConnection(conn);
                }
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to delete old items", e);
                return 0;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Integer> deleteClaimedOlderThan(int days) {
        return CompletableFuture.supplyAsync(() -> {
            long cutoffTime = System.currentTimeMillis() - (days * 24L * 60 * 60 * 1000);

            try {
                Connection conn = getConnection();
                try {
                    PreparedStatement stmt = prepareStatement(conn, SQL_DELETE_OLD_CLAIMED);
                    try {
                        stmt.setLong(1, cutoffTime);
                        return stmt.executeUpdate();
                    } finally {
                        returnStatement(stmt);
                    }
                } finally {
                    releaseConnection(conn);
                }
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to delete old claimed items", e);
                return 0;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Integer> getExpiringItemCount(UUID playerUUID, long olderThanTimestamp) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Connection conn = getConnection();
                try {
                    PreparedStatement stmt = prepareStatement(conn, SQL_COUNT_EXPIRING);
                    try {
                        stmt.setString(1, playerUUID.toString());
                        stmt.setLong(2, olderThanTimestamp);

                        try (ResultSet rs = stmt.executeQuery()) {
                            if (rs.next()) {
                                return rs.getInt("count");
                            }
                        }
                    } finally {
                        returnStatement(stmt);
                    }
                } finally {
                    releaseConnection(conn);
                }
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to get expiring item count for " + playerUUID, e);
            }
            return 0;
        }, executor);
    }

    @Override
    public CompletableFuture<Integer> purgeCorruptedItems() {
        return CompletableFuture.supplyAsync(() -> {
            List<Integer> corruptedIds = new ArrayList<>();

            // Phase 1: Scan for corrupted items
            try {
                Connection conn = getConnection();
                try {
                    String scanSql = "SELECT id, item_data FROM overflow_items WHERE claimed = 0 ORDER BY id LIMIT ? OFFSET ?";
                    int offset = 0;

                    while (true) {
                        int rowsRead = 0;

                        try (PreparedStatement scanStmt = conn.prepareStatement(scanSql)) {
                            scanStmt.setInt(1, BATCH_CHUNK_SIZE);
                            scanStmt.setInt(2, offset);

                            try (ResultSet rs = scanStmt.executeQuery()) {
                                while (rs.next()) {
                                    rowsRead++;
                                    int id = rs.getInt("id");
                                    String itemData = rs.getString("item_data");
                                    if (!ItemSerializer.isValid(itemData)) {
                                        corruptedIds.add(id);
                                    }
                                }
                            }
                        }

                        if (rowsRead < BATCH_CHUNK_SIZE) {
                            break;
                        }
                        offset += BATCH_CHUNK_SIZE;
                    }
                } finally {
                    releaseConnection(conn);
                }
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to scan for corrupted items", e);
                return 0;
            }

            if (corruptedIds.isEmpty()) {
                return 0;
            }

            // Phase 2: Delete corrupted items (separate connection to avoid holding
            // a pooled connection during the potentially lengthy scan phase)
            try {
                Connection conn = getConnection();
                try {
                    int totalDeleted = 0;
                    for (int start = 0; start < corruptedIds.size(); start += BATCH_CHUNK_SIZE) {
                        int end = Math.min(start + BATCH_CHUNK_SIZE, corruptedIds.size());
                        List<Integer> chunk = corruptedIds.subList(start, end);

                        String placeholders = String.join(",", chunk.stream().map(id -> "?").toList());
                        String sql = "DELETE FROM overflow_items WHERE id IN (" + placeholders + ")";

                        try (PreparedStatement delStmt = conn.prepareStatement(sql)) {
                            for (int i = 0; i < chunk.size(); i++) {
                                delStmt.setInt(i + 1, chunk.get(i));
                            }
                            totalDeleted += delStmt.executeUpdate();
                        }
                    }
                    return totalDeleted;
                } finally {
                    releaseConnection(conn);
                }
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to delete corrupted items", e);
                return 0;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<RawOverflowItem>> exportAllItems() {
        return CompletableFuture.supplyAsync(() -> {
            List<RawOverflowItem> items = new ArrayList<>();
            String sql = SQL_EXPORT_ALL + " ORDER BY id LIMIT ? OFFSET ?";

            try {
                Connection conn = getConnection();
                try {
                    int offset = 0;
                    while (true) {
                        int rowsRead = 0;

                        PreparedStatement stmt = prepareStatement(conn, sql);
                        try {
                            stmt.setInt(1, BATCH_CHUNK_SIZE);
                            stmt.setInt(2, offset);

                            try (ResultSet rs = stmt.executeQuery()) {
                                while (rs.next()) {
                                    rowsRead++;
                                    UUID playerUUID = UUID.fromString(rs.getString("player_uuid"));
                                    String playerName = rs.getString("player_name");
                                    String itemData = rs.getString("item_data");
                                    int amount = rs.getInt("amount");
                                    long receivedTimestamp = rs.getLong("received_timestamp");
                                    String source = rs.getString("source");
                                    boolean claimed = rs.getInt("claimed") == 1;
                                    Long claimedTimestamp = rs.getLong("claimed_timestamp");
                                    if (rs.wasNull()) {
                                        claimedTimestamp = null;
                                    }

                                    items.add(new RawOverflowItem(playerUUID, playerName, itemData, amount,
                                            receivedTimestamp, source, claimed, claimedTimestamp));
                                }
                            }
                        } finally {
                            returnStatement(stmt);
                        }

                        if (rowsRead < BATCH_CHUNK_SIZE) {
                            break; // Last chunk
                        }
                        offset += BATCH_CHUNK_SIZE;
                    }
                } finally {
                    releaseConnection(conn);
                }
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to export all items", e);
                throw new RuntimeException("Export failed", e);
            }

            return items;
        }, executor);
    }

    @Override
    public CompletableFuture<Integer> importItems(List<RawOverflowItem> items) {
        if (items == null || items.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }

        return CompletableFuture.supplyAsync(() -> {
            int totalImported = 0;

            try {
                Connection conn = getConnection();
                try {
                    conn.setAutoCommit(false);

                    // Clear target table to prevent duplicates on retry
                    try (Statement clearStmt = conn.createStatement()) {
                        clearStmt.executeUpdate("DELETE FROM overflow_items");
                    }

                    for (int start = 0; start < items.size(); start += BATCH_CHUNK_SIZE) {
                        int end = Math.min(start + BATCH_CHUNK_SIZE, items.size());

                        try (PreparedStatement stmt = conn.prepareStatement(SQL_IMPORT_ITEM)) {
                            for (int i = start; i < end; i++) {
                                RawOverflowItem item = items.get(i);
                                stmt.setString(1, item.playerUUID().toString());
                                stmt.setString(2, item.playerName());
                                stmt.setString(3, item.itemData());
                                stmt.setInt(4, item.amount());
                                stmt.setLong(5, item.receivedTimestamp());
                                stmt.setString(6, item.source());
                                stmt.setInt(7, item.claimed() ? 1 : 0);
                                if (item.claimedTimestamp() != null) {
                                    stmt.setLong(8, item.claimedTimestamp());
                                } else {
                                    stmt.setNull(8, java.sql.Types.BIGINT);
                                }
                                stmt.addBatch();
                            }

                            int[] results = stmt.executeBatch();
                            for (int r : results) {
                                if (r >= 0) totalImported += r;
                                else if (r == Statement.SUCCESS_NO_INFO) totalImported++;
                            }
                        }
                    }

                    conn.commit();
                } catch (SQLException e) {
                    try {
                        conn.rollback();
                    } catch (SQLException rollbackEx) {
                        logger.log(Level.SEVERE, "Failed to rollback import transaction", rollbackEx);
                    }
                    throw e;
                } finally {
                    try {
                        conn.setAutoCommit(true);
                    } catch (SQLException ignored) {}
                    releaseConnection(conn);
                }
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to import items", e);
                throw new RuntimeException("Import failed", e);
            }

            return totalImported;
        }, executor);
    }

    protected OverflowItem parseOverflowItem(ResultSet rs) throws SQLException {
        int id = rs.getInt("id");
        UUID playerUUID = UUID.fromString(rs.getString("player_uuid"));
        String playerName = rs.getString("player_name");
        String itemData = rs.getString("item_data");
        int amount = rs.getInt("amount");
        long receivedTimestamp = rs.getLong("received_timestamp");
        String source = rs.getString("source");
        boolean claimed = rs.getInt("claimed") == 1;
        Long claimedTimestamp = rs.getLong("claimed_timestamp");
        if (rs.wasNull()) {
            claimedTimestamp = null;
        }

        ItemStack item = ItemSerializer.deserialize(itemData);

        return new OverflowItem(id, playerUUID, playerName, item, amount,
                receivedTimestamp, source, claimed, claimedTimestamp);
    }
}
