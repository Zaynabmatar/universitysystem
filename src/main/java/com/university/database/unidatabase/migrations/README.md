# Database migrations

Every database change lives here as a numbered `.sql` file. The application
applies them automatically at startup, so after

```
git pull
mvn clean javafx:run
```

another developer's database is brought up to date on its own, before the login
screen opens. Nobody runs anything by hand.

## How it works

`com.university.database.migration.MigrationRunner` runs on the startup thread
in `App.start()`, straight after the connection check and before the first
screen is built. It:

1. connects through `DBConnection` — there is no second connection system;
2. creates `dbo.schema_migrations` if it is missing;
3. reads the migration names already recorded there;
4. runs every `.sql` file in this directory that is not recorded, **in filename
   order**, each one inside its own transaction;
5. writes the filename into `dbo.schema_migrations` in that same transaction.

Because the change and its ledger row commit together, a migration is either
fully applied and recorded, or neither. If one fails it is rolled back, it is
not recorded, **no later migration runs**, and startup stops with a dialog
naming the file.

```sql
CREATE TABLE dbo.schema_migrations
(
    migration_name NVARCHAR(260) NOT NULL,   -- the filename, e.g. 0002_add_foo.sql
    applied_at     DATETIME2(0)  NOT NULL,   -- defaults to SYSDATETIME()
    CONSTRAINT PK_schema_migrations PRIMARY KEY (migration_name)
);
```

`migration_name` is the primary key, so "never twice" is enforced by SQL Server
and not only by the check in Java.

## Adding a migration

1. Apply the change to your own database (running the app is enough once the
   file exists).
2. Create the next numbered file: `0002_short_description.sql`.
3. Make it safe to run against a database that already has the change —
   `IF NOT EXISTS`, `IF OBJECT_ID(...) IS NULL`, `IF COL_LENGTH(...) IS NULL`.
4. Commit the `.sql` file together with the Java that needs it.

**Never edit a migration that has been committed.** It has already run on
someone's database, and the runner will not run it again — so an edit is a
change that silently exists on your machine and nowhere else. Write a new
numbered file instead.

### Rules for the SQL itself

- **No `CREATE DATABASE`, `DROP DATABASE`, `DROP TABLE`, or `DELETE` of real
  data.** Migrations move the schema forward; they do not rebuild it.
- **No seed data that could duplicate rows.** If a migration must insert, guard
  it with `WHERE NOT EXISTS`. The files in `../seed` are *not* migrations and
  are never run by the runner.
- **No `BEGIN TRANSACTION` / `COMMIT` / `ROLLBACK`.** The runner already wraps
  the file in one transaction; a `COMMIT` inside would end it early and defeat
  the rollback.
- **No `USE <database>`.** It is illegal inside a transaction (SQL Server error
  226) and the connection is already on the configured database. A batch that
  contains nothing else is skipped, so a pasted script still works.
- **No server names, passwords, or local paths.** The connection comes from
  `DBConnection`; the password comes from `~/.universitysystem/db.properties`.
- `GO` is fine. The runner splits the file on it, exactly as sqlcmd does.

## Legacy scripts: `phase11`, `phase16`, `phase17`

These three predate the runner and were applied by hand. They are **baselined**:
recorded in `schema_migrations` as applied, without being executed. The list is
`MigrationRunner.BASELINE`.

This is deliberate. `phase11_grades_scale.sql` drops constraints and renames
columns with `sp_rename`; re-running it against a database that already has
`coursework_mark` fails, and against a half-applied one it could do worse.
`phase16` and `phase17` are guarded and would survive a re-run, but they belong
to the same hand-applied era and one of them (`phase17`) was only ever run
against a different SQL Server instance — auto-applying it now would add a
`users.email` column that no current Java code reads.

Baselining happens once, the first time the runner sees a database without a
`schema_migrations` table entry for them. Nothing is executed.

The base schema itself (`../universitymanagmentDB.sql`) and the seed data
(`../seed/*.sql`) are also outside the runner. A brand-new database is still
created from those by hand, exactly as before; migrations take over from there.
