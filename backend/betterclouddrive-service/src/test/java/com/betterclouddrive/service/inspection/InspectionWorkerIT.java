package com.betterclouddrive.service.inspection;

import com.betterclouddrive.common.dto.StorageInventory.*;
import com.betterclouddrive.dal.repository.*;
import com.betterclouddrive.dal.repository.InspectionRecords.*;
import com.betterclouddrive.service.quota.QuotaAccountingService;
import com.betterclouddrive.storage.StorageInventoryException;
import com.betterclouddrive.storage.StorageInventoryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/** Real SQL, transactions, proxies and background executor; storage responses are controlled separately. */
@Testcontainers
class InspectionWorkerIT {
    private static final String PASSWORD = UUID.randomUUID().toString();
    @Container static final GenericContainer<?> POSTGRES = new GenericContainer<>("postgres:18.4-alpine")
            .withEnv("POSTGRES_PASSWORD", PASSWORD).withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private AnnotationConfigApplicationContext context;
    private StorageInspectionRepository repository;
    private InspectionFindingRepository findings;
    private StorageInspectionService inspection;
    private InspectionWorker worker;
    private FakeStorage storage;
    private MutableClock clock;
    private TransactionTemplate tx;

    @Configuration(proxyBeanMethods=false)
    @EnableTransactionManagement(proxyTargetClass=true)
    static class Transactions {}

