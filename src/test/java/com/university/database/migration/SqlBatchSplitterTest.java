package com.university.database.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The splitter is what stands between a migration file and the driver, and a
 * mistake here turns a correct migration into "Incorrect syntax near 'GO'".
 */
class SqlBatchSplitterTest {

    @Test
    @DisplayName("a file with no GO is one batch")
    void singleBatch() {
        List<String> batches = SqlBatchSplitter.split("SELECT 1;\nSELECT 2;\n");
        assertEquals(1, batches.size());
        assertTrue(batches.get(0).contains("SELECT 2"));
    }

    @Test
    @DisplayName("GO on its own line separates batches and is not itself sent")
    void splitsOnGo() {
        List<String> batches = SqlBatchSplitter.split("SELECT 1;\nGO\nSELECT 2;\nGO\n");
        assertEquals(2, batches.size());
        assertFalse(batches.get(0).contains("GO"));
        assertTrue(batches.get(0).contains("SELECT 1"));
        assertTrue(batches.get(1).contains("SELECT 2"));
    }

    @Test
    @DisplayName("GO is recognised whatever its case and indentation")
    void goIsCaseInsensitive() {
        assertEquals(2, SqlBatchSplitter.split("SELECT 1;\n   go   \nSELECT 2;").size());
        assertEquals(2, SqlBatchSplitter.split("SELECT 1;\nGo -- next\nSELECT 2;").size());
    }

    @Test
    @DisplayName("a word merely starting with GO is not a separator")
    void doesNotSplitOnGoto() {
        assertEquals(1, SqlBatchSplitter.split("SELECT 1;\nGOTO done;\nSELECT 2;").size());
    }

    @Test
    @DisplayName("GO inside a block comment is not a separator")
    void ignoresGoInsideBlockComment() {
        String script = """
                /* the old script said
                GO
                here, which it must not act on */
                SELECT 1;
                """;
        List<String> batches = SqlBatchSplitter.split(script);
        assertEquals(1, batches.size());
        assertTrue(batches.get(0).contains("SELECT 1"));
    }

    @Test
    @DisplayName("GO inside a string literal is not a separator")
    void ignoresGoInsideStringLiteral() {
        String script = "PRINT N'first\nGO\nsecond';\nSELECT 1;\n";
        assertEquals(1, SqlBatchSplitter.split(script).size());
    }

    @Test
    @DisplayName("an escaped quote does not end the literal")
    void handlesEscapedQuotes() {
        String script = "PRINT N'it''s fine';\nGO\nSELECT 1;\n";
        assertEquals(2, SqlBatchSplitter.split(script).size());
    }

    @Test
    @DisplayName("batches holding only comments or whitespace are dropped")
    void dropsEmptyBatches() {
        String script = """
                SELECT 1;
                GO

                -- nothing here
                GO
                """;
        assertEquals(1, SqlBatchSplitter.split(script).size());
    }

    @Test
    @DisplayName("USE batches are recognised so they can be skipped")
    void recognisesDatabaseSwitch() {
        assertTrue(SqlBatchSplitter.isDatabaseSwitch("USE universitymanagementDB;\n"));
        assertTrue(SqlBatchSplitter.isDatabaseSwitch("  use [universitymanagementDB]  "));
        assertTrue(SqlBatchSplitter.isDatabaseSwitch("/* c */ USE universitymanagementDB;"));
        assertFalse(SqlBatchSplitter.isDatabaseSwitch("USE universitymanagementDB;\nSELECT 1;"));
        assertFalse(SqlBatchSplitter.isDatabaseSwitch("SELECT 1;"));
    }

    @Test
    @DisplayName("the real 0001 migration survives a round trip")
    void splitsTheFirstRealMigration() {
        List<MigrationCatalog.Migration> migrations = MigrationCatalog.discover();
        MigrationCatalog.Migration first = migrations.stream()
                .filter(m -> m.name().equals("0001_users_role_index.sql"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("0001_users_role_index.sql was not found"));

        List<String> batches = SqlBatchSplitter.split(first.sql());
        assertFalse(batches.isEmpty());
        assertTrue(batches.stream().anyMatch(b -> b.contains("IX_users_role")));
        assertFalse(batches.stream().anyMatch(SqlBatchSplitter::isDatabaseSwitch));
    }
}
