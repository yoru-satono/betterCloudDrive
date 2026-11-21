package com.betterclouddrive.service.quota;

import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.repository.QuotaAccountingRepository;
import com.betterclouddrive.dal.repository.QuotaAccountingRepository.Scope;
import com.betterclouddrive.dal.repository.QuotaLegacyRepository;
import com.betterclouddrive.dal.repository.StorageInspectionRepository;
import com.betterclouddrive.dal.repository.InspectionFindingRepository;
import com.betterclouddrive.dal.repository.InspectionRecords.*;
import com.betterclouddrive.service.inspection.*;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.wait.strategy.Wait;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@Testcontainers
class QuotaAccountingIT {
    private static final String PASSWORD = UUID.randomUUID().toString();
    @Container static final GenericContainer<?> POSTGRES = new GenericContainer<>("postgres:18.4-alpine")
            .withEnv("POSTGRES_PASSWORD", PASSWORD).withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.8.1-alpine").withExposedPorts(6379);
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static QuotaAccountingRepository repository;
    private static QuotaLegacyRepository legacy;
    private static QuotaAccountingService service;
    private static StringRedisTemplate redis;
    private static LettuceConnectionFactory redisConnections;

    @BeforeAll static void migrate() throws Exception {
        var source = new PGSimpleDataSource();
        source.setURL("jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/postgres");
        source.setUser("postgres"); source.setPassword(PASSWORD);
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        repository = new QuotaAccountingRepository(jdbc);
        legacy = new QuotaLegacyRepository(jdbc);
        service = new QuotaAccountingService(repository);
        var migrations = Path.of("../betterclouddrive-start/src/main/resources/db/migration");
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            for (String name : new String[]{"V1__schema.sql", "V2__warehouse_capture.sql", "V3__transactional_quota.sql", "V4__storage_inspection.sql"}) {
                statement.execute(Files.readString(migrations.resolve(name)));
            }
        }
        redisConnections = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        redisConnections.afterPropertiesSet(); redisConnections.start();
        redis = new StringRedisTemplate(redisConnections);
    }

    @AfterAll static void closeRedis() { if (redisConnections != null) redisConnections.destroy(); }

    @BeforeEach void fixtures() {
        // Exact tables in this test's isolated database; no development source is connected.
        jdbc.execute("TRUNCATE storage_inspection_runs, storage_inspection_findings CASCADE");
        jdbc.update("DELETE FROM quota_legacy_transfers");
        jdbc.update("DELETE FROM files");
        jdbc.update("DELETE FROM teams");
        jdbc.update("DELETE FROM users");
        jdbc.update("UPDATE quota_accounting_state SET ready=true WHERE id=1");
        jdbc.update("INSERT INTO users (id, username, password_hash, storage_quota, storage_used) VALUES (1, 'quota_fixture', 'unused', 100, 0)");
        try (var connection = redisConnections.getConnection()) { connection.serverCommands().flushDb(); }
    }

    @Test void metadataAndQuotaBothRollbackWithBusinessFailure() {
        tx.executeWithoutResult(status -> {
            service.lock(Scope.PERSONAL, 1);
            jdbc.update("INSERT INTO files (user_id, file_name, file_type, file_size, storage_path) VALUES (1, 'fixture', 'file', 40, 'users/1/fixture')");
            service.change(Scope.PERSONAL, 1, 40);
            status.setRollbackOnly();
        });
        assertThat(repository.find(Scope.PERSONAL, 1, false).orElseThrow().used()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM files", Long.class)).isZero();
    }

    @Test void concurrentFinalCommitsCannotSpendTheSameRemainingQuota() throws Exception {
        var locked = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> tx.executeWithoutResult(status -> {
                service.change(Scope.PERSONAL, 1, 70);
                locked.countDown();
                try { if (!commit.await(5, TimeUnit.SECONDS)) throw new AssertionError("missing commit signal"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var second = workers.submit(() -> tx.executeWithoutResult(status -> service.change(Scope.PERSONAL, 1, 70)));
                commit.countDown();
                first.get(5, TimeUnit.SECONDS);
                assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(BusinessException.class);
            } finally { commit.countDown(); }
        }
        assertThat(repository.find(Scope.PERSONAL, 1, false).orElseThrow().used()).isEqualTo(70);
        assertThat(repository.find(Scope.PERSONAL, 1, false).orElseThrow().revision()).isEqualTo(1);
    }

    @Test void legacyCutoverAppliesBothScopesOnceAndIsRepeatable() {
        jdbc.update("INSERT INTO teams (id, name, owner_id, storage_used, storage_quota) VALUES (10, 'fixture', 1, 7, 100)");
        redis.opsForValue().set("storage:incr:1", "11");
        redis.opsForValue().set("team:storage:incr:10", "-2");
        var cutover = new QuotaCutoverService(repository, legacy, redis, tx);
        cutover.run(true); cutover.run(true);
        assertThat(repository.find(Scope.PERSONAL, 1, false).orElseThrow().used()).isEqualTo(11);
        assertThat(repository.find(Scope.TEAM, 10, false).orElseThrow().used()).isEqualTo(5);
        assertThat(legacy.pending()).isEmpty();
        assertThat(repository.ready()).isTrue();
        assertThat(redis.hasKey("storage:incr:1")).isFalse();
    }

    @Test void lostRedisAcknowledgementKeepsGateClosedAndResumeDoesNotDoubleApply() {
        redis.opsForValue().set("storage:incr:1", "11");
        StringRedisTemplate interrupted = spy(redis);
        doThrow(new RedisConnectionFailureException("test transport unavailable"))
                .when(interrupted).execute(any(RedisScript.class), anyList(), anyString());
        var broken = new QuotaCutoverService(repository, legacy, interrupted, tx);
        assertThatThrownBy(() -> broken.run(true)).isInstanceOf(RedisConnectionFailureException.class);
        assertThat(repository.ready()).isFalse();
        assertThat(repository.find(Scope.PERSONAL, 1, false).orElseThrow().used()).isEqualTo(11);
        new QuotaCutoverService(repository, legacy, redis, tx).run(true);
        assertThat(repository.find(Scope.PERSONAL, 1, false).orElseThrow().used()).isEqualTo(11);
        assertThat(repository.ready()).isTrue();
    }

    @Test void changedLegacyValueIsNeverSilentlyDeletedOrAppliedTwice() {
        redis.opsForValue().set("storage:incr:1", "11");
        StringRedisTemplate racing = spy(redis);
        var once = new AtomicBoolean();
        doAnswer(invocation -> {
            if (once.compareAndSet(false, true)) redis.opsForValue().set("storage:incr:1", "15");
            return invocation.callRealMethod();
        }).when(racing).execute(any(RedisScript.class), anyList(), anyString());
        assertThatThrownBy(() -> new QuotaCutoverService(repository, legacy, racing, tx).run(true))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.ready()).isFalse();
        assertThat(redis.opsForValue().get("storage:incr:1")).isEqualTo("15");
        assertThat(repository.find(Scope.PERSONAL, 1, false).orElseThrow().used()).isEqualTo(11);
    }

    @Test void canonicalQuotaCountsLogicalCopiesAndVersionsButNotHiddenDescendants() {
        jdbc.update("INSERT INTO users (id, username, password_hash) VALUES (2, 'editor_fixture', 'unused')");
        jdbc.update("""
                INSERT INTO files (id, user_id, file_name, file_type, file_size, storage_path, is_deleted, parent_id) VALUES
                (10, 1, 'active', 'file', 10, 'users/1/shared', false, NULL),
                (11, 1, 'copy', 'file', 10, 'users/1/shared', false, NULL),
                (12, 1, 'trash', 'folder', 0, NULL, true, NULL),
                (13, 1, 'hidden', 'file', 77, 'users/1/hidden', false, 12)
                """);
        jdbc.update("""
                INSERT INTO file_versions (file_id, user_id, version_number, file_size, storage_path, md5_hash) VALUES
                (10, 2, 1, 3, 'users/1/version', 'fixture'), (13, 2, 1, 9, 'users/1/hidden-version', 'fixture')
                """);
        var inspection = new com.betterclouddrive.dal.repository.StorageInspectionRepository(jdbc);
        var fact = inspection.quotaFact("PERSONAL", 1);
        assertThat(fact.valid()).isTrue();
        assertThat(fact.expectedBytes()).isEqualTo(23);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inspection_live_references", Long.class)).isEqualTo(5);
        assertThat(inspection.quotaFact("PERSONAL", 2).expectedBytes()).isZero();
    }

    @Test void unreachableCyclesBlockRepairInsteadOfProducingAPartialTotal() {
        jdbc.update("""
                INSERT INTO files (id, user_id, file_name, file_type, file_size, parent_id) VALUES
                (10, 1, 'cycle-a', 'folder', 0, NULL), (11, 1, 'cycle-b', 'folder', 0, 10)
                """);
        jdbc.update("UPDATE files SET parent_id=11 WHERE id=10");
        var fact = new com.betterclouddrive.dal.repository.StorageInspectionRepository(jdbc).quotaFact("PERSONAL", 1);
        assertThat(fact.valid()).isFalse();
        assertThat(fact.expectedBytes()).isNull();
        assertThat(fact.invalidCount()).isEqualTo(2);
    }

    private Claim claim(StorageInspectionRepository inspection) {
        return tx.execute(status -> {
            inspection.enqueue(UUID.randomUUID(), "FULL", null, 1L);
            return inspection.claim(UUID.randomUUID(), 180).orElseThrow();
        });
    }

    @Test void leaseFencingRejectsAWorkerReplacedAfterExpiry() {
        var inspection = new StorageInspectionRepository(jdbc);
        Claim old = claim(inspection);
        jdbc.update("UPDATE storage_inspection_runs SET lease_until=now()-INTERVAL '1 second' WHERE id=?", old.run().id());
        Claim replacement = tx.execute(status -> inspection.claim(UUID.randomUUID(), 180).orElseThrow());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> inspection.guard(old))).isInstanceOf(IllegalStateException.class);
        tx.executeWithoutResult(status -> inspection.finish(replacement, "SUCCEEDED", null));
        assertThat(inspection.run(old.run().id()).orElseThrow().status()).isEqualTo("SUCCEEDED");
    }

    @Test void snapshotCapturesReferencesQuotaAndTasksUnderOneRun() {
        var inspection = new StorageInspectionRepository(jdbc);
        Claim run = claim(inspection);
        jdbc.update("INSERT INTO files (id, user_id, file_name, file_type, file_size, storage_path) VALUES (10,1,'fixture','file',4,'users/1/fixture')");
        tx.executeWithoutResult(status -> inspection.snapshot(run.run().id(), "UTC"));
        jdbc.update("UPDATE files SET file_size=9 WHERE id=10");
        assertThat(inspection.references(run.run().id(), 0, 10).getFirst().expectedBytes()).isEqualTo(4);
        assertThat(inspection.scopes(run.run().id(), 0, 10).getFirst().expectedBytes()).isEqualTo(4);
        assertThat(inspection.quotaFact("PERSONAL", 1).expectedBytes()).isEqualTo(9);
    }

    private InspectionMetrics metrics(StorageInspectionRepository inspection, InspectionFindingRepository findings) {
        return new InspectionMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), inspection, findings, repository, new InspectionProperties());
    }

    private UUID confirmedDrift(StorageInspectionRepository inspection, InspectionFindingRepository findings) {
        jdbc.update("UPDATE users SET storage_used=11 WHERE id=1");
        Claim run = claim(inspection);
        var now = java.time.Instant.now();
        var clock = new InspectionClock(java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
        var observations = new InspectionObservationService(inspection, findings, new InspectionProperties(), clock);
        var evidence = new Observation("QUOTA_DRIFT", "QUOTA", "1", null, "PERSONAL", 1L, "WARNING", "fixture-drift", 0L, 11L, null);
        UUID finding = tx.execute(status -> observations.observe(run, evidence));
        var advanced = new InspectionObservationService(inspection, findings, new InspectionProperties(),
                new InspectionClock(java.time.Clock.fixed(now.plusSeconds(601), java.time.ZoneOffset.UTC)));
        tx.executeWithoutResult(status -> advanced.observe(run, evidence));
        assertThat(inspection.finding(finding).orElseThrow().finding().status()).isEqualTo("CONFIRMED");
        return finding;
    }

    @Test void repairIsAtomicAuditedAndIdempotentEvenAfterPreviewExpires() {
        var inspection = new StorageInspectionRepository(jdbc);
        var findings = new InspectionFindingRepository(jdbc);
        UUID finding = confirmedDrift(inspection, findings);
        var repairs = new QuotaRepairService(inspection, findings, repository, service, metrics(inspection, findings));
        Preview preview = tx.execute(status -> repairs.preview(finding, 1));
        UUID key = UUID.randomUUID();
        Repair result = tx.execute(status -> repairs.apply(preview.id(), key, 1, "controlled fixture adjustment"));
        jdbc.update("UPDATE storage_quota_repair_previews SET expires_at=now()-INTERVAL '1 hour' WHERE id=?", preview.id());
        Repair repeated = tx.execute(status -> repairs.apply(preview.id(), key, 1, "controlled fixture adjustment"));
        assertThat(repeated.id()).isEqualTo(result.id());
        assertThat(repository.find(Scope.PERSONAL, 1, false).orElseThrow().used()).isZero();
        assertThat(findings.repairs(finding)).hasSize(1);
        assertThat(inspection.finding(finding).orElseThrow().finding().status()).isEqualTo("RESOLVED");
    }

    @Test void interveningCapacityChangeInvalidatesRepairPreview() {
        var inspection = new StorageInspectionRepository(jdbc);
        var findings = new InspectionFindingRepository(jdbc);
        UUID finding = confirmedDrift(inspection, findings);
        var repairs = new QuotaRepairService(inspection, findings, repository, service, metrics(inspection, findings));
        Preview preview = tx.execute(status -> repairs.preview(finding, 1));
        tx.executeWithoutResult(status -> {
            service.change(Scope.PERSONAL, 1, 4);
            jdbc.update("INSERT INTO files (user_id,file_name,file_type,file_size,storage_path) VALUES (1,'fixture','file',4,'users/1/new')");
        });
        assertThatThrownBy(() -> tx.execute(status -> repairs.apply(preview.id(), UUID.randomUUID(), 1, "fixture")))
                .isInstanceOf(com.betterclouddrive.common.exception.InspectionException.class);
        assertThat(repository.find(Scope.PERSONAL, 1, false).orElseThrow().used()).isEqualTo(15);
        assertThat(findings.repairs(finding)).isEmpty();
    }

    @Test void teamRepairUsesTheFileSpaceRatherThanTheVersionEditorAndKeepsTheLimit() {
        jdbc.update("INSERT INTO teams (id,name,owner_id,storage_quota,storage_used) VALUES (5,'repair fixture',1,100,16)");
        jdbc.update("INSERT INTO files (id,user_id,team_id,file_name,file_type,file_size,storage_path) VALUES (10,1,5,'fixture','file',4,'teams/5/fixture')");
        jdbc.update("INSERT INTO file_versions (id,file_id,user_id,version_number,file_size,md5_hash,storage_path) VALUES (10,10,1,1,3,'fixture','teams/5/history')");
        var inspection = new StorageInspectionRepository(jdbc);
        var findings = new InspectionFindingRepository(jdbc);
        Claim run = claim(inspection);
        var now = java.time.Instant.now();
        var evidence = new Observation("QUOTA_DRIFT", "QUOTA", "5", null, "TEAM", 5L, "WARNING", "fixture-team", 7L, 16L, null);
        var first = new InspectionObservationService(inspection, findings, new InspectionProperties(),
                new InspectionClock(java.time.Clock.fixed(now, java.time.ZoneOffset.UTC)));
        UUID finding = tx.execute(status -> first.observe(run, evidence));
        var second = new InspectionObservationService(inspection, findings, new InspectionProperties(),
                new InspectionClock(java.time.Clock.fixed(now.plusSeconds(601), java.time.ZoneOffset.UTC)));
        tx.executeWithoutResult(status -> second.observe(run, evidence));
        var repairs = new QuotaRepairService(inspection, findings, repository, service, metrics(inspection, findings));
        Preview preview = tx.execute(status -> repairs.preview(finding, 1));
        assertThat(preview.expectedBytes()).isEqualTo(7);
        assertThatThrownBy(() -> tx.execute(status -> repairs.apply(preview.id(), UUID.randomUUID(), 2, "fixture")))
                .isInstanceOf(com.betterclouddrive.common.exception.InspectionException.class);
        tx.executeWithoutResult(status -> repairs.apply(preview.id(), UUID.randomUUID(), 1, "team fixture repair"));
        var balance = repository.find(Scope.TEAM, 5, false).orElseThrow();
        assertThat(balance.used()).isEqualTo(7);
        assertThat(balance.quota()).isEqualTo(100);
        assertThat(repository.find(Scope.PERSONAL, 1, false).orElseThrow().used()).isZero();
        assertThat(findings.repairs(finding)).singleElement().satisfies(repair -> assertThat(repair.scope()).isEqualTo("TEAM"));
    }

    @Test void auditWriteFailureRollsBackTheCounterRepair() {
        var inspection = new StorageInspectionRepository(jdbc);
        var findings = new InspectionFindingRepository(jdbc);
        UUID finding = confirmedDrift(inspection, findings);
        var failing = spy(findings);
        var repairs = new QuotaRepairService(inspection, failing, repository, service, metrics(inspection, findings));
        Preview preview = tx.execute(status -> repairs.preview(finding, 1));
        doThrow(new IllegalStateException("fixture audit storage failure")).when(failing).repair(any(), any(), any(), anyString());
        assertThatThrownBy(() -> tx.execute(status -> repairs.apply(preview.id(), UUID.randomUUID(), 1, "fixture")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.find(Scope.PERSONAL, 1, false).orElseThrow().used()).isEqualTo(11);
        assertThat(findings.repairs(finding)).isEmpty();
    }
}
