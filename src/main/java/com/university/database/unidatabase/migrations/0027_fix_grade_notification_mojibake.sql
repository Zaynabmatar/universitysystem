/* ==========================================================================
   0027 — repair mojibake em dash in stored grade notification messages

   WHY
     GradeService.notifyPublished / notifySubmitted / notifyOverride built the
     "<code> — <title>" part of every grade notification with a source-file
     literal that was itself corrupted: a UTF-8 em dash (2014) that had been
     round-tripped through Windows-1252 three times before being pasted into
     the .java file, so the constant baked into the JAR was the 18-character
     mojibake sequence C3 83 C2 A2 ... rather than a single em dash. Every
     notification those methods ever sent inherited the corruption verbatim
     (see notification_id 25042 etc. — "CS201 <mojibake> Data Structures").
     The source fix (same change set) stops new rows from being corrupted;
     this migration repairs the ones already written.

     Scanned every text column in the database for this pattern (and for
     stray Ã/Â/ƒ/U+FFFD generally, using a binary collation so an
     accent-insensitive default collation could not hide matches) —
     dbo.notifications.message is the only column affected. Nothing else in
     the schema needs this fix.

   SAFE TO RE-RUN
     REPLACE is a no-op once the mojibake sequence is gone, and the WHERE
     clause only ever touches rows that still contain it.
============================================================================ */

SET NOCOUNT ON;

DECLARE @bad NVARCHAR(50) =
    NCHAR(0x00C3) + NCHAR(0x0192) + NCHAR(0x00C2) + NCHAR(0x00A2) + NCHAR(0x00C3) + NCHAR(0x00A2) +
    NCHAR(0x00E2) + NCHAR(0x20AC) + NCHAR(0x0161) + NCHAR(0x00C2) + NCHAR(0x00AC) + NCHAR(0x00C3) +
    NCHAR(0x00A2) + NCHAR(0x00E2) + NCHAR(0x201A) + NCHAR(0x00AC) + NCHAR(0x00C2) + NCHAR(0x009D);
DECLARE @good NCHAR(1) = NCHAR(0x2014);

UPDATE dbo.notifications
   SET message = REPLACE(message COLLATE Latin1_General_BIN2, @bad COLLATE Latin1_General_BIN2,
                          @good COLLATE Latin1_General_BIN2)
 WHERE message COLLATE Latin1_General_BIN2 LIKE '%' + @bad + '%' COLLATE Latin1_General_BIN2;

PRINT N'0027: notification rows repaired = ' + CAST(@@ROWCOUNT AS NVARCHAR(10));
