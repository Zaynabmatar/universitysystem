package com.university.database.migration;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a migration script into the batches JDBC can actually execute.
 *
 * <p>{@code GO} is not T-SQL. It is a separator that sqlcmd and SSMS understand
 * and that the JDBC driver does not: sending a whole file containing {@code GO}
 * to {@link java.sql.Statement#execute} fails with "Incorrect syntax near
 * 'GO'". So the file has to be cut into batches here, at every line that
 * consists of nothing but {@code GO}.</p>
 *
 * <p>The scan is comment- and string-aware. A bare {@code GO} inside a block
 * comment or inside a string literal is not a separator, and cutting there
 * would produce two batches that are each unparseable — a failure mode that
 * looks like a broken migration rather than a broken splitter.</p>
 */
final class SqlBatchSplitter {

    private SqlBatchSplitter() {
    }

    /**
     * @param script the full text of a migration file
     * @return its batches, in order, with blank ones dropped
     */
    static List<String> split(String script) {
        List<String> batches = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        // Carried across lines: SQL Server block comments nest, and both block
        // comments and string literals may span any number of lines.
        int blockCommentDepth = 0;
        boolean inString = false;

        for (String line : script.split("\r\n|\n|\r", -1)) {
            if (blockCommentDepth == 0 && !inString && isBatchSeparator(line)) {
                addIfNotBlank(batches, current);
                current.setLength(0);
                continue;
            }

            current.append(line).append('\n');

            // Advance the state machine over this line so the next line knows
            // whether it is inside a comment or a literal.
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                char next = (i + 1 < line.length()) ? line.charAt(i + 1) : '\0';

                if (inString) {
                    if (c == '\'') {
                        if (next == '\'') {
                            i++;          // '' is an escaped quote, still inside the literal
                        } else {
                            inString = false;
                        }
                    }
                } else if (blockCommentDepth > 0) {
                    if (c == '*' && next == '/') {
                        blockCommentDepth--;
                        i++;
                    } else if (c == '/' && next == '*') {
                        blockCommentDepth++;
                        i++;
                    }
                } else if (c == '-' && next == '-') {
                    break;                // line comment: nothing after it counts
                } else if (c == '/' && next == '*') {
                    blockCommentDepth++;
                    i++;
                } else if (c == '\'') {
                    inString = true;
                }
            }
        }

        addIfNotBlank(batches, current);
        return batches;
    }

    /**
     * True for a line that is only {@code GO} — optionally with a repeat count
     * (which is ignored; migrations must be written to run exactly once) or a
     * trailing line comment, both of which sqlcmd accepts.
     */
    private static boolean isBatchSeparator(String line) {
        String trimmed = line.trim();
        int comment = trimmed.indexOf("--");
        if (comment >= 0) {
            trimmed = trimmed.substring(0, comment).trim();
        }
        if (trimmed.isEmpty()) {
            return false;
        }
        return trimmed.equalsIgnoreCase("GO")
                || trimmed.toUpperCase(java.util.Locale.ROOT).matches("GO\\s+\\d+");
    }

    private static void addIfNotBlank(List<String> batches, StringBuilder batch) {
        String text = batch.toString();
        if (!isEffectivelyEmpty(text)) {
            batches.add(text);
        }
    }

    /**
     * True when a batch holds no statement — only whitespace and comments.
     *
     * <p>Sending one of those to SQL Server is not harmless: an empty batch
     * makes the driver throw, so a file that merely ends with a comment after
     * its last {@code GO} would fail for no reason.</p>
     */
    private static boolean isEffectivelyEmpty(String batch) {
        return stripComments(batch).isBlank();
    }

    /**
     * True when the batch does nothing but switch database.
     *
     * <p>{@code USE <database>} is illegal inside a multi-statement transaction
     * (SQL Server error 226), and every migration here runs in one. It is also
     * pointless: the connection is already open on the configured database, and
     * a hardcoded database name in a migration is exactly the kind of
     * machine-specific detail that must not be in the repository. Scripts
     * copied in from sqlcmd usually start with one, so it is dropped rather
     * than allowed to fail the migration.</p>
     */
    static boolean isDatabaseSwitch(String batch) {
        String bare = stripComments(batch).trim();
        if (bare.endsWith(";")) {
            bare = bare.substring(0, bare.length() - 1).trim();
        }
        return bare.toUpperCase(java.util.Locale.ROOT).matches("USE\\s+[\\[\\]\\w.\"]+");
    }

    /** Removes line and block comments, leaving whitespace where they were. */
    private static String stripComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int depth = 0;
        boolean inString = false;
        boolean inLineComment = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = (i + 1 < sql.length()) ? sql.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    out.append(c);
                }
            } else if (inString) {
                out.append(c);
                if (c == '\'') {
                    if (next == '\'') {
                        out.append(next);
                        i++;
                    } else {
                        inString = false;
                    }
                }
            } else if (depth > 0) {
                if (c == '*' && next == '/') {
                    depth--;
                    i++;
                } else if (c == '/' && next == '*') {
                    depth++;
                    i++;
                } else if (c == '\n') {
                    out.append(c);
                }
            } else if (c == '-' && next == '-') {
                inLineComment = true;
                i++;
            } else if (c == '/' && next == '*') {
                depth++;
                i++;
            } else {
                out.append(c);
                if (c == '\'') {
                    inString = true;
                }
            }
        }
        return out.toString();
    }
}
