/* ==========================================================================
   0012 - remove the old chat/ticket system, the waitlist queue, and the
   assignments feature outright.
   --------------------------------------------------------------------------
   Three backend features are retired here:
     - dbo.chat_conversations / dbo.chat_messages -- the old student <->
       admin ticket system. NOT the University AI Assistant: the assistant
       is stateless (AIAssistantService/AIAssistantPanelController persist
       nothing of their own) and has zero overlap with these two tables.
     - dbo.waitlist -- a full section now refuses registration outright
       instead of offering a queue.
     - dbo.assignments -- was never wired to any UI/service/controller.

   Order: notifications of the three retired types are deleted first (they
   point at rows that are about to disappear), then CK_notifications_type is
   narrowed to drop those type values, then each table is dropped child-
   before-parent, each guarded so this file is safe to run twice and safe on
   a database that never had one of these features seeded.
   ========================================================================== */

-- 1. Clear out notifications of the three retired types.
IF OBJECT_ID('dbo.notifications', 'U') IS NOT NULL
BEGIN
    DELETE FROM dbo.notifications WHERE type IN (N'CHAT_REPLY', N'WAITLIST', N'ASSIGNMENT');
    PRINT N'1. Removed notifications of type CHAT_REPLY / WAITLIST / ASSIGNMENT.';
END
GO

-- 2. Narrow CK_notifications_type to drop the three retired values.
IF EXISTS (SELECT 1 FROM sys.check_constraints
            WHERE name = 'CK_notifications_type'
              AND definition LIKE '%CHAT_REPLY%')
BEGIN
    ALTER TABLE dbo.notifications DROP CONSTRAINT CK_notifications_type;
    ALTER TABLE dbo.notifications
        ADD CONSTRAINT CK_notifications_type CHECK (type IN
            (N'INFO', N'SUCCESS', N'WARNING', N'GRADE', N'PAYMENT', N'REGISTRATION', N'NEWS', N'GENERAL'));
    PRINT N'2. CK_notifications_type narrowed - CHAT_REPLY/WAITLIST/ASSIGNMENT no longer allowed.';
END
ELSE
    PRINT N'2. CK_notifications_type already narrowed - nothing to do.';
GO

-- 3. Drop dbo.chat_messages (child of chat_conversations) first.
IF OBJECT_ID('dbo.chat_messages', 'U') IS NOT NULL
BEGIN
    IF EXISTS (SELECT 1 FROM sys.foreign_keys WHERE referenced_object_id = OBJECT_ID('dbo.chat_messages'))
    BEGIN
        THROW 51003, N'dbo.chat_messages still has an incoming foreign key - not dropping it.', 1;
    END
    DROP TABLE dbo.chat_messages;
    PRINT N'3. Dropped dbo.chat_messages.';
END
ELSE
    PRINT N'3. dbo.chat_messages already gone - nothing to drop.';
GO

-- 4. Drop dbo.chat_conversations.
IF OBJECT_ID('dbo.chat_conversations', 'U') IS NOT NULL
BEGIN
    IF EXISTS (SELECT 1 FROM sys.foreign_keys WHERE referenced_object_id = OBJECT_ID('dbo.chat_conversations'))
    BEGIN
        THROW 51003, N'dbo.chat_conversations still has an incoming foreign key - not dropping it.', 1;
    END
    DROP TABLE dbo.chat_conversations;
    PRINT N'4. Dropped dbo.chat_conversations.';
END
ELSE
    PRINT N'4. dbo.chat_conversations already gone - nothing to drop.';
GO

-- 5. Drop dbo.waitlist.
IF OBJECT_ID('dbo.waitlist', 'U') IS NOT NULL
BEGIN
    IF EXISTS (SELECT 1 FROM sys.foreign_keys WHERE referenced_object_id = OBJECT_ID('dbo.waitlist'))
    BEGIN
        THROW 51003, N'dbo.waitlist still has an incoming foreign key - not dropping it.', 1;
    END
    DROP TABLE dbo.waitlist;
    PRINT N'5. Dropped dbo.waitlist.';
END
ELSE
    PRINT N'5. dbo.waitlist already gone - nothing to drop.';
GO

-- 6. Drop dbo.assignments.
IF OBJECT_ID('dbo.assignments', 'U') IS NOT NULL
BEGIN
    IF EXISTS (SELECT 1 FROM sys.foreign_keys WHERE referenced_object_id = OBJECT_ID('dbo.assignments'))
    BEGIN
        THROW 51003, N'dbo.assignments still has an incoming foreign key - not dropping it.', 1;
    END
    DROP TABLE dbo.assignments;
    PRINT N'6. Dropped dbo.assignments.';
END
ELSE
    PRINT N'6. dbo.assignments already gone - nothing to drop.';
GO

-- 7. Verify - every column below should be NULL (object no longer exists).
SELECT OBJECT_ID('dbo.chat_messages','U')      AS chat_messages_still_exists,
       OBJECT_ID('dbo.chat_conversations','U') AS chat_conversations_still_exists,
       OBJECT_ID('dbo.waitlist','U')           AS waitlist_still_exists,
       OBJECT_ID('dbo.assignments','U')        AS assignments_still_exists;
