package com.university.database.migration;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Finds the migration scripts and hands them back in filename order.
 *
 * <p>The scripts live in the source tree, under
 * {@code src/main/java/com/university/database/unidatabase/migrations}, so that
 * a schema change and the code that depends on it travel in the same commit.
 * That directory is a Java source root, which Maven does not treat as a
 * resource root by default, so {@code pom.xml} copies the {@code .sql} files
 * into {@code target/classes} explicitly.</p>
 *
 * <p>Two lookups, in this order:</p>
 * <ol>
 *   <li>the source directory on disk, found by walking up from the working
 *       directory — this is what {@code mvn javafx:run} and the IDE hit, and it
 *       picks up a file the moment it is saved, without a rebuild;</li>
 *   <li>the classpath copy, which is what a packaged build has.</li>
 * </ol>
 *
 * <p>{@code -Duniversity.migrations.dir=<path>} overrides both, which is how
 * the runner can be pointed at a scratch directory during testing.</p>
 */
final class MigrationCatalog {

    /** Path of the migrations directory, relative to the project root and to the classpath root. */
    static final String RELATIVE_PATH = "com/university/database/unidatabase/migrations";

    private static final String SOURCE_ROOT = "src/main/java";

    private static final String DIRECTORY_OVERRIDE_PROPERTY = "university.migrations.dir";

    /** How far up from the working directory to look for the project root. */
    private static final int MAX_PARENTS_SEARCHED = 4;

    private MigrationCatalog() {
    }

    /** One migration script: its filename and its text. */
    record Migration(String name, String sql) {
    }

    /**
     * @return every {@code .sql} file in the migrations directory, ordered by
     *         filename, which is why filenames are numbered
     * @throws MigrationException if the directory cannot be found or read
     */
    static List<Migration> discover() {
        Path directory = locateOnDisk();
        if (directory != null) {
            return readFrom(directory);
        }

        List<Migration> fromClasspath = readFromClasspath();
        if (fromClasspath != null) {
            return fromClasspath;
        }

        throw new MigrationException(
                "Cannot find the database migrations directory (" + SOURCE_ROOT + "/" + RELATIVE_PATH
                + "). Run the application from the project directory, or set -D"
                + DIRECTORY_OVERRIDE_PROPERTY + "=<path>.", null);
    }

    /** Where the scripts were read from, for the startup log. */
    static String describeSource() {
        Path directory = locateOnDisk();
        if (directory != null) {
            return directory.toString();
        }
        URL url = Thread.currentThread().getContextClassLoader().getResource(RELATIVE_PATH);
        return url != null ? url.toString() : "(not found)";
    }

    private static Path locateOnDisk() {
        String override = System.getProperty(DIRECTORY_OVERRIDE_PROPERTY);
        if (override != null && !override.isBlank()) {
            Path path = Path.of(override);
            if (!Files.isDirectory(path)) {
                throw new MigrationException(
                        "-D" + DIRECTORY_OVERRIDE_PROPERTY + " points at " + path
                        + ", which is not a directory.", null);
            }
            return path;
        }

        Path base = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (int i = 0; i <= MAX_PARENTS_SEARCHED && base != null; i++) {
            Path candidate = base.resolve(SOURCE_ROOT).resolve(RELATIVE_PATH);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            base = base.getParent();
        }
        return null;
    }

    private static List<Migration> readFrom(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> scripts = files
                    .filter(Files::isRegularFile)
                    .filter(p -> isSqlFile(p.getFileName().toString()))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString(), NAME_ORDER))
                    .toList();

            List<Migration> migrations = new ArrayList<>(scripts.size());
            for (Path script : scripts) {
                migrations.add(new Migration(
                        script.getFileName().toString(),
                        stripByteOrderMark(Files.readString(script, StandardCharsets.UTF_8))));
            }
            return migrations;
        } catch (IOException e) {
            throw new MigrationException("Cannot read the migrations directory " + directory, e);
        }
    }

    /** @return the classpath copy of the scripts, or null if there is none */
    private static List<Migration> readFromClasspath() {
        ClassLoader loader = MigrationCatalog.class.getClassLoader();
        URL url = loader.getResource(RELATIVE_PATH);
        if (url == null) {
            return null;
        }

        try {
            List<String> names = switch (url.getProtocol()) {
                case "file" -> listFileUrl(url);
                case "jar" -> listJarUrl(url);
                default -> null;
            };
            if (names == null) {
                return null;
            }

            names.sort(NAME_ORDER);
            List<Migration> migrations = new ArrayList<>(names.size());
            for (String name : names) {
                try (InputStream in = loader.getResourceAsStream(RELATIVE_PATH + "/" + name)) {
                    if (in == null) {
                        throw new MigrationException(name,
                                "Migration " + name + " is listed on the classpath but cannot be opened.", null);
                    }
                    migrations.add(new Migration(name,
                            stripByteOrderMark(new String(in.readAllBytes(), StandardCharsets.UTF_8))));
                }
            }
            return migrations;
        } catch (IOException | URISyntaxException e) {
            throw new MigrationException("Cannot read the migrations from the classpath (" + url + ")", e);
        }
    }

    private static List<String> listFileUrl(URL url) throws URISyntaxException, IOException {
        Path directory = Path.of(url.toURI());
        try (Stream<Path> files = Files.list(directory)) {
            return files
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(MigrationCatalog::isSqlFile)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }
    }

    private static List<String> listJarUrl(URL url) throws IOException {
        JarURLConnection connection = (JarURLConnection) url.openConnection();
        connection.setUseCaches(false);
        List<String> names = new ArrayList<>();
        try (JarFile jar = connection.getJarFile()) {
            String prefix = RELATIVE_PATH + "/";
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String entry = entries.nextElement().getName();
                if (entry.startsWith(prefix) && isSqlFile(entry)) {
                    String name = entry.substring(prefix.length());
                    if (!name.isEmpty() && name.indexOf('/') < 0) {
                        names.add(name);
                    }
                }
            }
        }
        return names;
    }

    private static boolean isSqlFile(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).endsWith(".sql");
    }

    /**
     * Filename order, decided the same way on every machine.
     *
     * <p>Deliberately not the default locale collation: on a Turkish locale
     * {@code "I".toLowerCase()} is not {@code "i"}, and the order migrations run
     * in must not depend on where the developer lives.</p>
     */
    private static final Comparator<String> NAME_ORDER = Comparator.naturalOrder();

    /**
     * SQL Server Management Studio saves scripts as UTF-8 with a byte order
     * mark. Left in place it becomes an invisible character at the very start
     * of the first batch, and SQL Server answers "Incorrect syntax near '?'".
     */
    private static String stripByteOrderMark(String text) {
        return text.startsWith("﻿") ? text.substring(1) : text;
    }
}
