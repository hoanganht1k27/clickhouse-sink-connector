package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.AppInjector;
import com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication;
import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.api.DebeziumEmbeddedRestApi;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.DDLParserService;
import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.model.DBCredentials;
import com.clickhouse.jdbc.ClickHouseConnection;
import com.google.common.collect.Maps;
import com.google.inject.Guice;
import com.google.inject.Injector;
import io.debezium.storage.jdbc.offset.JdbcOffsetBackingStoreConfig;
import org.apache.log4j.BasicConfigurator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.getDebeziumProperties;
import static org.junit.Assert.assertTrue;

@Testcontainers
@DisplayName("Test that validates that the sequence number that is created in non-gtid mode is incremented correctly,"
        + "by performing a lot of updates on the primary key.")
public class MultipleUpdatesWSameTimestampIT {

    private static final Logger log = LoggerFactory.getLogger(MultipleUpdatesWSameTimestampIT.class);


    protected MySQLContainer mySqlContainer;

    @Container
    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:latest")
            .asCompatibleSubstituteFor("clickhouse"))
            .withInitScript("init_clickhouse_schema_only_column_timezone.sql")
            //   .withCopyFileToContainer(MountableFile.forClasspathResource("config.xml"), "/etc/clickhouse-server/config.d/config.xml")
            .withUsername("ch_user")
            .withPassword("password")
            .withExposedPorts(8123);
    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:latest")
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
//                .withInitScript("15k_tables_mysql.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        clickHouseContainer.start();
        Thread.sleep(35000);
    }


    @DisplayName("Test that validates that the sequence number that is created in non-gtid mode is incremented correctly,"
            + "by performing a lot of updates on the primary key.")
    @Test
    public void testIncrementingSequenceNumberWithUpdates() throws Exception {

        Injector injector = Guice.createInjector(new AppInjector());

        Properties props = getDebeziumProperties(mySqlContainer, clickHouseContainer);
        props.setProperty("snapshot.mode", "schema_only");
        props.setProperty("schema.history.internal.store.only.captured.tables.ddl", "true");
        props.setProperty("schema.history.internal.store.only.captured.databases.ddl", "true");

        // Override clickhouse server timezone.
        ClickHouseDebeziumEmbeddedApplication clickHouseDebeziumEmbeddedApplication = new ClickHouseDebeziumEmbeddedApplication();


        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                clickHouseDebeziumEmbeddedApplication.start(injector.getInstance(DebeziumRecordParserService.class),
                        injector.getInstance(DDLParserService.class), props, false);
                DebeziumEmbeddedRestApi.startRestApi(props, injector, clickHouseDebeziumEmbeddedApplication.getDebeziumEventCapture()
                        , new Properties());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

        });

        Thread.sleep(25000);

        Connection conn = ITCommon.connectToMySQL(mySqlContainer);
        conn.prepareStatement("create table `newtable`(col1 varchar(255) not null, col2 int, col3 int, primary key(col1))").execute();

        // Insert a new row in the table
        conn.prepareStatement("insert into newtable values('a', 1, 1)").execute();

        // Generate and execute the update workload
        String updateStatement = "UPDATE newtable SET col2 = ? WHERE col1 = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(updateStatement)) {
            conn.setAutoCommit(false);
            for (int i = 0; i < 20000; i++) {
                // Set parameters for the update statement
                pstmt.setInt(1, 10000 + i);
                pstmt.setString(2, "a");

                // Execute the update statement
                pstmt.executeUpdate();
            }
            conn.commit();
        }


        Thread.sleep(10000);

        // Validate in Clickhouse the last record written is 29999
        String jdbcUrl = BaseDbWriter.getConnectionString(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "employees");
        ClickHouseConnection chConn = BaseDbWriter.createConnection(jdbcUrl, "Client_1",
                clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), new ClickHouseSinkConnectorConfig(new HashMap<>()));
        BaseDbWriter writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "employees", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null, chConn);

        long col2 = 1L;
        ResultSet version1Result = writer.executeQueryWithResultSet("select col2 from newtable final where col1 = 'a'");
        while(version1Result.next()) {
            col2 = version1Result.getLong("col2");
        }
        Thread.sleep(10000);


        assertTrue(col2 == 29999);
        clickHouseDebeziumEmbeddedApplication.getDebeziumEventCapture().engine.close();

        conn.close();
        // Files.deleteIfExists(tmpFilePath);
        executorService.shutdown();
    }
}
