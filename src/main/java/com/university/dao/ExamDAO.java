package com.university.dao;

import com.university.model.Exam;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code dbo.exams}.
 */
public class ExamDAO extends AbstractDAO {

    private static final String SELECT =
            "SELECT exam_id, section_id, exam_date, start_time, duration_minutes, room, "
            + "created_by, created_at FROM dbo.exams";

    private static final String INSERT =
            "INSERT INTO dbo.exams (section_id, exam_date, start_time, duration_minutes, room, created_by) "
            + "VALUES (?, ?, ?, ?, ?, ?)";

    private static final RowMapper<Exam> MAPPER = ExamDAO::mapRow;

    static Exam mapRow(ResultSet rs) throws SQLException {
        Exam exam = new Exam();
        exam.setExamId(rs.getInt("exam_id"));
        exam.setSectionId(rs.getInt("section_id"));
        exam.setExamDate(DaoUtils.getLocalDate(rs, "exam_date"));
        exam.setStartTime(DaoUtils.getLocalTime(rs, "start_time"));
        exam.setDurationMinutes(rs.getInt("duration_minutes"));
        exam.setRoom(rs.getString("room"));
        exam.setCreatedBy(DaoUtils.getInteger(rs, "created_by"));
        exam.setCreatedAt(DaoUtils.getLocalDateTime(rs, "created_at"));
        return exam;
    }

    public Optional<Exam> findById(int examId) {
        return queryOne(SELECT + " WHERE exam_id = ?", MAPPER, examId);
    }

    /** Every exam scheduled for one section. */
    public List<Exam> findBySection(int sectionId) {
        return queryList(SELECT + " WHERE section_id = ? ORDER BY exam_date, start_time",
                MAPPER, sectionId);
    }

    /**
     * Every exam belonging to a section this student is (or was) enrolled in — a dropped
     * enrollment does not carry its section's exams along with it.
     */
    public List<Exam> findByStudent(int studentId) {
        String sql = "SELECT e.exam_id, e.section_id, e.exam_date, e.start_time, "
                + "e.duration_minutes, e.room, e.created_by, e.created_at "
                + "FROM dbo.exams e "
                + "INNER JOIN dbo.enrollments en ON en.section_id = e.section_id "
                + "WHERE en.student_id = ? AND en.status <> 'DROPPED' "
                + "ORDER BY e.exam_date, e.start_time";
        return queryList(sql, MAPPER, studentId);
    }

    public int insert(Exam exam) {
        return insertAndReturnKey(INSERT, exam.getSectionId(), exam.getExamDate(),
                exam.getStartTime(), exam.getDurationMinutes(), exam.getRoom(), exam.getCreatedBy());
    }
}
