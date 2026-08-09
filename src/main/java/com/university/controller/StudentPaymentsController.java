package com.university.controller;

import com.university.enums.Currency;
import com.university.enums.UserRole;
import com.university.model.Semester;
import com.university.model.Student;
import com.university.model.TuitionInstallment;
import com.university.service.AcademicService;
import com.university.service.SemesterService;
import com.university.service.Session;
import com.university.service.TuitionService;
import com.university.util.AlertUtil;
import com.university.util.PdfExporter;

import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleGroup;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The student's own semester bill: what each of their real registered
 * courses costs in USD and in LBP — two separate, independent obligations,
 * never a currency conversion of one another — split into stable
 * installments, plus the payment methods the university accepts.
 *
 * <p>Everything here is read-only and driven entirely by
 * {@link TuitionService#billFor}, called for whichever semester is selected
 * in {@code semesterComboBox} — populated from the student's own real
 * registration history ({@link AcademicService#semestersStudied}), never a
 * hardcoded list. A student can never see another student's bill: the
 * student key never comes from anywhere but
 * {@link Session#requireStudentId()}.</p>
 *
 * <p>The four payment method options are display-only, exactly as asked —
 * clicking one does not create a payment, call an external gateway, or
 * change anything on screen.</p>
 */
public class StudentPaymentsController {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DecimalFormat LBP_FORMAT = new DecimalFormat("#,##0");

    @FXML private ComboBox<Semester> semesterComboBox;

    @FXML private TableView<InstallmentRow> usdInstallmentsTable;
    @FXML private TableColumn<InstallmentRow, String> colUsdNo;
    @FXML private TableColumn<InstallmentRow, String> colUsdReference;
    @FXML private TableColumn<InstallmentRow, String> colUsdType;
    @FXML private TableColumn<InstallmentRow, String> colUsdCurrency;
    @FXML private TableColumn<InstallmentRow, String> colUsdAmount;
    @FXML private TableColumn<InstallmentRow, String> colUsdDueDate;
    @FXML private TableColumn<InstallmentRow, String> colUsdPaymentDate;
    @FXML private TableColumn<InstallmentRow, String> colUsdPenalty;
    @FXML private TableColumn<InstallmentRow, String> colUsdStatus;
    @FXML private TableColumn<InstallmentRow, InstallmentRow> colUsdVoucher;
    @FXML private Label totalUsdLabel;

    @FXML private TableView<InstallmentRow> lbpInstallmentsTable;
    @FXML private TableColumn<InstallmentRow, String> colLbpNo;
    @FXML private TableColumn<InstallmentRow, String> colLbpReference;
    @FXML private TableColumn<InstallmentRow, String> colLbpType;
    @FXML private TableColumn<InstallmentRow, String> colLbpCurrency;
    @FXML private TableColumn<InstallmentRow, String> colLbpAmount;
    @FXML private TableColumn<InstallmentRow, String> colLbpDueDate;
    @FXML private TableColumn<InstallmentRow, String> colLbpPaymentDate;
    @FXML private TableColumn<InstallmentRow, String> colLbpPenalty;
    @FXML private TableColumn<InstallmentRow, String> colLbpStatus;
    @FXML private TableColumn<InstallmentRow, InstallmentRow> colLbpVoucher;
    @FXML private Label totalLbpLabel;

    @FXML private RadioButton creditCardOption;
    @FXML private RadioButton wishOption;
    @FXML private RadioButton omtOption;
    @FXML private RadioButton cashOption;

    @FXML private TableView<ChargeRow> chargesTable;
    @FXML private TableColumn<ChargeRow, String> colChargeNo;
    @FXML private TableColumn<ChargeRow, String> colChargeCode;
    @FXML private TableColumn<ChargeRow, String> colChargeTitle;
    @FXML private TableColumn<ChargeRow, String> colChargeCredits;
    @FXML private TableColumn<ChargeRow, String> colChargeAmountLbp;
    @FXML private TableColumn<ChargeRow, String> colChargeTotalLbp;
    @FXML private TableColumn<ChargeRow, String> colChargeAmountUsd;
    @FXML private TableColumn<ChargeRow, String> colChargeTotalUsd;
    @FXML private Label totalCreditsFooterLabel;
    @FXML private Label totalLbpFooterLabel;
    @FXML private Label totalUsdFooterLabel;

    private final SemesterService semesterService = new SemesterService();
    private final TuitionService tuitionService = new TuitionService();
    private final AcademicService academicService = new AcademicService();

    private final ObservableList<InstallmentRow> usdRows = FXCollections.observableArrayList();
    private final ObservableList<InstallmentRow> lbpRows = FXCollections.observableArrayList();
    private final ObservableList<ChargeRow> chargeRows = FXCollections.observableArrayList();

    private int studentId;
    private String studentIdText;
    private String studentName;
    private String semesterName;

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.STUDENT);
        Student student = Session.current().getStudent();
        studentId = Session.current().requireStudentId();
        studentIdText = String.valueOf(studentId);
        studentName = student.getFullName();

        // Visual only: the four options share one group so exactly one can be highlighted,
        // but selecting one records nothing and calls nothing.
        ToggleGroup methodGroup = new ToggleGroup();
        creditCardOption.setToggleGroup(methodGroup);
        wishOption.setToggleGroup(methodGroup);
        omtOption.setToggleGroup(methodGroup);
        cashOption.setToggleGroup(methodGroup);

        setUpInstallmentColumns(colUsdNo, colUsdReference, colUsdType, colUsdCurrency, colUsdAmount,
                colUsdDueDate, colUsdPaymentDate, colUsdPenalty, colUsdStatus, colUsdVoucher);
        setUpInstallmentColumns(colLbpNo, colLbpReference, colLbpType, colLbpCurrency, colLbpAmount,
                colLbpDueDate, colLbpPaymentDate, colLbpPenalty, colLbpStatus, colLbpVoucher);

        usdInstallmentsTable.setItems(usdRows);
        usdInstallmentsTable.setPlaceholder(new Label("No USD installments due."));
        usdInstallmentsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        lbpInstallmentsTable.setItems(lbpRows);
        lbpInstallmentsTable.setPlaceholder(new Label("No LBP installments due."));
        lbpInstallmentsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        bindHeightToContent(usdInstallmentsTable, usdRows);
        bindHeightToContent(lbpInstallmentsTable, lbpRows);

        colChargeNo.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().no));
        colChargeCode.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().courseCode));
        colChargeTitle.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().courseTitle));
        colChargeCredits.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().credits));
        colChargeAmountLbp.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().amountLbp));
        colChargeTotalLbp.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().dueLbp));
        colChargeAmountUsd.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().amountUsd));
        colChargeTotalUsd.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().dueUsd));
        chargesTable.setItems(chargeRows);
        chargesTable.setPlaceholder(new Label("You are not registered in any course this semester."));
        chargesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        bindHeightToContent(chargesTable, chargeRows);

        setUpSemesterSelector();
        reload();
    }

    /**
     * Fills the dropdown with every semester the student actually has registration/payment
     * history in ({@link AcademicService#semestersStudied}, newest first) — never a hardcoded
     * list — and defaults the selection to the current semester when it is one of them,
     * otherwise the most recent one. Switching the selection reloads the whole bill below it.
     */
    private void setUpSemesterSelector() {
        semesterComboBox.setConverter(new StringConverter<>() {
            @Override public String toString(Semester semester) {
                return semester == null ? "" : semester.getSemesterName();
            }
            @Override public Semester fromString(String string) {
                return null;
            }
        });

        List<Semester> history = academicService.semestersStudied(studentId);
        semesterComboBox.setItems(FXCollections.observableArrayList(history));

        Semester current = semesterService.getCurrentSemester();
        Semester initial = history.stream()
                .filter(s -> current != null && s.getSemesterId() == current.getSemesterId())
                .findFirst()
                .orElse(history.isEmpty() ? null : history.get(0));
        semesterComboBox.getSelectionModel().select(initial);

        semesterComboBox.valueProperty().addListener((obs, oldValue, newValue) -> reload());
    }

    private void setUpInstallmentColumns(TableColumn<InstallmentRow, String> no,
            TableColumn<InstallmentRow, String> reference, TableColumn<InstallmentRow, String> type,
            TableColumn<InstallmentRow, String> currency, TableColumn<InstallmentRow, String> amount,
            TableColumn<InstallmentRow, String> dueDate, TableColumn<InstallmentRow, String> paymentDate,
            TableColumn<InstallmentRow, String> penalty, TableColumn<InstallmentRow, String> status,
            TableColumn<InstallmentRow, InstallmentRow> voucher) {
        no.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().no));
        reference.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().bankReference));
        type.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().type));
        currency.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().currencyLabel));
        amount.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().amountText));
        dueDate.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().dueDateText));
        paymentDate.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().paymentDateText));
        penalty.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().penaltyText));
        status.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().statusLabel));
        status.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                getStyleClass().removeAll("badge-ok", "badge-warn", "badge-danger", "badge-neutral");
                if (empty || value == null) {
                    setText(null);
                    return;
                }
                setText(value);
                if ("Paid".equals(value)) {
                    getStyleClass().add("badge-ok");
                } else if ("Partially Paid".equals(value)) {
                    getStyleClass().add("badge-warn");
                } else if ("Overdue".equals(value)) {
                    getStyleClass().add("badge-danger");
                } else {
                    getStyleClass().add("badge-neutral");
                }
            }
        });

        voucher.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue()));
        voucher.setCellFactory(col -> new TableCell<>() {
            private final Button printButton = new Button("Print");
            {
                printButton.getStyleClass().add("voucher-button");
                printButton.setOnAction(e -> exportVoucher(getTableRow().getItem()));
            }
            @Override protected void updateItem(InstallmentRow value, boolean empty) {
                super.updateItem(value, empty);
                setGraphic(empty || value == null ? null : printButton);
            }
        });
    }

    private void reload() {
        Semester semester = semesterComboBox.getValue();
        if (semester == null) {
            usdRows.clear();
            lbpRows.clear();
            chargeRows.clear();
            totalUsdLabel.setText("Total USD Amount: " + moneyUsd(BigDecimal.ZERO));
            totalLbpLabel.setText("Total LBP Amount: " + moneyLbp(BigDecimal.ZERO));
            totalCreditsFooterLabel.setText("Total Credits: 0");
            totalLbpFooterLabel.setText("Total LBP: " + moneyLbp(BigDecimal.ZERO));
            totalUsdFooterLabel.setText("Total USD: " + moneyUsd(BigDecimal.ZERO));
            return;
        }
        semesterName = semester.getSemesterName();

        try {
            TuitionService.Bill bill = tuitionService.billFor(studentId, semester);

            usdRows.setAll(bill.usdInstallments.stream().map(this::toRow).toList());
            lbpRows.setAll(bill.lbpInstallments.stream().map(this::toRow).toList());
            totalUsdLabel.setText("Total USD Amount: " + moneyUsd(bill.remainingUsd));
            totalLbpLabel.setText("Total LBP Amount: " + moneyLbp(bill.remainingLbp));

            int index = 1;
            BigDecimal dueUsdSum = BigDecimal.ZERO;
            BigDecimal dueLbpSum = BigDecimal.ZERO;
            List<ChargeRow> built = new ArrayList<>();
            for (TuitionService.CourseCharge charge : bill.courseCharges) {
                built.add(new ChargeRow(String.valueOf(index++), charge.courseCode, charge.courseTitle,
                        String.valueOf(charge.credits), moneyLbpPlain(charge.amountLbp),
                        moneyLbpPlain(charge.dueLbp), moneyUsd(charge.amountUsd), moneyUsd(charge.dueUsd)));
                dueUsdSum = dueUsdSum.add(charge.dueUsd);
                dueLbpSum = dueLbpSum.add(charge.dueLbp);
            }
            chargeRows.setAll(built);

            totalCreditsFooterLabel.setText("Total Credits: " + bill.totalCredits());
            totalLbpFooterLabel.setText("Total LBP: " + moneyLbp(dueLbpSum));
            totalUsdFooterLabel.setText("Total USD: " + moneyUsd(dueUsdSum));
        } catch (RuntimeException e) {
            AlertUtil.error("Payments", "Your billing information could not be loaded.", e);
        }
    }

    private InstallmentRow toRow(TuitionInstallment installment) {
        String amountText = installment.getCurrency() == Currency.USD
                ? moneyUsd(installment.getAmount()) : moneyLbp(installment.getAmount());
        String penaltyText = installment.getCurrency() == Currency.USD
                ? moneyUsd(installment.getPenalty()) : moneyLbp(installment.getPenalty());
        return new InstallmentRow(
                String.valueOf(installment.getInstallmentNo()),
                installment.getBankReference(),
                "Payment",
                installment.getCurrency().getLabel(),
                amountText,
                DATE.format(installment.getDueDate()),
                installment.getPaymentDate() == null ? "-" : DATE.format(installment.getPaymentDate()),
                penaltyText,
                installment.getStatus().getLabel());
    }

    /* ------------------------------------------------------------------ voucher */

    private void exportVoucher(InstallmentRow row) {
        if (row == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save payment voucher as PDF");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF file", "*.pdf"));
        chooser.setInitialFileName("voucher_" + row.bankReference + ".pdf");

        File target = chooser.showSaveDialog(usdInstallmentsTable.getScene().getWindow());
        if (target == null) {
            return;
        }
        if (!target.getName().toLowerCase().endsWith(".pdf")) {
            target = new File(target.getParentFile(), target.getName() + ".pdf");
        }

        try {
            PdfExporter.exportInstallmentVoucher(studentIdText, studentName, semesterName,
                    row.bankReference, row.currencyLabel, row.amountText, row.dueDateText,
                    row.statusLabel, target);
            AlertUtil.success("Voucher exported", "Saved to:\n" + target.getAbsolutePath());
            openQuietly(target);
        } catch (Exception e) {
            AlertUtil.error("Export failed",
                    "The voucher could not be written.\n"
                    + "Close the file if it is already open in a PDF viewer, then try again.", e);
        }
    }

    private void openQuietly(File file) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(file);
            }
        } catch (Exception ignored) {
            // some JREs have no Desktop support; the file is saved either way
        }
    }

    /* ------------------------------------------------------------------ formatting */

    private String moneyUsd(BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        return value.setScale(2, RoundingMode.HALF_UP) + " $";
    }

    private String moneyLbp(BigDecimal amount) {
        return moneyLbpPlain(amount) + " LBP";
    }

    private String moneyLbpPlain(BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        return LBP_FORMAT.format(value.setScale(0, RoundingMode.HALF_UP));
    }

    /**
     * Sizes a table to exactly fit its rows instead of stretching to fill the page, so it never
     * gets its own vertical scrollbar and the page below it moves up or down as rows are added
     * or removed — the same behaviour as the Classes page's Registered Courses table.
     */
    private static void bindHeightToContent(TableView<?> table, ObservableList<?> items) {
        double rowHeight = 28;
        double headerHeight = 32;
        double emptyStateHeight = 70;
        table.setFixedCellSize(rowHeight);
        var height = Bindings.createDoubleBinding(
                () -> headerHeight + (items.isEmpty() ? emptyStateHeight : items.size() * rowHeight + 2),
                items);
        table.prefHeightProperty().bind(height);
        table.minHeightProperty().bind(height);
        table.maxHeightProperty().bind(height);
    }

    /* ------------------------------------------------------------------ row types */

    private static final class InstallmentRow {
        final String no;
        final String bankReference;
        final String type;
        final String currencyLabel;
        final String amountText;
        final String dueDateText;
        final String paymentDateText;
        final String penaltyText;
        final String statusLabel;

        InstallmentRow(String no, String bankReference, String type, String currencyLabel, String amountText,
                       String dueDateText, String paymentDateText, String penaltyText, String statusLabel) {
            this.no = no;
            this.bankReference = bankReference;
            this.type = type;
            this.currencyLabel = currencyLabel;
            this.amountText = amountText;
            this.dueDateText = dueDateText;
            this.paymentDateText = paymentDateText;
            this.penaltyText = penaltyText;
            this.statusLabel = statusLabel;
        }
    }

    private static final class ChargeRow {
        final String no;
        final String courseCode;
        final String courseTitle;
        final String credits;
        final String amountLbp;
        final String dueLbp;
        final String amountUsd;
        final String dueUsd;

        ChargeRow(String no, String courseCode, String courseTitle, String credits, String amountLbp,
                  String dueLbp, String amountUsd, String dueUsd) {
            this.no = no;
            this.courseCode = courseCode;
            this.courseTitle = courseTitle;
            this.credits = credits;
            this.amountLbp = amountLbp;
            this.dueLbp = dueLbp;
            this.amountUsd = amountUsd;
            this.dueUsd = dueUsd;
        }
    }
}
