/* ============================================================================
   SEED 10 -- More administrator accounts, and an instructor password-hash fix
   ----------------------------------------------------------------------------
   Two independent fixes requested after the Seed 01-09 data population:

   1. ADD three more administrator accounts (the project previously had only
      the original "admin"). IDENTITY_INSERT is used because the exact
      user_id (405, 406, 407) had to be known in advance to compute the
      correct BCrypt hash of "<user_id>@iuL" -- the same password rule
      UserDAO.insert() applies to every account in this project. This does
      not touch the schema; it only pins the identity values for these three
      literal rows, the same technique the project's own base seed script
      uses for its first five rows.

   2. CORRECT the password_hash of the 48 instructor accounts added in Seed
      01. Those rows were inserted with the BCrypt hash of ("their real
      user_id - 1") + "@iuL" -- a bug caused by IDENTITY values drifting
      during earlier failed attempts at that script (rolled-back inserts do
      not give IDENTITY values back). Confirmed by comparing each account's
      stored hash against BCrypt.hashpw("<its real user_id>@iuL", ...).
      Every hash below is freshly computed for that exact, already-assigned
      user_id -- never a borrowed or literal placeholder. The 350 student
      accounts and the 2 original instructor accounts (a.khoury, s.haddad)
      were verified unaffected and are not touched here.

   Nothing else is modified: no schema change, no application code, and no
   other user/student/instructor/enrollment/grade row or relationship.
============================================================================ */

USE universitymanagementDB;
GO
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
GO

BEGIN TRY
BEGIN TRANSACTION;

-- ---------------------------------------------------------------- 1. three more admins
SET IDENTITY_INSERT dbo.users ON;

INSERT INTO dbo.users (user_id, username, password_hash, role, is_active) VALUES
    (405, N'r.osseiran', N'$2a$12$HCZBv.kTXnd4rmAFF3ZS9uzwBLbPCg9CVi.xsml4VUyVp83QnfzqW', N'ADMIN', 1),
    (406, N'k.abdallah',  N'$2a$12$RpnajpOW8rPrp9WuakZxa.WlL26i/jXc1vDiaJymP8Pzo74xx3exe', N'ADMIN', 1),
    (407, N'd.chalhoub',  N'$2a$12$qUFitaqlOBTNsrK.lGOCQOVOl4P3tyRQCkd.Fj9Bsk8iB39EHGtZe', N'ADMIN', 1);

SET IDENTITY_INSERT dbo.users OFF;

