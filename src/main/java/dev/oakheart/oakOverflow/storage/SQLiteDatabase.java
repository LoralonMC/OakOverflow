package dev.oakheart.oakoverflow.storage;

import dev.oakheart.oakoverflow.config.ConfigManager;

import java.io.File;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * SQLite implementation of the DataStore.
 * Uses WAL mode for better concurrency and crash safety.
 * Caches PreparedStatements for the persistent single connection.
 */
public class SQLiteDatabase extends AbstractDatabase {

    private final File dataFolder;
    private Connection connection;
    private String dbUrl;

    // Statement cache — reuses PreparedStatements across calls on the single connection
    private final Map<String, PreparedStatement> statementCache = new HashMap<>();

    public SQLiteDatabase(File dataFolder, Logger logger, ConfigManager configManager) {
        super(logger, configManager, Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "OakOverflow-Database");
            thread.setDaemon(true);
            return thread;
        }));
        this.dataFolder = dataFolder;
    }

    @Override
    protected void initializeConnection() throws Exception {
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        File dbFile = new File(dataFolder, configManager.getSqliteFilename());
        this.dbUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();

        connection = DriverManager.getConnection(dbUrl);
        configurePragmas(connection);

        logger.info("Database connection established: " + dbFile.getAbsolutePath());
    }

    @Override
    protected void createTables() throws SQLException {
        Connection conn = getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS overflow_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_uuid TEXT NOT NULL,
                        player_name TEXT NOT NULL,
                        item_data TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        received_timestamp INTEGER NOT NULL,
                        source TEXT,
                        claimed INTEGER DEFAULT 0,
                        claimed_timestamp INTEGER
                    )
                    """);

            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_player_unclaimed
                    ON overflow_items(player_uuid, claimed)
                    """);

            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_timestamp
                    ON overflow_items(received_timestamp)
                    """);

            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_claimed_timestamp
                    ON overflow_items(claimed, claimed_timestamp)
                    """);

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS schema_version (
                        version INTEGER PRIMARY KEY,
                        applied_at INTEGER NOT NULL,
                        description TEXT
                    )
                    """);

            logger.info("Database tables created successfully");
        } finally {
            releaseConnection(conn);
        }
    }

    @Override
    protected Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            logger.warning("SQLite connection lost, attempting to reconnect...");
            clearStatementCache();

            connection = DriverManager.getConnection(dbUrl);
            configurePragmas(connection);
            logger.info("SQLite connection re-established");
        }
        return connection;
    }

    @Override
    protected void releaseConnection(Connection conn) {
        // No-op for SQLite — persistent single connection
    }

    /**
     * Returns a cached PreparedStatement, creating it on first use.
     * Clears parameters from previous invocations before returning.
     */
    @Override
    protected PreparedStatement prepareStatement(Connection conn, String sql) throws SQLException {
        PreparedStatement cached = statementCache.get(sql);
        if (cached != null && !cached.isClosed()) {
            cached.clearParameters();
            return cached;
        }
        PreparedStatement stmt = conn.prepareStatement(sql);
        statementCache.put(sql, stmt);
        return stmt;
    }

    /**
     * Returns a cached PreparedStatement with flags (e.g. RETURN_GENERATED_KEYS).
     * Uses a cache key that includes the flags to distinguish from flagless variants.
     */
    @Override
    protected PreparedStatement prepareStatement(Connection conn, String sql, int flags) throws SQLException {
        String cacheKey = sql + "::flags=" + flags;
        PreparedStatement cached = statementCache.get(cacheKey);
        if (cached != null && !cached.isClosed()) {
            cached.clearParameters();
            return cached;
        }
        PreparedStatement stmt = conn.prepareStatement(sql, flags);
        statementCache.put(cacheKey, stmt);
        return stmt;
    }

    /**
     * No-op for SQLite — cached statements are kept open for reuse.
     */
    @Override
    protected void returnStatement(PreparedStatement stmt) {
        // Don't close — statement is cached for reuse
    }

    @Override
    protected void closeResources() {
        clearStatementCache();
        if (connection != null) {
            try {
                connection.close();
                logger.info("Database connection closed");
            } catch (SQLException e) {
                logger.warning("Error closing database connection: " + e.getMessage());
            }
        }
    }

    private void clearStatementCache() {
        for (PreparedStatement stmt : statementCache.values()) {
            try {
                stmt.close();
            } catch (SQLException ignored) {}
        }
        statementCache.clear();
    }

    private void configurePragmas(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            if (configManager.isSqliteWalMode()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA synchronous=NORMAL");
            }
            stmt.execute("PRAGMA foreign_keys=ON");
        }
    }
}
