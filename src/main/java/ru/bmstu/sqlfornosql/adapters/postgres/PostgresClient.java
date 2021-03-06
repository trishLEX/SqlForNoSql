package ru.bmstu.sqlfornosql.adapters.postgres;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ru.bmstu.sqlfornosql.adapters.AbstractClient;
import ru.bmstu.sqlfornosql.adapters.sql.SqlHolder;
import ru.bmstu.sqlfornosql.model.Table;
import ru.bmstu.sqlfornosql.model.TableIterator;

import javax.annotation.Nonnull;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Iterator;
import java.util.NoSuchElementException;

public class PostgresClient extends AbstractClient {
    private static final Logger logger = LogManager.getLogger(PostgresClient.class);
    private static final PostgresMapper MAPPER = new PostgresMapper();
    static {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Can't load driver", e);
        }
    }

    private HikariDataSource connectionPool;

    public PostgresClient(String host, int port, String user, String password, String database) {
            String connectionString = String.format("jdbc:postgresql://%s:%d/%s", host, port, database);

            connectionPool = new HikariDataSource();
            connectionPool.setUsername(user);
            connectionPool.setPassword(password);
            connectionPool.setDriverClassName("org.postgresql.Driver");
            connectionPool.setJdbcUrl(connectionString);
            connectionPool.setAutoCommit(false);
            connectionPool.setMaximumPoolSize(Runtime.getRuntime().availableProcessors());
    }

    @Override
    public TableIterator executeQuery(SqlHolder query) {
        query.updateSelectItems();
        logger.info("Executing postgres query: " + query.getSqlQuery());
        try {
            Connection connection = connectionPool.getConnection();
            connection.setAutoCommit(false);
            Statement statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.FETCH_FORWARD);
            statement.setFetchSize((int) TableIterator.BATCH_SIZE);
            ResultSet resultSet = statement.executeQuery(query.getSqlQuery());
            TableIterator tableIterator = new TableIterator() {
                @Nonnull
                @Override
                public Iterator<Table> iterator() {
                    return executeQuery(query);
                }

                @Override
                public Table next() {
                    if (hasNext()) {
                        Table table = MAPPER.mapResultSet(resultSet, query);
                        lastBatchSize = table.size();

                        if (!hasNext()) {
                            afterAll.forEach(Runnable::run);
                        }

                        return table;
                    }

                    throw new NoSuchElementException("There are no more elements");
                }
            };

            tableIterator.addAfterAll(() -> {
                try {
                    connection.close();
                    statement.close();
                    resultSet.close();
                } catch (SQLException e) {
                    throw new IllegalStateException("Can't close connection", e);
                }
            });
            return tableIterator;
        } catch (SQLException e) {
            throw new IllegalStateException("Can't execute query: " + query.getSqlQuery(), e);
        }
    }

    @Override
    public void close() {
        connectionPool.close();
    }
}
