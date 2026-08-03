package com.university.controller;

import com.university.controller.dialog.PrerequisiteFormDialog;
import com.university.controller.dialog.RequirementFormDialog;
import com.university.enums.UserRole;
import com.university.model.Course;
import com.university.model.CoursePrerequisite;
import com.university.model.Program;
import com.university.model.ProgramRequirement;
import com.university.service.CourseService;
import com.university.service.ServiceException;
import com.university.service.Session;
import com.university.util.AlertUtil;
import com.university.util.GradeScale;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.util.StringConverter;

import java.util.List;
import java.util.Optional;

/**
 * The admin's "Prerequisites &amp; Degree Plans" screen: course prerequisites
 * (with cycle prevention, enforced by {@link CourseService#addPrerequisite})
 * and the degree plan (program requirements), on two tabs.
 */
public class AdminPrerequisitesController {

    // ---- Course Prerequisites tab ----
    @FXML private ComboBox<Course> prereqCourseFilter;
    @FXML private Label prereqCountLabel;
    @FXML private TableView<CoursePrerequisite> prereqTable;
    @FXML private TableColumn<CoursePrerequisite, String> colPCourse;
    @FXML private TableColumn<CoursePrerequisite, String> colPCourseTitle;
    @FXML private TableColumn<CoursePrerequisite, String> colPRequires;
    @FXML private TableColumn<CoursePrerequisite, String> colPReqTitle;
    @FXML private TableColumn<CoursePrerequisite, String> colPMinGrade;
    @FXML private Button editPrereqButton;
    @FXML private Button removePrereqButton;

    // ---- Degree Plan Requirements tab ----
    @FXML private ComboBox<Program> reqProgramFilter;
    @FXML private CheckBox mandatoryOnlyCheck;
    @FXML private Label reqSummaryLabel;
    @FXML private TableView<ProgramRequirement> reqTable;
    @FXML private TableColumn<ProgramRequirement, String> colRCourse;
    @FXML private TableColumn<ProgramRequirement, String> colRTitle;
    @FXML private TableColumn<ProgramRequirement, String> colRCredits;
    @FXML private TableColumn<ProgramRequirement, String> colRKind;
    @FXML private TableColumn<ProgramRequirement, String> colRSemester;
    @FXML private Button editReqButton;
    @FXML private Button removeReqButton;

    private final CourseService courseService = new CourseService();