-- ---------------------------------------------------------------- 2. instructor password fix
UPDATE u SET u.password_hash = v.hash
FROM dbo.users u
JOIN (VALUES
    (7, N'$2a$12$BQBZ0Zae/yr.jcbAYXNsSe..7gsnYl4EdzXTPCfVr3ru39kHqME/S'),
    (8, N'$2a$12$fnBVUjLEJIbJCFYRrLMwDeKX1rHFrl/9vYgoSL/o8YcCOvLPvECVe'),
    (9, N'$2a$12$BP.NrSc.LDoe2cWe2kvYG.eTTysxdxTZFQeS2mXAGtX5a9tBbOjU6'),
    (10, N'$2a$12$D0XU5WiZWTwVifYp9KxEjulzE1Lhv5UCyMWGZzqb8rbBE2zSf5diS'),
    (11, N'$2a$12$33VND6Qo62wXpOthdfR5j.Q110HZmKbLMin8dakDgylEtNNeDRtjW'),
    (12, N'$2a$12$0i.kM7P8fCsApNjCj7ilou8xePJlsOylnXGLCo6dGkYaQc6oqvZmu'),
    (13, N'$2a$12$UfCu1NhFU9K7YHfcQIE6ee0JCJm9OYRPiMWyN9Q2kq1.68WjKXPUC'),
    (14, N'$2a$12$yDQvWCULDPOlQoKmjnakBuiaVUGaXfGq8DRb35KWdtUZrwhVvBfoa'),
    (15, N'$2a$12$uSaweOxTAnECnnIqlGUjZeSn8GsvC3zQSRB.BjLT5FJ43JgbbHBRq'),
    (16, N'$2a$12$/dbOfTsTOLE/4eqOH.A5XeSa8GKGWWvLESx1uxgH3ffMP3SFnIn.O'),
    (17, N'$2a$12$OWyqFiQpAuYVo0l80D08nuKmMjCTJ/2g/vwSu9Ipv3ukKuUnnorJy'),
    (18, N'$2a$12$C9Wxbk9MSbGk1D0maOA22eHNDbWU/pYXyRsO8h18/qkdjXgEENHnC'),
    (19, N'$2a$12$Vk5MX/qBW1A.Krr2/d51mehsOjP4FYmBgU2SorlOuCLUG.rPc/rNS'),
    (20, N'$2a$12$tnYMCKI3zJnpcK3vBmyP0uzABx1ocvk3jxRJeicPArIukM8eVLShG'),
    (21, N'$2a$12$p/tq9qSgc2E5OaeTJLo7UOWxPQVU8ZExy/ePafSwLOyBFA0YDqo.S'),
    (22, N'$2a$12$9uA/kbRFdspmfnh6TI0u0.tb.k7e9TQMZZfzRcovkmoglwM.F9Yb.'),
    (23, N'$2a$12$oioiXfT8E2kVGddAHhGFk.yU13XcocrH5f49jqX7O4G2oEWa4CQ0S'),
    (24, N'$2a$12$5MogN2GVjhvaXjDE1xs58.J2kLVCOyRhBdbxpibDhzy.r.JB9kkoW'),
    (25, N'$2a$12$grY.C93twHW00SgBD2Iaj.cbCuXCMxHsITK3XMFDTj8RGQAPU.dz.'),
    (26, N'$2a$12$8SfnKkvEt1jCZEgRU63CDulNQbJYwQCWPKYIqlz0mk16k4jtevgHm'),
    (27, N'$2a$12$3V4i4/dWpSQbqMQVvpNbzusk7L7ikrdj/kaIWcU0w2Wf3nTyY5ovG'),
    (28, N'$2a$12$inAVe9vCJ7Mrjncpxbt4wO6xBRGYKqM8vAmWwr0Yz4Za43Cf2tl3i'),
    (29, N'$2a$12$8kFO8gC0TTAssd2Ma8lDx..3ZucF.GDo/aB50QqPE5cZQxbB.L0jm'),
    (30, N'$2a$12$N9okF0W6L0A8EuuRHe4og.NHrbyFqdxJ/F0OjADY1O1jEZM1AI.K6'),
    (31, N'$2a$12$7HqFcBsnncraRVNzJl9nmuoRPDIWGSn1p540P2vU9MWyOJ290wkPK'),
    (32, N'$2a$12$fvS8ZnotXrmvlfBLm02Uv.haoUSER3ApemGUg/BHju1apdwz7wJW2'),
    (33, N'$2a$12$CnZ2HkusIB5qX3AINsRvVegGxjtkhJhkDavF8IiByTwrkypsTOtTO'),
    (34, N'$2a$12$0wKYlqNoRAjRsk0ezbMy9e7.kIBdjW98FbVExLzixvDe7uwedfs3i'),
    (35, N'$2a$12$LHQSakas2fVupBoXqcCjS.mozbUdYisHGTMT34q.lAEmnfjRTW2oq'),
    (36, N'$2a$12$qjibMp4SWkGq5lxrPgXkpe9PZjXRsZScusQ3fK9HU9YZWhwxC.gGu'),
    (37, N'$2a$12$uGWtpkxtva4nPYM2evQ6YeC4lG4PLoXD6dLLdNB78/0v6t9.rVAdC'),
    (38, N'$2a$12$cydY0MPWcLQP9YuAd2enJuJDO8WK7mvX.sGe2FIBxpltk1GfHPIyC'),
    (39, N'$2a$12$HXm3dOb7ikPDNMIBPm0wC.VEjxtM/xSXpMUjMyRoBsTKCcyMFgsh6'),
    (40, N'$2a$12$HTrOHWjwHL8jMbsPsQhpgODlXueYycJGpFgyjd75Jt9uefwmzOAcO'),
    (41, N'$2a$12$I0lvzCxWHsWH9/U4.TAHI.SgZXU/KWI7jKa8emcfa1IdmgTUKcPoq'),
    (42, N'$2a$12$QJvdexJhUCdI8k60mvgavu0UNn84MRJ1fn15XPPWYQFYdWMUzt.Jm'),
    (43, N'$2a$12$S4B5fOBSwUQAGSOLS1xLt.g6wwHHHVTcZ7r3mecc.LC2VH8nceD4G'),
    (44, N'$2a$12$/fROpYRXKY1JciCNbajvBOXP06./5LQu.Jz4ObO0kjNEDiZxJJt4y'),
    (45, N'$2a$12$i0zupgR8PNrbJ3awEKMgq.YNXwxvsNjAzL0LkABBLtzE9UDYytZOC'),
    (46, N'$2a$12$/Vdcs7l.XE9IamWO3XkFLuyG.YWHUz4y8oG4Pl/U07kzgKhFWlaBK'),
    (47, N'$2a$12$s8gfPVTxRUOSjypg8MP5r.Hgl9dl01NulYeEL85nLpBC8iBRPJXbi'),
    (48, N'$2a$12$jUN7bhvatgGmqyuh.SVFj.K2Vls8rPq4xMXw6wWi7u8nPJ7lH6VDm'),
    (49, N'$2a$12$GQI/awengpp0YTp7PETm0O3UKKFcmjmJH/cQTtRqO2VhNxOJzsX/u'),
    (50, N'$2a$12$.juxwIec7u6w/cJT0D2tXOrUJgTJHV7x1R4H3zQDLmKTDl0L5UgFy'),
    (51, N'$2a$12$6aHdGrdMf1eWa8TLBrbcW.TrL84QEKf0hrs2kvsqC5hgbYs.KYafG'),
    (52, N'$2a$12$AlaucJAgJ24p2gzSqvVl3.ZPBL73GhCc6ZfZMWEY0o5TxE1m5oFoa'),
    (53, N'$2a$12$pM/2a0yxQCLgxflUJzDXueVH7VX9E4Z7QapIfqSOi8WpvJ0du0mOy'),
    (54, N'$2a$12$egeV8QkbBdOlE7.N.Neh5OhKzL73JPNtp8hyOH5K0HTX1yTfwBvei')
) AS v(uid, hash)
ON v.uid = u.user_id
WHERE u.role = N'INSTRUCTOR';

COMMIT TRANSACTION;
PRINT N'>>> SEED 10 (more admins + instructor password fix) completed.';
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0 ROLLBACK TRANSACTION;
    PRINT N'!!! SEED 10 FAILED, rolled back: ' + ERROR_MESSAGE();
    THROW;
END CATCH
GO
