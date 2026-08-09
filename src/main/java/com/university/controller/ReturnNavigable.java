package com.university.controller;

/**
 * A content-area screen opened from the account dropdown (Change Password,
 * My Email, Change Address, List of Instructors) that needs to know where its
 * own back arrow should return to, since {@link com.university.util.SceneManager}
 * keeps no navigation history of its own.
 */
public interface ReturnNavigable {

    /** Called once, right after the screen loads, with where "back" should go. */
    void setReturnTarget(String fxml, String title);
}
