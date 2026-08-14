/* ============================================================================
   0017 — remove the unfinished university_news feature.

   dbo.university_news, UniversityNewsDAO, NewsService and the UniversityNews
   model were a fully-built DB/DAO/service layer with no controller or FXML
   screen ever wired on top of it (confirmed by a full grep of controllers,
   FXML and every other Java file — zero references outside those three
   classes). Nothing reads or writes it, so its data is disposable, unlike a
   table this project would normally never DROP mid-migration (see
   migrations/README.md, "no DROP TABLE ... migrations move the schema
   forward, they do not rebuild it"). This is a deliberate, narrow exception
   for that reason — confirmed with the project owner before writing this
   file — not a precedent for dropping tables generally.

   A full database backup was taken immediately before this migration was
   first applied (universitymanagementDB_pre_news_removal_20260814.bak).

   SAFE TO RE-RUN: guarded by OBJECT_ID, so a second run is a no-op.
============================================================================ */

IF OBJECT_ID('dbo.university_news', 'U') IS NOT NULL
BEGIN
    DROP TABLE dbo.university_news;
END
