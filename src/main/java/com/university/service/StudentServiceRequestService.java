package com.university.service;

import com.university.dao.StudentServiceRequestDAO;
import com.university.model.StudentServiceRequest;

import java.util.List;

/**
 * Business rules for Online Service / Student Services requests — recording
 * a request the signed-in student submits, and reading back "My Requests".
 */
public class StudentServiceRequestService {

    /**
     * The only status a newly submitted request is ever given. Staff move a
     * request to {@link #STATUS_READY_FOR_PICKUP} later, once it is actually
     * processed — it is never assigned at submission time.
     */
    public static final String STATUS_WILL_BE_READY_SOON = "Will be ready soon";

    /** Assigned only once staff finish processing a request, never on submit. */
    public static final String STATUS_READY_FOR_PICKUP = "Ready for pick up";

    private final StudentServiceRequestDAO requestDao = new StudentServiceRequestDAO();

    /** Records a submitted request for the signed-in student. */
    public void submit(int studentId, String serviceName, String details) {
        requestDao.insert(studentId, serviceName, STATUS_WILL_BE_READY_SOON, details);
    }

    /** Every request the signed-in student has submitted, most recent first. */
    public List<StudentServiceRequest> myRequests(int studentId) {
        return requestDao.findByStudent(studentId);
    }

    /** How many requests the signed-in student has submitted. */
    public int myRequestCount(int studentId) {
        return requestDao.countByStudent(studentId);
    }
}