    @BeforeAll static void migrate() throws Exception {
        var source = new PGSimpleDataSource();
        source.setURL("jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/postgres");
        source.setUser("postgres"); source.setPassword(PASSWORD);
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        var migrations = Path.of("../betterclouddrive-start/src/main/resources/db/migration");
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            for (String name : new String[]{"V1__schema.sql", "V2__warehouse_capture.sql", "V3__transactional_quota.sql", "V4__storage_inspection.sql"}) {
                statement.execute(Files.readString(migrations.resolve(name)));
            }
        }
    }

    @BeforeEach void fixtures() {
        jdbc.execute("TRUNCATE storage_inspection_runs, storage_inspection_findings, users CASCADE");
        jdbc.update("INSERT INTO users (id,username,password_hash,storage_used,storage_quota) VALUES (1,'inspection_fixture','unused',11,1000)");
        jdbc.update("UPDATE quota_accounting_state SET ready=true WHERE id=1");
        jdbc.update("""
                INSERT INTO files (id,user_id,file_name,file_type,file_size,storage_path) VALUES
                (10,1,'fixture','file',4,'users/1/current'), (11,1,'copy','file',4,'users/1/current')
                """);
        jdbc.update("INSERT INTO file_versions (id,file_id,user_id,version_number,file_size,md5_hash,storage_path) VALUES (10,10,1,1,3,'fixture','users/1/history')");
        String upload = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO upload_sessions (id,user_id,file_name,file_size,md5_hash,total_chunks,status)
                VALUES (?,1,'fixture',3,'fixture',1,1)
                """, upload);
        clock = new MutableClock(Instant.now());
        storage = new FakeStorage();
        storage.put("users/1/current", 4, clock.instant().minusSeconds(3600));
        storage.put("users/1/history", 3, clock.instant().minusSeconds(3600));
        storage.put("users/1/orphan", 5, clock.instant().minusSeconds(90000));
        storage.put("uploads/1/" + upload + "/chunks.part.1", 3, clock.instant());
        var properties = new InspectionProperties(); properties.setPageSize(2);
        tx = new TransactionTemplate(manager);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean("transactionManager", DataSourceTransactionManager.class, () -> manager);
        context.registerBean(InspectionProperties.class, () -> properties);
        context.registerBean(InspectionClock.class, () -> new InspectionClock(clock));
        context.registerBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new);
        context.registerBean(StorageInventoryService.class, () -> storage);
        context.registerBean(ActiveObjectPrefixService.class, () -> org.mockito.Mockito.mock(ActiveObjectPrefixService.class));
        context.registerBean(QuotaAccountingRepository.class);
        context.registerBean(StorageInspectionRepository.class);
        context.registerBean(InspectionFindingRepository.class);
        context.registerBean(InspectionRetentionRepository.class);
        context.registerBean(QuotaAccountingService.class);
        context.registerBean(InspectionObservationService.class);
        context.registerBean(InspectionRules.class);
        context.registerBean(InspectionMetrics.class);
        context.registerBean(StorageInspectionService.class);
        context.registerBean(QuotaRepairService.class);
        context.registerBean(InspectionWorker.class);
        context.refresh();
        repository = context.getBean(StorageInspectionRepository.class);
        findings = context.getBean(InspectionFindingRepository.class);
        inspection = context.getBean(StorageInspectionService.class);
        worker = context.getBean(InspectionWorker.class);
    }

    @AfterEach void close() { if (context != null) context.close(); }

    private Run complete(Run run) throws Exception {
        worker.dispatch();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            Run current = repository.run(run.id()).orElseThrow();
            if (!Set.of("QUEUED", "RUNNING").contains(current.status())) return current;
            if ("QUEUED".equals(current.status())) worker.dispatch();
            Thread.sleep(50);
        }
        throw new AssertionError("Inspection did not reach a terminal state; inspect the isolated run metadata");
    }

    private Run full() throws Exception { return complete(inspection.start(1L)); }
    private List<Finding> ofType(String type) { return findings.list(new FindingFilter(type, null, null, null, null), 0, 100); }

    @Test void sharedObjectsAndActivePartsAreProtectedAndStableOrphansRequireTwoObservations() throws Exception {
        Run first = full();
        assertThat(first.status()).isEqualTo("SUCCEEDED");
        assertThat(first.objectsScanned()).isEqualTo(4);
        assertThat(first.objectBytes()).isEqualByComparingTo("15");
        assertThat(ofType("MISSING_OBJECT")).isEmpty();
        assertThat(ofType("QUOTA_DRIFT")).isEmpty();
        assertThat(ofType("ORPHAN_OBJECT")).singleElement().satisfies(finding -> {
            assertThat(finding.status()).isEqualTo("SUSPECTED");
            assertThat(finding.observations()).isEqualTo(1);
        });
        clock.advance(601);
        assertThat(full().status()).isEqualTo("SUCCEEDED");
        assertThat(ofType("ORPHAN_OBJECT")).singleElement().satisfies(finding -> {
            assertThat(finding.status()).isEqualTo("CONFIRMED");
            assertThat(finding.observations()).isEqualTo(2);
        });
    }

    @Test void detectsMissingVersionAndWrongSizeThenResolvesThemAfterRecovery() throws Exception {
        storage.objects.remove("users/1/history");
        storage.put("users/1/current", 9, clock.instant().minusSeconds(3600));
        assertThat(full().status()).isEqualTo("SUCCEEDED");
        assertThat(ofType("MISSING_OBJECT")).singleElement().satisfies(finding -> assertThat(finding.referenceKind()).isEqualTo("VERSION"));
        assertThat(ofType("OBJECT_SIZE_MISMATCH")).hasSize(2);
        clock.advance(601);
        full();
        assertThat(ofType("MISSING_OBJECT").getFirst().status()).isEqualTo("CONFIRMED");
        storage.put("users/1/current", 4, clock.instant());
        storage.put("users/1/history", 3, clock.instant());
        full();
        assertThat(ofType("MISSING_OBJECT").getFirst().status()).isEqualTo("RESOLVED");
        assertThat(ofType("OBJECT_SIZE_MISMATCH")).allSatisfy(finding -> assertThat(finding.status()).isEqualTo("RESOLVED"));
    }

    @Test void inventoryGapProducesPartialCoverageAndPreservesThePreviousCompleteReport() throws Exception {
        Run previous = full();
        storage.omitted.add("users/1/current");
        storage.put("users/1/current/", 4, clock.instant().minusSeconds(3600));
        Run partial = full();
        assertThat(partial.status()).isEqualTo("PARTIAL");
        assertThat(partial.inventoryComplete()).isFalse();
        assertThat(partial.errorKey()).isEqualTo("inspection_inventory_gap");
        assertThat(repository.latestComplete().orElseThrow().id()).isEqualTo(previous.id());
        assertThat(ofType("MISSING_OBJECT")).isEmpty();
        clock.advance(601);
        assertThat(full().status()).isEqualTo("PARTIAL");
        assertThat(ofType("ORPHAN_OBJECT")).singleElement().satisfies(finding -> assertThat(finding.status()).isEqualTo("SUSPECTED"));
    }

    @Test void storageOutageFailsTheRunWithoutCreatingMissingObjectFindings() throws Exception {
        Run previous = full();
        storage.failList = true;
        Run failed = full();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorKey()).isEqualTo("inventory_list_unavailable");
        assertThat(repository.latestComplete().orElseThrow().id()).isEqualTo(previous.id());
        assertThat(ofType("MISSING_OBJECT")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT storage_used FROM users WHERE id=1", Long.class)).isEqualTo(11);
    }

    @Test void cancelIsObservedBetweenPagesAndDoesNotPublishACompleteReport() throws Exception {
        storage.entered = new CountDownLatch(1); storage.release = new CountDownLatch(1);
        Run run = inspection.start(1L);
        worker.dispatch();
        try {
            assertThat(storage.entered.await(5, TimeUnit.SECONDS)).isTrue();
            inspection.cancel(run.id());
        } finally { storage.release.countDown(); }
        Run canceled = complete(run);
        assertThat(canceled.status()).isEqualTo("CANCELED");
        assertThat(canceled.inventoryComplete()).isFalse();
        assertThat(repository.latestComplete()).isEmpty();
        assertThat(storage.calls.get()).isEqualTo(1);
    }

    @Test void resumesThePersistedInventoryCursorAfterLeaseExpiry() throws Exception {
        Run run = inspection.start(1L);
        var firstPage = storage.list(null, 2);
        tx.executeWithoutResult(status -> {
            Claim claim = repository.claim(UUID.randomUUID(), 180).orElseThrow();
            long references = repository.snapshot(run.id(), "UTC");
            var delta = repository.objects(run.id(), firstPage.objects());
            repository.progress(claim, "INVENTORY", firstPage.nextToken(), delta.objects(), delta.bytes(), references, 0, true, false, 180);
        });
        jdbc.update("UPDATE storage_inspection_runs SET lease_until=now()-INTERVAL '1 second' WHERE id=?", run.id());
        storage.cursors.clear();
        Run resumed = complete(run);
        assertThat(resumed.status()).isEqualTo("SUCCEEDED");
        assertThat(storage.cursors.getFirst()).isEqualTo(firstPage.nextToken());
        assertThat(resumed.objectsScanned()).isEqualTo(4);
        assertThat(resumed.objectBytes()).isEqualByComparingTo("15");
    }

    @Test void duplicateObjectsAcrossPagesAreNotDoubleCounted() throws Exception {
        var all = new ArrayList<>(storage.objects.values());
        storage.script.put("", new Page(List.of(all.get(0), all.get(1)), "next", false));
        storage.script.put("next", new Page(List.of(all.get(1), all.get(2)), "last", false));
        storage.script.put("last", new Page(List.of(all.get(3)), null, true));
        Run run = full();
        assertThat(run.status()).isEqualTo("SUCCEEDED");
        assertThat(run.objectsScanned()).isEqualTo(4);
        assertThat(run.objectBytes()).isEqualByComparingTo("15");
    }

    @Test void structuralMetadataIssuesIdentifyAffectedRecordsAndBlockUnsafeQuotaRepair() throws Exception {
        jdbc.update("INSERT INTO users (id,username,password_hash) VALUES (2,'other_fixture','unused')");
        jdbc.update("INSERT INTO files (id,user_id,file_name,file_type,file_size) VALUES (20,2,'folder','folder',0)");
        jdbc.update("UPDATE files SET parent_id=20 WHERE id=10");
        assertThat(full().status()).isEqualTo("SUCCEEDED");
        assertThat(ofType("METADATA_INCONSISTENCY")).anySatisfy(finding -> {
            assertThat(finding.referenceId()).isEqualTo("10");
            assertThat(finding.metadataReason()).isEqualTo("CROSS_SPACE_PARENT");
        });
        assertThat(ofType("QUOTA_METADATA_INVALID")).singleElement();
        assertThat(repository.quotaFact("PERSONAL", 1).valid()).isFalse();
        jdbc.update("UPDATE files SET parent_id=NULL WHERE id=10");
        full();
        assertThat(ofType("METADATA_INCONSISTENCY")).allSatisfy(finding -> assertThat(finding.status()).isEqualTo("RESOLVED"));
    }

    @Test void headOutageDoesNotMasqueradeAsAMissingObject() throws Exception {
        Run previous = full();
        storage.objects.remove("users/1/history");
        storage.failHead = true;
        Run failed = full();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorKey()).isEqualTo("inventory_head_unavailable");
        assertThat(ofType("MISSING_OBJECT")).isEmpty();
        assertThat(repository.latestComplete().orElseThrow().id()).isEqualTo(previous.id());
    }

    @Test void expiredRunBudgetStopsBeforeAnotherStorageRequest() throws Exception {
        Run previous = full();
        Run run = inspection.start(1L);
        tx.executeWithoutResult(status -> repository.claim(UUID.randomUUID(), 180).orElseThrow());
        jdbc.update("UPDATE storage_inspection_runs SET started_at=now()-INTERVAL '3 hours', lease_until=now()-INTERVAL '1 second' WHERE id=?", run.id());
        int calls = storage.calls.get();
        Run result = complete(run);
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.errorKey()).isEqualTo("inspection_time_budget_exceeded");
        assertThat(storage.calls.get()).isEqualTo(calls);
        assertThat(repository.latestComplete().orElseThrow().id()).isEqualTo(previous.id());
    }

    @Test void stalledUploadsAndTasksResolveWhenTheirSourceFinishes() throws Exception {
        // Legacy source columns have no zone; encode fixtures in the configured source zone,
        // independently of the PostgreSQL JDBC session's host-derived TimeZone.
        var uploadCreated = LocalDateTime.ofInstant(clock.instant().minusSeconds(26 * 3600), ZoneOffset.UTC);
        var taskStarted = LocalDateTime.ofInstant(clock.instant().minusSeconds(3 * 3600), ZoneOffset.UTC);
        jdbc.update("UPDATE upload_sessions SET created_at=?", uploadCreated);
        jdbc.update("INSERT INTO team_tasks (id,actor_user_id,task_type,status,title_key,created_at,updated_at,started_at) VALUES (100,1,'ZIP','RUNNING','fixture',?,?,?)", taskStarted, taskStarted, taskStarted);
        full();
        assertThat(ofType("UPLOAD_STALLED")).singleElement().satisfies(f -> assertThat(f.status()).isEqualTo("SUSPECTED"));
        assertThat(ofType("TASK_STALLED")).singleElement().satisfies(f -> assertThat(f.status()).isEqualTo("SUSPECTED"));
        clock.advance(601); full();
        assertThat(ofType("UPLOAD_STALLED").getFirst().status()).isEqualTo("CONFIRMED");
        assertThat(ofType("TASK_STALLED").getFirst().status()).isEqualTo("CONFIRMED");
        jdbc.update("UPDATE upload_sessions SET status=2");
        jdbc.update("UPDATE team_tasks SET status='SUCCEEDED' WHERE id=100");
        full();
        assertThat(ofType("UPLOAD_STALLED").getFirst().status()).isEqualTo("RESOLVED");
        assertThat(ofType("TASK_STALLED").getFirst().status()).isEqualTo("RESOLVED");
    }

    @Test void retentionPrunesOnlyExpiredDerivedDataAndKeepsUnresolvedFindingsAndAudits() throws Exception {
        Run run = full();
        Finding orphan = ofType("ORPHAN_OBJECT").getFirst();
        var review = inspection.review(orphan.id(), 1, "NOTE", "retention fixture");
        jdbc.update("UPDATE storage_inspection_runs SET created_at=now()-INTERVAL '400 days' WHERE id=?", run.id());
        jdbc.update("UPDATE storage_inspection_findings SET first_seen_at=now()-INTERVAL '400 days',last_observed_at=now()-INTERVAL '400 days' WHERE id=?", orphan.id());
        assertThat(inspection.pruneDerivedData()).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM storage_inspection_objects WHERE run_id=?", Long.class, run.id())).isZero();
        assertThat(repository.finding(orphan.id())).isPresent();
        assertThat(findings.reviews(orphan.id())).extracting(Review::id).containsExactly(review.id());
        assertThat(repository.latestComplete().orElseThrow().id()).isEqualTo(run.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM files", Long.class)).isEqualTo(2);
        jdbc.update("UPDATE storage_inspection_findings SET status='RESOLVED',resolved_at=now()-INTERVAL '100 days' WHERE id=?", orphan.id());
        inspection.pruneDerivedData();
        assertThat(repository.finding(orphan.id())).isPresent(); // a retained review still owns the report
        jdbc.update("UPDATE storage_inspection_reviews SET created_at=now()-INTERVAL '366 days' WHERE id=?", review.id());
        inspection.pruneDerivedData();
        assertThat(repository.finding(orphan.id())).isEmpty();
        assertThat(repository.latestComplete().orElseThrow().id()).isEqualTo(run.id());
    }

    @Test void auxiliaryReferencesStayDistinctAndResolveWhenTheirObjectsReturn() throws Exception {
        jdbc.update("UPDATE files SET thumbnail_path='thumbnails/fixture' WHERE id=10");
        jdbc.update("INSERT INTO teams (id,name,owner_id,storage_used) VALUES (5,'auxiliary fixture',1,0)");
        jdbc.update("INSERT INTO files (id,user_id,team_id,file_name,file_type,file_size) VALUES (30,1,5,'team folder','folder',0),(31,1,NULL,'folder','folder',0)");
        jdbc.update("INSERT INTO folder_zip_download_cache (user_id,folder_id,object_key,file_name,file_size,content_signature) VALUES (1,31,'folder-zips/cache-fixture','fixture.zip',7,'fixture')");
        jdbc.update("INSERT INTO team_tasks (id,team_id,actor_user_id,task_type,status,title_key,target_id,result_path,result_size_bytes) VALUES (100,5,1,'FOLDER_ZIP','SUCCEEDED','fixture',30,'folder-zips/task-fixture',9)");
        assertThat(full().status()).isEqualTo("SUCCEEDED");
        assertThat(ofType("AUXILIARY_MISSING")).extracting(Finding::referenceKind)
                .containsExactlyInAnyOrder("THUMBNAIL", "ZIP_CACHE", "ZIP_TASK");
        assertThat(ofType("MISSING_OBJECT")).isEmpty();
        storage.put("thumbnails/fixture", 2, clock.instant());
        storage.put("folder-zips/cache-fixture", 7, clock.instant());
        storage.put("folder-zips/task-fixture", 9, clock.instant());
        assertThat(full().status()).isEqualTo("SUCCEEDED");
        assertThat(ofType("AUXILIARY_MISSING")).allSatisfy(f -> assertThat(f.status()).isEqualTo("RESOLVED"));
    }

    @Test void aFreshReferenceAddedDuringHeadPreventsAnOrphanFinding() throws Exception {
        var linked = new java.util.concurrent.atomic.AtomicBoolean();
        storage.onStat = key -> {
            if (key.equals("users/1/orphan") && linked.compareAndSet(false, true)) {
                tx.executeWithoutResult(status -> {
                    context.getBean(QuotaAccountingService.class).change(QuotaAccountingRepository.Scope.PERSONAL, 1, 5);
                    jdbc.update("INSERT INTO files (id,user_id,file_name,file_type,file_size,storage_path) VALUES (12,1,'new reference','file',5,'users/1/orphan')");
                });
            }
        };
        assertThat(full().status()).isEqualTo("SUCCEEDED");
        assertThat(linked).isTrue();
        assertThat(ofType("ORPHAN_OBJECT")).isEmpty();
        assertThat(repository.quotaFact("PERSONAL", 1).expectedBytes()).isEqualTo(16);
    }

    static final class MutableClock extends Clock {
        private volatile Instant value;
        MutableClock(Instant value) { this.value = value; }
        void advance(long seconds) { value = value.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(value, zone); }
        @Override public Instant instant() { return value; }
    }

    static final class FakeStorage implements StorageInventoryService {
        final Map<String, ObjectInfo> objects = new ConcurrentSkipListMap<>();
        final Set<String> omitted = ConcurrentHashMap.newKeySet();
        final Map<String, Page> script = new ConcurrentHashMap<>();
        final List<String> cursors = new java.util.concurrent.CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean failList;
        volatile boolean failHead;
        volatile java.util.function.Consumer<String> onStat = key -> {};
        volatile CountDownLatch entered;
        volatile CountDownLatch release;
        void put(String key, long size, Instant modified) { objects.put(key, new ObjectInfo(key, size, "fixture-" + size, modified)); }
        @Override public Page list(String cursor, int limit) {
            calls.incrementAndGet(); cursors.add(cursor == null ? "" : cursor);
            if (failList) throw new StorageInventoryException("inventory_list_unavailable");
            if (entered != null) {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("missing fixture release"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            }
            if (!script.isEmpty()) return Objects.requireNonNull(script.get(cursor == null ? "" : cursor));
            var rows = objects.values().stream().filter(object -> !omitted.contains(object.key())).toList();
            int offset = cursor == null ? 0 : Integer.parseInt(cursor);
            int end = Math.min(offset + limit, rows.size());
            return new Page(rows.subList(offset, end), end == rows.size() ? null : Integer.toString(end), end == rows.size());
        }
        @Override public Optional<ObjectInfo> stat(String key) {
            if (failHead) throw new StorageInventoryException("inventory_head_unavailable");
            onStat.accept(key);
            return Optional.ofNullable(objects.get(key));
        }
    }
}
