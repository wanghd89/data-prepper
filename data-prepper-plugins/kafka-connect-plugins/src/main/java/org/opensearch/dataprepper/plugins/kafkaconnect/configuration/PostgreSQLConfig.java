package org.opensearch.dataprepper.plugins.kafkaconnect.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import org.opensearch.dataprepper.plugins.kafkaconnect.util.Connector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class PostgreSQLConfig extends ConnectorConfig {
    public static final String CONNECTOR_CLASS = "io.debezium.connector.postgresql.PostgresConnector";
    private static class TableConfig {
        @JsonProperty("database_name")
        @NotNull
        private String databaseName;

        @JsonProperty("topic_prefix")
        @NotNull
        private String topicPrefix;

        @JsonProperty("table_name")
        @NotNull
        private String tableName;

        public String getDatabaseName() {
            return databaseName;
        }

        public String getTableName() {
            return tableName;
        }

        public String getTopicPrefix() {
            return topicPrefix;
        }
    }
    private static final String TOPIC_DEFAULT_PARTITIONS = "10";
    private static final String TOPIC_DEFAULT_REPLICATION_FACTOR = "-1";
    private static final String DEFAULT_PORT = "5432";
    private static final String DEFAULT_SNAPSHOT_MODE = "initial";
    private static final String DEFAULT_DECODING_PLUGIN = "pgoutput"; // default plugin for Aurora PostgreSQL

    @JsonProperty("hostname")
    @NotNull
    private String hostname;

    @JsonProperty("port")
    private String port = DEFAULT_PORT;

    /**
     * The name of the PostgreSQL logical decoding plug-in installed on the PostgreSQL server.
     * Supported values are decoderbufs, and pgoutput.
     */
    @JsonProperty("plugin_name")
    private String pluginName = DEFAULT_DECODING_PLUGIN;

    @JsonProperty("credentials")
    private CredentialsConfig credentialsConfig;

    @JsonProperty("snapshot_mode")
    private String snapshotMode = DEFAULT_SNAPSHOT_MODE;

    @JsonProperty("tables")
    private List<TableConfig> tables = new ArrayList<>();

    @Override
    public List<Connector> buildConnectors() {
        return tables.stream().map(table -> {
            final String connectorName = table.getTopicPrefix() + "." + table.getTableName();
            final Map<String, String> config = buildConfig(table);
            return new Connector(connectorName, config, this.forceUpdate);
        }).collect(Collectors.toList());
    }

    private Map<String, String> buildConfig(final TableConfig tableName) {
        Map<String, String> config = new HashMap<>();
        config.put("topic.creation.default.partitions", TOPIC_DEFAULT_PARTITIONS);
        config.put("topic.creation.default.replication.factor", TOPIC_DEFAULT_REPLICATION_FACTOR);
        config.put("connector.class", CONNECTOR_CLASS);
        config.put("plugin.name", pluginName);
        config.put("database.hostname", hostname);
        config.put("database.port", port);
        config.put("database.user", credentialsConfig.getUsername());
        config.put("database.password", credentialsConfig.getPassword());
        config.put("snapshot.mode", snapshotMode);
        config.put("topic.prefix", tableName.getTopicPrefix());
        config.put("database.dbname", tableName.getDatabaseName());
        config.put("table.include.list", tableName.getTableName());
        return config;
    }
}
