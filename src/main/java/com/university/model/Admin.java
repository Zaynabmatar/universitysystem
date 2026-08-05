package com.university.model;

/**
 * One row of {@code dbo.admins}.
 *
 * <p>An administrator has no profile fields of its own — {@code dbo.users}
 * already carries the username — so this exists purely to give admins their
 * own {@code admin_id} identity, one-to-one with {@code dbo.users} the same
 * way {@link Student} and {@link Instructor} do.</p>
 */
public class Admin {

    private int adminId;
    private int userId;
    private boolean active = true;

    public Admin() {
    }

    public Admin(int adminId, int userId, boolean active) {
        this.adminId = adminId;
        this.userId = userId;
        this.active = active;
    }

    public int getAdminId() {
        return adminId;
    }

    public void setAdminId(int adminId) {
        this.adminId = adminId;
    }

    public int getUserId() {
        return userId;
    }

    public void setUserId(int userId) {
        this.userId = userId;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
