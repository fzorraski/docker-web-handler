package br.com.fzdevx.domain.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PsqlErrorCollectorTest {

    @Test
    void recognisesPsqlPgRestoreAndServerErrorLines() {
        PsqlErrorCollector c = new PsqlErrorCollector();
        c.accept("psql:/scripts/mandatory/02-master-ip-and-port-cleaner.sql:4: ERROR:  relation \"master_config\" does not exist");
        c.accept("pg_restore: error: could not execute query: ERROR:  role \"wms\" does not exist");
        c.accept("FATAL:  password authentication failed for user \"postgres\"");
        c.accept("UPDATE 12");
        c.accept("pg_restore: warning: errors ignored on restore: 3");
        c.accept("NOTICE:  table \"tmp\" does not exist, skipping");
        c.accept(null);

        assertEquals(3, c.count());
        assertEquals(3, c.lines().size());
        assertTrue(c.summary().startsWith("3 error(s): psql:/scripts/mandatory/02-master-ip-and-port-cleaner.sql:4: ERROR:"), c.summary());
        assertTrue(c.summary().contains("FATAL:  password authentication failed"), c.summary());
    }

    @Test
    void keepsOnlyTheFirstLinesButCountsAll() {
        PsqlErrorCollector c = new PsqlErrorCollector();
        for (int i = 1; i <= 8; i++) {
            c.accept("ERROR:  problem " + i);
        }
        assertEquals(8, c.count());
        assertEquals(5, c.lines().size());
        assertTrue(c.summary().endsWith("| ... and 3 more"), c.summary());
    }

    @Test
    void recognisesContextLinesThatFollowAnError() {
        assertTrue(PsqlErrorCollector.isErrorOrContextLine("pg_restore: error: could not execute query: ERROR:  unrecognized configuration parameter \"transaction_timeout\""));
        assertTrue(PsqlErrorCollector.isErrorOrContextLine("Command was: SET transaction_timeout = 0;"));
        assertTrue(PsqlErrorCollector.isErrorOrContextLine("DETAIL:  Key (id)=(1) already exists."));
        assertTrue(PsqlErrorCollector.isErrorOrContextLine("LINE 1: SELECT * FROM nope"));
        assertFalse(PsqlErrorCollector.isErrorOrContextLine("Command was: SET transaction_timeout = 0;".replace("Command was:", "COPY 12")));
        assertFalse(PsqlErrorCollector.isErrorLine("Command was: SET transaction_timeout = 0;"));
        assertFalse(PsqlErrorCollector.isErrorOrContextLine("CREATE TABLE"));
        assertFalse(PsqlErrorCollector.isErrorOrContextLine(null));
    }

    @Test
    void emptyWhenNothingMatched() {
        PsqlErrorCollector c = new PsqlErrorCollector();
        c.accept("CREATE TABLE");
        assertTrue(c.isEmpty());
        assertNull(c.summary());
    }
}
