package br.com.fzdevx.infrastructure.persistence.jdbc;

import br.com.fzdevx.domain.exception.DuplicateEntityException;
import br.com.fzdevx.domain.model.ContainerSchedule;
import br.com.fzdevx.domain.model.DatabaseDump;
import br.com.fzdevx.domain.model.DatabaseSnapshot;
import br.com.fzdevx.domain.model.ManagedDatabase;
import br.com.fzdevx.domain.model.RunContainerConfig;
import br.com.fzdevx.domain.model.ScheduleAction;
import br.com.fzdevx.domain.model.ScheduleType;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(PostgresBackendProfile.class)
class PgOperationalRepositoriesTest {

    @Inject
    PgManagedDatabaseRepository managedDatabases;

    @Inject
    PgDumpRepository dumps;

    @Inject
    PgSnapshotRepository snapshots;

    @Inject
    PgScheduleRepository schedules;

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    // ---- managed databases ----

    @Test
    void managedDatabase_caseInsensitiveIdentity() {
        String repo = unique("repo");
        ManagedDatabase db = new ManagedDatabase();
        db.setRepository(repo);
        db.setName("MyDatabase");
        db.setDescription("first");
        managedDatabases.save(db);

        // case-insensitive find and upsert
        assertTrue(managedDatabases.find(repo.toUpperCase(), "mydatabase").isPresent());
        ManagedDatabase again = new ManagedDatabase();
        again.setRepository(repo);
        again.setName("MYDATABASE");
        again.setDescription("second");
        managedDatabases.save(again);

        List<ManagedDatabase> all = managedDatabases.findByRepository(repo);
        assertEquals(1, all.size());
        assertEquals("second", all.get(0).getDescription());

        managedDatabases.delete(repo, "mydatabase");
        assertTrue(managedDatabases.find(repo, "MyDatabase").isEmpty());
    }

    @Test
    void bulkMarkUsed_createsBumpsAndNeverRegresses() {
        String repo = unique("repo");
        Instant early = now().minus(1, ChronoUnit.HOURS);
        Instant late = now();

        // creates a missing record
        managedDatabases.bulkMarkUsed(repo, Map.of("db1", late, "db2", early));
        assertEquals(late, managedDatabases.find(repo, "db1").orElseThrow().getAppLastUsedAt());

        // regression attempt is skipped, forward bump applies (case-insensitive match)
        managedDatabases.bulkMarkUsed(repo, Map.of("DB1", early, "db2", late));
        assertEquals(late, managedDatabases.find(repo, "db1").orElseThrow().getAppLastUsedAt());
        assertEquals(late, managedDatabases.find(repo, "db2").orElseThrow().getAppLastUsedAt());
        // no case-duplicate row was created
        assertEquals(2, managedDatabases.findByRepository(repo).size());
    }

    // ---- dumps ----

    @Test
    void dump_roundTripAndConstraintBackedDedup() {
        DatabaseDump dump = new DatabaseDump();
        dump.setId(UUID.randomUUID().toString());
        dump.setOriginalFilename(unique("backup") + ".sql.gz");
        dump.setStoredFilename(unique("stored"));
        dump.setMd5Hash(unique("md5"));
        dump.setFormat(DatabaseDump.Format.CUSTOM);
        dump.setFileSize(1234);
        dump.setUploadedAt(now());
        dump.setSharedWithTenants(List.of("t1"));
        dumps.save(dump);

        DatabaseDump loaded = dumps.findById(dump.getId()).orElseThrow();
        assertEquals(DatabaseDump.Format.CUSTOM, loaded.getFormat());
        assertEquals(List.of("t1"), loaded.getSharedWithTenants());
        assertTrue(dumps.findByMd5Hash(dump.getMd5Hash()).isPresent());
        assertTrue(dumps.findByOriginalFilename(dump.getOriginalFilename()).isPresent());

        // same md5, different id -> unique constraint closes the dedup race
        DatabaseDump twin = new DatabaseDump();
        twin.setId(UUID.randomUUID().toString());
        twin.setOriginalFilename(unique("other"));
        twin.setStoredFilename(unique("stored"));
        twin.setMd5Hash(dump.getMd5Hash());
        assertThrows(DuplicateEntityException.class, () -> dumps.save(twin));

        // atomic mutator
        assertTrue(dumps.update(dump.getId(), d -> d.setDescription("touched")));
        assertEquals("touched", dumps.findById(dump.getId()).orElseThrow().getDescription());
        dumps.delete(dump.getId());
    }

    // ---- snapshots ----

    @Test
    void snapshot_roundTripAndMutator() {
        DatabaseSnapshot snapshot = new DatabaseSnapshot();
        snapshot.setId(UUID.randomUUID().toString());
        snapshot.setStoredFilename(unique("snap"));
        snapshot.setRepository("repo-a");
        snapshot.setSourceDatabaseName("db1");
        snapshot.setFormat(DatabaseSnapshot.Format.CUSTOM);
        snapshot.setCreatedAt(now());
        snapshot.setTemporary(true);
        snapshots.save(snapshot);

        DatabaseSnapshot loaded = snapshots.findById(snapshot.getId()).orElseThrow();
        assertTrue(loaded.isTemporary());
        assertEquals(DatabaseSnapshot.Format.CUSTOM, loaded.getFormat());

        assertTrue(snapshots.update(snapshot.getId(), s -> s.setLabel("v1")));
        assertEquals("v1", snapshots.findById(snapshot.getId()).orElseThrow().getLabel());
        snapshots.delete(snapshot.getId());
    }

    // ---- schedules ----

    @Test
    void schedule_configBlobRoundTrip_andTargetedExecutionWrite() {
        ContainerSchedule schedule = new ContainerSchedule();
        schedule.setId(UUID.randomUUID().toString());
        schedule.setName(unique("nightly"));
        schedule.setAction(ScheduleAction.CREATE);
        schedule.setScheduleType(ScheduleType.RECURRING);
        schedule.setEnabled(true);
        schedule.setCronExpression("0 3 * * *");
        schedule.setCreatedAt(now());
        RunContainerConfig config = new RunContainerConfig();
        config.setRepository("repo-a");
        config.setTag("1.2.3");
        schedule.setCreateConfig(config);
        schedules.save(schedule);

        ContainerSchedule loaded = schedules.findById(schedule.getId()).orElseThrow();
        assertEquals("repo-a", loaded.getCreateConfig().getRepository());
        assertEquals("1.2.3", loaded.getCreateConfig().getTag());

        // simulate concurrent admin edit + execution write-back: both must survive
        schedules.save(loadedWithName(loaded, "renamed"));
        Instant executedAt = now();
        schedules.recordExecution(schedule.getId(), "SUCCESS", "ok", executedAt);
        schedules.updateNextExecution(schedule.getId(), executedAt.plus(1, ChronoUnit.DAYS));

        ContainerSchedule after = schedules.findById(schedule.getId()).orElseThrow();
        assertEquals("renamed", after.getName());
        assertEquals("SUCCESS", after.getLastExecutionStatus());
        assertEquals(executedAt, after.getLastExecutedAt());
        assertEquals(executedAt.plus(1, ChronoUnit.DAYS), after.getNextExecutionAt());

        schedules.delete(schedule.getId());
        assertTrue(schedules.findById(schedule.getId()).isEmpty());
    }

    private static ContainerSchedule loadedWithName(ContainerSchedule schedule, String name) {
        schedule.setName(name);
        return schedule;
    }
}
