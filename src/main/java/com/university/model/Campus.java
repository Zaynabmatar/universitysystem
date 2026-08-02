package com.university.model;

/**
 * One row of {@code dbo.campuses}, used to display where a class meets.
 */
public class Campus {

    private int campusId;
    private String campusName;
    private String location;
    private boolean active = true;

    public Campus() {
    }

    public Campus(int campusId, String campusName, String location, boolean active) {
        this.campusId = campusId;
        this.campusName = campusName;
        this.location = location;
        this.active = active;
    }

    public int getCampusId() {
        return campusId;
    }

    public void setCampusId(int campusId) {
        this.campusId = campusId;
    }

    public String getCampusName() {
        return campusName;
    }

    public void setCampusName(String campusName) {
        this.campusName = campusName;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    @Override
    public String toString() {
        return campusName;
    }
}
