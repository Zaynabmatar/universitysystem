package com.university.controller;

import com.university.controller.dialog.InstructorFormDialog;
import com.university.enums.UserRole;
import com.university.model.Department;
import com.university.model.Instructor;
import com.university.service.CourseService;
import com.university.service.InstructorService;
import com.university.service.ServiceException;
import com.university.service.Session;
import com.university.util.AlertUtil;
import com.university.util.SceneManager;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;

import java.util.List;

/**
 * Read-only instructor directory reachable from the account dropdown by every
 * role. Reads through {@link InstructorService#listActive()} — the exact same
 * DAO/table {@code AdminInstructorsController} manages — so a newly added or
 * edited instructor appears here automatically, with no second data source.
 * Admin additionally gets an Edit action that reuses the existing
 * {@link InstructorFormDialog}, rather than a second editing system.
 */
public class InstructorDirectoryController implements ReturnNavigable {

    @FXML private TableView<Instructor> instructorTable;
    @FXML private TableColumn<Instructor, Integer> colIndex;
    @FXML private TableColumn<Instructor, String> colName;
    @FXML private TableColumn<Instructor, String> colRank;
    @FXML private TableColumn<Instructor, String> colEmail;
    @FXML private Button editButton;

    private final InstructorService instructorService = new InstructorService();
    private final CourseService courseService = new CourseService();

    private final ObservableList<Instructor> rows = FXCollections.observableArrayList();
    private List<Department> departments = List.of();

    private String returnFxml;
    private String returnTitle;

    @Override
    public void setReturnTarget(String fxml, String title) {
        this.returnFxml = fxml;
        this.returnTitle = title;
    }

    @FXML
    private void initialize() {
        colIndex.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Integer item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : String.valueOf(getIndex() + 1));
            }
        });
        colName.setCellValueFactory(new PropertyValueFactory<>("fullName"));
        colRank.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getAcademicRank() == null ? "—" : c.getValue().getAcademicRank().toString()));
        colEmail.setCellValueFactory(new PropertyValueFactory<>("email"));

        instructorTable.setItems(rows);
        instructorTable.setPlaceholder(new Label("No instructors found."));

        boolean isAdmin = Session.current().getRole() == UserRole.ADMIN;
        editButton.setVisible(isAdmin);
        editButton.setManaged(isAdmin);
        if (isAdmin) {
            departments = courseService.listDepartments(false);
            editButton.disableProperty().bind(
                    instructorTable.getSelectionModel().selectedItemProperty().isNull());
        }

        reload();
    }

    @FXML
    private void handleBack() {
        SceneManager.getInstance().navigateTo(returnFxml, returnTitle);
    }

    @FXML
    private void handleRefresh() {
        reload();
    }

    private void reload() {
        try {
            rows.setAll(instructorService.listActive());
        } catch (Exception e) {
            AlertUtil.error("Could not load instructors", "The instructor list could not be loaded.", e);
        }
    }

    @FXML
    private void handleEdit() {
        Instructor selected = instructorTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        Instructor edited = new InstructorFormDialog(selected, departments).showAndWait().orElse(null);
        if (edited == null) return;

        try {
            instructorService.update(edited);
            AlertUtil.success("Saved", "The instructor's details were updated.");
            reload();
        } catch (ServiceException se) {
            AlertUtil.warn("Could not save", se.getMessage());
        } catch (Exception e) {
            AlertUtil.error("Could not save", "The instructor could not be updated.", e);
        }
    }
}
