package dev.oakheart.oakoverflow.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.oakheart.oakoverflow.config.ConfigManager;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * MySQL implementation of the DataStore.
 * Uses HikariCP for connection pooling.
 */
public class MySQLDatabase extends AbstractDatabase {

    private HikariDataSource dataSource;

    public MySQLDatabase(ConfigManager configManager, Logger logger) {
        super(logger, configManager, Executors.newFixedThreadPool(
                configManager.getAsyncThreads(),
                r -> {
                    Thread thread = new Thread(r, "OakOverflow-MySQL");
                    thread.setDaemon(true);
                    return thread;
                }
        ));
    }

    @Override
    protected void initializeConnection() throws Exception {
        HikariConfig hikariConfig = new HikariConfig();

        String host = configManager.getMysqlHost();
        int port = configManager.getMysqlPort();
        String database = configManager.getMysqlDatabase();

        StringBuilder jdbcUrl = new StringBuilder("jdbc:mysql://")
                .append(host).append(":").append(port).append("/").append(database);

        List<String> properties = new ArrayList<>();
        properties.add("characterEncoding=" + configManager.getMysqlProperty("characterEncoding", "utf8mb4"));
        properties.add("serverTimezone=" + configManager.getMysqlProperty("serverTimezone", "UTC"));
        properties.add("autoReconnect=" + configManager.getMysqlProperty("autoReconnect", "false"));
        properties.add("sslMode=" + configManager.getMysqlSslMode());

        jdbcUrl.append("?").append(String.join("&", properties));

        hikariConfig.setJdbcUrl(jdbcUrl.toString());
        hikariConfig.setUsername(configManager.getMysqlUsername());
        hikariConfig.setPassword(configManager.getMysqlPassword());

        hikariConfig.setMaximumPoolSize(configManager.getMaxConnections());
        hikariConfig.setMinimumIdle(configManager.getMinIdleConnections());
        hikariConfig.setConnectionTimeout(configManager.getConnectionTimeout());
        hikariConfig.setMaxLifetime(configManager.getMaxLifetime());
        hikariConfig.setIdleTimeout(configManager.getIdleTimeout());

        if (configManager.isTestOnBorrow()) {
            hikariConfig.setConnectionTestQuery("SELECT 1");
        }

        // Enable JDBC driver-level prepared statement caching
        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "25");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        hikariConfig.setPoolName("OakOverflow-MySQL-Pool");

        dataSource = new HikariDataSource(hikariConfig);

        logger.info("MySQL connection pool established: " + host + ":" + port + "/" + database);
    }

    @Override
    protected void createTables() throws SQLException {
        Connection conn = getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS overflow_items (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        player_uuid VARCHAR(36) NOT NULL,
                        player_name VARCHAR(255) NOT NULL,
                        item_data MEDIUMTEXT NOT NULL,
                        amount INT NOT NULL,
                        received_timestamp BIGINT NOT NULL,
                        source VARCHAR(255),
                        claimed TINYINT(1) DEFAULT 0,
                        claimed_timestamp BIGINT,
                        INDEX idx_player_unclaimed (player_uuid, claimed),
                        INDEX idx_timestamp (received_timestamp),
                        INDEX idx_claimed_timestamp (claimed, claimed_timestamp)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS schema_version (
                        version INT PRIMARY KEY,
                        applied_at BIGINT NOT NULL,
                        description VARCHAR(255)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);

            logger.info("MySQL database tables created successfully");
        } finally {
            releaseConnection(conn);
        }
    }

    @Override
    protected Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    protected void releaseConnection(Connection conn) throws SQLException {
        if (conn != null) {
            conn.close();
        }
    }

    @Override
    protected void closeResources() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("MySQL connection pool closed");
        }
    }
}
