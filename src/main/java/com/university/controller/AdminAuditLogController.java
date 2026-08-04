package com.university.controller;

import com.university.dao.AuditLogDAO;
import com.university.dao.UserDAO;
import com.university.enums.AuditActionType;
import com.university.enums.UserRole;
import com.university.model.AuditLog;
import com.university.model.User;
import com.university.service.ReportService;
import com.university.service.Session;
import com.university.util.AlertUtil;
import com.university.util.CsvExporter;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.util.StringConverter;

import java.io.File;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * project_details.md Section 7 — {@code admin_audit_log.fxml}.
 *
 * <p>This screen exists to answer one question in the presentation: "prove your triggers work."
 * Every row it shows was written by SQL Server, not by this application — {@code trg_Grade_Audit}
 * is the only trigger in this database that writes {@code audit_log} (there is no student-audit or
 * enrollment-count trigger here; {@code sections.enrolled_count} is maintained in {@code SectionDAO}
 * instead). The screen is read-only: there is no add, no edit and no delete, on purpose.</p>
 */
public class AdminAuditLogController {

    @FXML private ComboBox<String> tableFilter;
    @FXML private ComboBox<String> actionFilter;
    @FXML private ComboBox<User> userFilter;
    @FXML private DatePicker fromDate;
    @FXML private DatePicker toDate;
    @FXML private TextField searchField;
    @FXML private Label countLabel;
    @FXML private TextArea oldValueArea;
    @FXML private TextArea newValueArea;

    @FXML private TableView<AuditLog> logTable;
    @FXML private TableColumn<AuditLog, Number> colId;
    @FXML private TableColumn<AuditLog, Number> colRecord;
    @FXML private TableColumn<AuditLog, String> colWhen;
    @FXML private TableColumn<AuditLog, String> colUser;
    @FXML private TableColumn<AuditLog, String> colAction;
    @FXML private TableColumn<AuditLog, String> colTable;
    @FXML private TableColumn<AuditLog, String> colDesc;

    private static final String ALL = "(all)";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ReportService reportService = new ReportService();
    private final ObservableList<AuditLog> rows = FXCollections.observableArrayList();

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.ADMIN);
        wireTable();
        loadFilterChoices();
        handleSearch();
    }

    private void wireTable() {
        colId.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue().getLogId()));
        colWhen.setCellValueFactory(cd -> new SimpleStringProperty(
                cd.getValue().getCreatedAt() == null ? "" : WHEN.format(cd.getValue().getCreatedAt())));
        colUser.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().getUsername()));
        colAction.setCellValueFactory(cd -> new SimpleStringProperty(
                cd.getValue().getActionType() == null ? null : cd.getValue().getActionType().toDb()));
        colTable.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().getTableName()));
        colRecord.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue().getRecordId()));
        colDesc.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().getDescription()));

        // colour the action so INSERT / UPDATE / DELETE are readable at a glance from the back row
        colAction.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String v, boolean empty) {
                super.updateItem(v, empty);
                getStyleClass().removeAll("badge-ok", "badge-warn", "badge-danger");
                setText(empty ? null : v);
                if (empty || v == null) {
                    return;
                }
                switch (v) {
                    case "INSERT" -> getStyleClass().add("badge-ok");
                    case "UPDATE" -> getStyleClass().add("badge-warn");
                    case "DELETE" -> getStyleClass().add("badge-danger");
                    default -> { }
                }
            }
        });

        logTable.setItems(rows);
        logTable.setPlaceholder(new Label("No audit entries match these filters."));
        logTable.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> showDetail(now));
    }

    private void showDetail(AuditLog a) {
        oldValueArea.setText(a == null || a.getOldValue() == null ? "" : a.getOldValue());
        newValueArea.setText(a == null || a.getNewValue() == null ? "" : a.getNewValue());
    }

    private void loadFilterChoices() {
        try {
            tableFilter.getItems().setAll(ALL);
            tableFilter.getItems().addAll(reportService.auditTableNames());
            tableFilter.getSelectionModel().selectFirst();

            actionFilter.getItems().setAll(ALL, "INSERT", "UPDATE", "DELETE");
            actionFilter.getSelectionModel().selectFirst();

            userFilter.setConverter(new StringConverter<>() {
                @Override
                public String toString(User u) {
                    return u == null ? ALL : u.getUsername();
                }

                @Override
                public User fromString(String s) {
                    return null;
                }
            });
            userFilter.getItems().clear();
            userFilter.getItems().add(null);   // null = "(all)"
            userFilter.getItems().addAll(loadUsers());
            userFilter.getSelectionModel().selectFirst();
        } catch (RuntimeException e) {
            AlertUtil.error("Audit log",
                    "The filter lists could not be loaded. Check that SQL Server is running.");
        }
    }

    private List<User> loadUsers() {
        return new UserDAO().findAll();
    }

    @FXML
    private void handleSearch() {
        try {
            AuditLogDAO.Filter f = new AuditLogDAO.Filter();

            String table = tableFilter.getValue();
            f.tableName = (table == null || ALL.equals(table)) ? null : table;

            String action = actionFilter.getValue();
            f.actionType = (action == null || ALL.equals(action)) ? null : AuditActionType.fromDb(action);

            User user = userFilter.getValue();
            f.userId = (user == null) ? null : user.getUserId();

            f.dateFrom = fromDate.getValue();
            f.dateTo = toDate.getValue();
            f.searchText = searchField.getText();

            if (f.dateFrom != null && f.dateTo != null && f.dateTo.isBefore(f.dateFrom)) {
                AlertUtil.warn("Check the dates", "The \"To\" date is before the \"From\" date.");
                return;
            }

            rows.setAll(reportService.searchAuditLog(f));
            showDetail(null);
            countLabel.setText(rows.size() + " entr" + (rows.size() == 1 ? "y" : "ies")
                    + (rows.size() >= f.maxRows ? " (showing the " + f.maxRows + " most recent)" : ""));
        } catch (RuntimeException e) {
            AlertUtil.error("Audit log",
                    "The audit log could not be read. Check that SQL Server is running, then press Search again.");
        }
    }

    @FXML
    private void handleClear() {
        tableFilter.getSelectionModel().selectFirst();
        actionFilter.getSelectionModel().selectFirst();
        userFilter.getSelectionModel().selectFirst();
        fromDate.setValue(null);
        toDate.setValue(null);
        searchField.clear();
        handleSearch();
    }

    @FXML
    private void handleExport() {
        File file = CsvExporter.exportTable(logTable, logTable.getScene().getWindow(), "audit_log");
        if (file != null) {
            AlertUtil.success("Exported", "The audit log was saved to:\n" + file.getAbsolutePath());
        } else {
            AlertUtil.info("Export", "Nothing was saved. Either you cancelled, or that folder is read-only.");
        }
    }
}
