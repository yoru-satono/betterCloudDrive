package com.betterclouddrive.dal.repository;

import com.betterclouddrive.common.dto.WarehouseEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static com.betterclouddrive.common.dto.WarehouseEvent.*;

@Testcontainers
class WarehouseEventRepositoryIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine")
            .withDatabaseName("warehouse_capture_test");
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static WarehouseEventRepository repository;

    @BeforeAll static void migrateRealSchema() throws Exception {
        var source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        repository = new WarehouseEventRepository(jdbc);
        var migrations = Path.of("../betterclouddrive-start/src/main/resources/db/migration");
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute(Files.readString(migrations.resolve("V1__schema.sql")));
            statement.execute(Files.readString(migrations.resolve("V2__warehouse_capture.sql")));
        }
    }

    @BeforeEach void clearTestFacts() { jdbc.update("DELETE FROM warehouse_events"); }

    private WarehouseEvent event(String key) {
        return new WarehouseEvent(UUID.randomUUID(), key, Type.UPLOAD_COMMITTED, Instant.parse("2026-11-19T12:00:00Z"),
                1L, 2L, 3L, null, Channel.API, Mode.INSTANT, Outcome.SUCCEEDED, 7);
    }

    @Test void repeatedBusinessIdentityDoesNotDoubleCountAndPreservesUtc() {
        transaction.executeWithoutResult(status -> repository.append(event("upload:3")));
        transaction.executeWithoutResult(status -> repository.append(event("upload:3")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM warehouse_events", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT sum(byte_count) FROM warehouse_events", Long.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT occurred_at FROM warehouse_events", java.time.OffsetDateTime.class).toInstant())
                .isEqualTo(Instant.parse("2026-11-19T12:00:00Z"));
    }

    @Test void rolledBackBusinessTransactionLeavesNoSuccessFact() {
        transaction.executeWithoutResult(status -> {
            repository.append(event("rolled-back"));
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM warehouse_events", Long.class)).isZero();
    }

    @Test void unexportedFactsIncludeTransactionsThatCommitAfterANewerFact() throws Exception {
        var inserted = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var older = executor.submit(() -> transaction.executeWithoutResult(status -> {
                repository.append(event("late-commit"));
                inserted.countDown();
                try {
                    if (!commit.await(10, TimeUnit.SECONDS)) throw new AssertionError("Commit signal missing");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
            }));
            try {
                assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();
                transaction.executeWithoutResult(status -> repository.append(event("earlier-commit")));
                assertThat(jdbc.queryForList("SELECT deduplication_key FROM warehouse_events WHERE exported_at IS NULL", String.class))
                        .containsExactly("earlier-commit");
            } finally { commit.countDown(); }
            older.get(10, TimeUnit.SECONDS);
            assertThat(jdbc.queryForList("SELECT deduplication_key FROM warehouse_events WHERE exported_at IS NULL", String.class))
                    .containsExactlyInAnyOrder("late-commit", "earlier-commit");
        }
    }

    @Test void sourceProjectionsExcludeCredentialsAndObjectPaths() {
        var columns = jdbc.queryForList("""
            SELECT column_name FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name LIKE 'warehouse_source_%'
            """, String.class);
        assertThat(columns).doesNotContain("password_hash", "webdav_password_hash", "email", "username",
                "share_code", "password_ciphertext", "storage_path", "file_name", "detail");
        assertThat(columns).contains("team_id", "file_size", "storage_quota");
    }

    @Test void retentionNeverDeletesUnexportedFacts() {
        repository.append(event("pending"));
        repository.append(event("old-export"));
        repository.append(event("recent-export"));
        jdbc.update("UPDATE warehouse_events SET exported_at = CURRENT_TIMESTAMP - INTERVAL '8 days' WHERE deduplication_key = 'old-export'");
        jdbc.update("UPDATE warehouse_events SET exported_at = CURRENT_TIMESTAMP WHERE deduplication_key = 'recent-export'");
        assertThat(repository.pruneExported(Instant.now().minus(java.time.Duration.ofDays(7)))).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT deduplication_key FROM warehouse_events", String.class))
                .containsExactlyInAnyOrder("pending", "recent-export");
        assertThat(repository.pending().count()).isEqualTo(1);
    }
}