    private final ObservableList<CoursePrerequisite> prereqRows = FXCollections.observableArrayList();
    private final ObservableList<ProgramRequirement> reqRows = FXCollections.observableArrayList();
    private List<Course> courses = List.of();
    private List<Program> programs = List.of();

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.ADMIN);
        courses = courseService.listCourses(false);
        programs = courseService.listPrograms(false);

        initPrereqTab();
        initRequirementsTab();
        reloadPrereqs();
        reloadRequirements();
    }

    // ------------------------------------------------------------------ shared lookups

    private Course courseById(int courseId) {
        return courses.stream().filter(c -> c.getCourseId() == courseId).findFirst().orElse(null);
    }

    private String courseCodeOf(int courseId) {
        Course c = courseById(courseId);
        return c == null ? "#" + courseId : c.getCourseCode();
    }

    private String courseTitleOf(int courseId) {
        Course c = courseById(courseId);
        return c == null ? "—" : c.getCourseTitle();
    }

    // ------------------------------------------------------------------ Course Prerequisites tab

    private void initPrereqTab() {
        colPCourse.setCellValueFactory(c -> new SimpleStringProperty(courseCodeOf(c.getValue().getCourseId())));
        colPCourseTitle.setCellValueFactory(c -> new SimpleStringProperty(courseTitleOf(c.getValue().getCourseId())));
        colPRequires.setCellValueFactory(c -> new SimpleStringProperty(courseCodeOf(c.getValue().getPrerequisiteCourseId())));
        colPReqTitle.setCellValueFactory(c -> new SimpleStringProperty(courseTitleOf(c.getValue().getPrerequisiteCourseId())));
        colPMinGrade.setCellValueFactory(c -> new SimpleStringProperty(GradeScale.display(c.getValue().getMinGradePoints())));

        prereqTable.setItems(prereqRows);
        prereqTable.setPlaceholder(new Label("No prerequisite links match your filter."));

        prereqCourseFilter.getItems().add(null);
        prereqCourseFilter.getItems().addAll(courses);
        prereqCourseFilter.setConverter(new StringConverter<>() {
            @Override public String toString(Course c) { return c == null ? "All courses" : c.toString(); }
            @Override public Course fromString(String s) { return null; }
        });
        prereqCourseFilter.getSelectionModel().selectFirst();
        prereqCourseFilter.valueProperty().addListener((o, a, b) -> reloadPrereqs());

        var selected = prereqTable.getSelectionModel().selectedItemProperty();
        editPrereqButton.disableProperty().bind(selected.isNull());
        removePrereqButton.disableProperty().bind(selected.isNull());
    }

    private void reloadPrereqs() {
        try {
            Course filter = prereqCourseFilter.getValue();
            List<CoursePrerequisite> result = courseService.listPrerequisites(
                    filter == null ? null : filter.getCourseId());
            prereqRows.setAll(result);
            prereqCountLabel.setText(result.size() + " prerequisite link" + (result.size() == 1 ? "" : "s"));
        } catch (Exception e) {
            AlertUtil.error("Could not load prerequisites", "The prerequisite list could not be loaded.", e);
        }
    }

    @FXML private void handleRefreshPrereq() { reloadPrereqs(); }

    @FXML
    private void handleClearPrereqFilter() {
        prereqCourseFilter.getSelectionModel().selectFirst();
        reloadPrereqs();
    }

    @FXML
    private void handleAddPrereq() {
        Optional<CoursePrerequisite> result = new PrerequisiteFormDialog(null, courses).showAndWait();
        if (result.isEmpty()) return;

        CoursePrerequisite link = result.get();
        try {
            courseService.addPrerequisite(link.getCourseId(), link.getPrerequisiteCourseId(), link.getMinGradePoints());
            AlertUtil.success("Prerequisite added",
                    courseCodeOf(link.getPrerequisiteCourseId()) + " must now be passed before "
                    + courseCodeOf(link.getCourseId()) + ".");
            reloadPrereqs();
        } catch (ServiceException se) {
            AlertUtil.warn("Cannot add prerequisite", se.getMessage());
        } catch (Exception e) {
            AlertUtil.error("Could not save", "The prerequisite could not be added.", e);
        }
    }

    @FXML
    private void handleEditPrereq() {
        CoursePrerequisite selected = prereqTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        Optional<CoursePrerequisite> result = new PrerequisiteFormDialog(selected, courses).showAndWait();
        if (result.isEmpty()) return;

        try {
            courseService.updatePrerequisiteMinGrade(selected.getPrerequisiteId(), result.get().getMinGradePoints());
            AlertUtil.success("Saved", "The minimum grade was updated.");
            reloadPrereqs();
        } catch (ServiceException se) {
            AlertUtil.warn("Could not save", se.getMessage());
        } catch (Exception e) {
            AlertUtil.error("Could not save", "The minimum grade could not be updated.", e);
        }
    }

    @FXML
    private void handleRemovePrereq() {
        CoursePrerequisite selected = prereqTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        boolean ok = AlertUtil.confirm("Remove prerequisite",
                "Remove the rule that " + courseCodeOf(selected.getPrerequisiteCourseId())
                + " must be passed before " + courseCodeOf(selected.getCourseId()) + "?\n\n"
                + "This only removes the rule. No course and no grade is deleted.");
        if (!ok) return;

        try {
            courseService.removePrerequisite(selected.getPrerequisiteId());
            AlertUtil.success("Removed", "The prerequisite rule was removed.");
            reloadPrereqs();
        } catch (Exception e) {
            AlertUtil.error("Could not remove", "The prerequisite could not be removed.", e);
        }
    }

    // ------------------------------------------------------------------ Degree Plan tab

    private void initRequirementsTab() {
        colRCourse.setCellValueFactory(c -> new SimpleStringProperty(courseCodeOf(c.getValue().getCourseId())));
        colRTitle.setCellValueFactory(c -> new SimpleStringProperty(courseTitleOf(c.getValue().getCourseId())));
        colRCredits.setCellValueFactory(c -> {
            Course course = courseById(c.getValue().getCourseId());
            return new SimpleStringProperty(course == null ? "—" : String.valueOf(course.getCredits()));
        });
        colRKind.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().isMandatory() ? "Mandatory" : "Elective"));
        colRSemester.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().getRecommendedSemester())));

        reqTable.setItems(reqRows);
        reqTable.setPlaceholder(new Label("This program has no degree-plan entries yet."));

        reqProgramFilter.getItems().setAll(programs);
        reqProgramFilter.setConverter(new StringConverter<>() {
            @Override public String toString(Program p) { return p == null ? "" : p.toString(); }
            @Override public Program fromString(String s) { return null; }
        });
        reqProgramFilter.getSelectionModel().selectFirst();
        reqProgramFilter.valueProperty().addListener((o, a, b) -> reloadRequirements());
        mandatoryOnlyCheck.selectedProperty().addListener((o, a, b) -> reloadRequirements());

        var selected = reqTable.getSelectionModel().selectedItemProperty();
        editReqButton.disableProperty().bind(selected.isNull());
        removeReqButton.disableProperty().bind(selected.isNull());
    }

    private void reloadRequirements() {
        Program program = reqProgramFilter.getValue();
        if (program == null) {
            reqRows.clear();
            reqSummaryLabel.setText("");
            return;
        }
        try {
            List<ProgramRequirement> result = courseService.listRequirements(
                    program.getProgramId(), mandatoryOnlyCheck.isSelected());
            reqRows.setAll(result);

            int mandatoryCredits = courseService.sumMandatoryCredits(program.getProgramId());
            reqSummaryLabel.setText(result.size() + " course(s) · " + mandatoryCredits
                    + " mandatory credits of " + program.getTotalCreditsRequired() + " required");
        } catch (Exception e) {
            AlertUtil.error("Could not load the degree plan", "The degree plan could not be loaded.", e);
        }
    }

    @FXML private void handleRefreshRequirements() { reloadRequirements(); }

    @FXML
    private void handleAddRequirement() {
        Program program = reqProgramFilter.getValue();
        if (program == null) return;

        Optional<ProgramRequirement> result = new RequirementFormDialog(null, program, courses).showAndWait();
        if (result.isEmpty()) return;

        ProgramRequirement requirement = result.get();
        try {
            courseService.addRequirement(requirement.getProgramId(), requirement.getCourseId(),
                    requirement.isMandatory(), requirement.getRecommendedSemester());
            AlertUtil.success("Added", courseCodeOf(requirement.getCourseId()) + " was added to the degree plan.");
            reloadRequirements();
        } catch (ServiceException se) {
            AlertUtil.warn("Cannot add", se.getMessage());
        } catch (Exception e) {
            AlertUtil.error("Could not save", "The course could not be added to the degree plan.", e);
        }
    }

    @FXML
    private void handleEditRequirement() {
        ProgramRequirement selected = reqTable.getSelectionModel().getSelectedItem();
        Program program = reqProgramFilter.getValue();
        if (selected == null || program == null) return;

        Optional<ProgramRequirement> result = new RequirementFormDialog(selected, program, courses).showAndWait();
        if (result.isEmpty()) return;

        try {
            courseService.updateRequirement(selected.getRequirementId(), result.get().isMandatory(),
                    result.get().getRecommendedSemester());
            AlertUtil.success("Saved", "The degree-plan entry was updated.");
            reloadRequirements();
        } catch (ServiceException se) {
            AlertUtil.warn("Could not save", se.getMessage());
        } catch (Exception e) {
            AlertUtil.error("Could not save", "The degree-plan entry could not be updated.", e);
        }
    }

    @FXML
    private void handleRemoveRequirement() {
        ProgramRequirement selected = reqTable.getSelectionModel().getSelectedItem();
        Program program = reqProgramFilter.getValue();
        if (selected == null || program == null) return;

        boolean ok = AlertUtil.confirm("Remove from degree plan",
                "Remove " + courseCodeOf(selected.getCourseId()) + " from the " + program.getProgramCode()
                + " degree plan?");
        if (!ok) return;

        try {
            courseService.removeRequirement(selected.getRequirementId());
            AlertUtil.success("Removed", "The course was removed from the degree plan.");
            reloadRequirements();
        } catch (Exception e) {
            AlertUtil.error("Could not remove", "The course could not be removed from the degree plan.", e);
        }
    }
}
