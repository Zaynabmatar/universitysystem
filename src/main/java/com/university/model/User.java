package com.university.model;

import com.university.enums.UserRole;

import java.time.LocalDateTime;

/**
 * One row of {@code dbo.users}, the single login record per person.
 *
 * <p>{@code passwordHash} holds a BCrypt hash. Plain text passwords are never
 * stored in this object or in the database.</p>
 */
public class User {

    private int userId;
    private String username;
    private String passwordHash;
    private UserRole role;
    private boolean active = true;
    private LocalDateTime lastLogin;
    private LocalDateTime createdAt;

    public User() {
    }

    public User(int userId, String username, String passwordHash, UserRole role,
                boolean active, LocalDateTime lastLogin, LocalDateTime createdAt) {
        this.userId = userId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.active = active;
        this.lastLogin = lastLogin;
        this.createdAt = createdAt;
    }

    public int getUserId() {
        return userId;
    }

    public void setUserId(int userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public UserRole getRole() {
        return role;
    }

    public void setRole(UserRole role) {
        this.role = role;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public LocalDateTime getLastLogin() {
        return lastLogin;
    }

    public void setLastLogin(LocalDateTime lastLogin) {
        this.lastLogin = lastLogin;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    /** The password hash is deliberately left out of this text. */
    @Override
    public String toString() {
        return username + " (" + role + ")";
    }
}
