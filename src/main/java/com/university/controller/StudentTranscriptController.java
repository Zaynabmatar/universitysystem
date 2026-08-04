package com.university.controller;

import com.university.enums.UserRole;
import com.university.service.Session;
import com.university.service.TranscriptService;
import com.university.service.TranscriptService.TermBlock;
import com.university.service.TranscriptService.Transcript;
import com.university.service.TranscriptService.TranscriptRow;
import com.university.util.AlertUtil;
import com.university.util.PdfExporter;

import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.List;
import java.util.function.Function;

/**
 * The student's own academic record: one card per semester, oldest first, each showing that
 * term's GPA and the cumulative GPA <em>as it stood at the end of that term</em>.
 *
 * <p>Read-only, in every sense — Phase 12 writes nothing. The only action is exporting the
 * same numbers to PDF through {@link PdfExporter}, which receives the already-built
 * {@link Transcript} so the paper and the screen cannot disagree.</p>
 */
public class StudentTranscriptController {

    @FXML private Label      subtitleLabel;
    @FXML private Label      cumulativeGpaLabel;
    @FXML private Label      creditsLabel;
    @FXML private Label      standingLabel;
    @FXML private Label      termCountLabel;
    @FXML private VBox       termContainer;
    @FXML private ScrollPane scrollPane;
    @FXML private Button     exportPdfButton;

    private final TranscriptService transcriptService = new TranscriptService();
    private Transcript transcript;

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.STUDENT);
        load();
    }

    @FXML
    private void handleRefresh() {
        load();
    }

    private void load() {
        try {
            transcript = transcriptService.getTranscript(Session.current().requireStudentId());
            render();
        } catch (RuntimeException e) {
            AlertUtil.error("Transcript", "Your transcript could not be loaded.", e);
        }
    }

    private void render() {
        subtitleLabel.setText(transcript.fullName() + "  •  " + transcript.studentNumber
                            + "  •  " + transcript.programName);
        cumulativeGpaLabel.setText(transcript.cumulativeGpaText());
        creditsLabel.setText(transcript.creditsEarned + " / " + transcript.creditsRequired);
        standingLabel.setText(transcript.academicStanding);
        termCountLabel.setText(String.valueOf(transcript.terms.size()));

        standingLabel.getStyleClass().removeAll("standing-good", "standing-bad");
        standingLabel.getStyleClass().add(isHealthy(transcript.academicStanding)
                ? "standing-good" : "standing-bad");

        termContainer.getChildren().clear();
        // Nothing to export is not an error — the button simply switches off (checklist: empty cases).
        exportPdfButton.setDisable(transcript.isEmpty());

        if (transcript.isEmpty()) {
            Label empty = new Label("You have no academic record yet. "
                                  + "Once your first semester is graded it will appear here.");
            empty.getStyleClass().add("empty-state");
            empty.setWrapText(true);
            termContainer.getChildren().add(empty);
            return;
        }

        for (TermBlock block : transcript.terms) {
            termContainer.getChildren().add(buildTermBlock(block));
        }
    }

    private boolean isHealthy(String standing) {
        return !"PROBATION".equals(standing) && !"SUSPENDED".equals(standing);
    }

    /** One titled card per semester: heading, table, then the term and cumulative footer. */
    private VBox buildTermBlock(TermBlock block) {
        Label heading = new Label(block.heading());
        heading.getStyleClass().add("term-heading");

        TableView<TranscriptRow> table = buildTable(block.rows);

        Label credits = new Label("Term credits  " + block.termCredits);
        credits.getStyleClass().add("term-total");
        Label termGpa = new Label("Term GPA  " + block.termGpaText());
        termGpa.getStyleClass().add("term-total");
        Label cumulativeGpa = new Label("Cumulative GPA  " + block.cumulativeGpaText());
        cumulativeGpa.getStyleClass().addAll("term-total", "term-total-cumulative");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(18, spacer, credits, termGpa, cumulativeGpa);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(6, 4, 0, 4));

        VBox card = new VBox(6, heading, table, footer);
        card.getStyleClass().add("term-card");
        card.setPadding(new Insets(12));
        return card;
    }

    private TableView<TranscriptRow> buildTable(List<TranscriptRow> rows) {
        TableView<TranscriptRow> table = new TableView<>(FXCollections.observableArrayList(rows));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        // A semester is small: size the table to its content so the page scrolls, not the table.
        table.setFixedCellSize(30);
        table.prefHeightProperty().bind(table.fixedCellSizeProperty()
                .multiply(Bindings.size(table.getItems()).add(1.35)));
        table.setPlaceholder(new Label("No courses in this semester."));

        table.getColumns().add(column("Code",   110, r -> r.courseCode));
        table.getColumns().add(column("Course", 300, r -> r.courseTitle));
        table.getColumns().add(column("Cr",      50, TranscriptRow::creditsText));
        table.getColumns().add(column("Mark",    90, TranscriptRow::markText));
        table.getColumns().add(column("Grade",   80, TranscriptRow::letterText));
        table.getColumns().add(column("Points",  80, TranscriptRow::pointsText));
        table.getColumns().add(column("Note",   160, TranscriptRow::displayNote));

        // Grey out the superseded attempt so the eye skips it (Section 5.5).
        table.setRowFactory(view -> new TableRow<>() {
            @Override
            protected void updateItem(TranscriptRow item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("row-repeated", "row-inprogress");
                if (!empty && item != null) {
                    if (item.isSupersededRepeat()) {
                        getStyleClass().add("row-repeated");
                    } else if (item.isInProgress()) {
                        getStyleClass().add("row-inprogress");
                    }
                }
            }
        });
        return table;
    }

    private TableColumn<TranscriptRow, String> column(String title, double width,
                                                      Function<TranscriptRow, String> getter) {
        TableColumn<TranscriptRow, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(data -> new SimpleStringProperty(getter.apply(data.getValue())));
        return column;
    }

    /* =================== PDF EXPORT =================== */

    @FXML
    private void handleExportPdf() {
        if (transcript == null || transcript.isEmpty()) {
            AlertUtil.warn("Export", "There is nothing to export yet.");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save transcript as PDF");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF file", "*.pdf"));
        chooser.setInitialFileName("transcript_" + transcript.studentNumber + ".pdf");

        File target = chooser.showSaveDialog(exportPdfButton.getScene().getWindow());
        if (target == null) {
            return;                       // Cancel does nothing at all — no error, no empty file
        }
        if (!target.getName().toLowerCase().endsWith(".pdf")) {
            target = new File(target.getParentFile(), target.getName() + ".pdf");
        }

        try {
            PdfExporter.exportTranscript(transcript, target);
            AlertUtil.success("Transcript exported", "Saved to:\n" + target.getAbsolutePath());
            openQuietly(target);
        } catch (Exception e) {
            // The usual real cause on Windows is that the file is still open in a viewer.
            AlertUtil.error("Export failed",
                    "The PDF could not be written.\n"
                  + "Close the file if it is already open in a PDF viewer, then try again.", e);
        }
    }

    /** Opening the file is a nicety — it must never break the success path. */
    private void openQuietly(File file) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(file);
            }
        } catch (Exception ignored) {
            // some JREs have no Desktop support; the file is saved either way
        }
    }
}
