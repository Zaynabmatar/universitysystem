package com.university.util;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Modality;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;

/**
 * The ONLY way this application talks to the user about success, failure and confirmation.
 * project_details.md Section 13: "every error becomes a friendly popup, never a stack trace on screen".
 */
public final class AlertUtil {

    private AlertUtil() { }

    public static void info(String title, String message) {
        show(Alert.AlertType.INFORMATION, title, message);
    }

    /** Same visual as info() — used for "saved", "registered", "dropped" messages. */
    public static void success(String title, String message) {
        show(Alert.AlertType.INFORMATION, title, message);
    }

    public static void warn(String title, String message) {
        show(Alert.AlertType.WARNING, title, message);
    }

    public static void error(String title, String message) {
        show(Alert.AlertType.ERROR, title, message);
    }

    /**
     * Friendly error popup with the technical detail hidden behind "Show details".
     * The user sees a sentence; the stack trace is one click away for debugging.
     */
    public static void error(String title, String message, Throwable cause) {
        Alert alert = base(Alert.AlertType.ERROR, title, message);
        if (cause != null) {
            StringWriter sw = new StringWriter();
            cause.printStackTrace(new PrintWriter(sw));

            TextArea area = new TextArea(sw.toString());
            area.setEditable(false);
            area.setWrapText(false);
            area.setMaxWidth(Double.MAX_VALUE);
            area.setMaxHeight(Double.MAX_VALUE);
            GridPane.setVgrow(area, Priority.ALWAYS);
            GridPane.setHgrow(area, Priority.ALWAYS);

            GridPane content = new GridPane();
            content.setMaxWidth(Double.MAX_VALUE);
            content.add(new Label("Technical details:"), 0, 0);
            content.add(area, 0, 1);

            alert.getDialogPane().setExpandableContent(content);
        }
        alert.showAndWait();
    }

    /**
     * "Are you sure?" — project_details.md Section 13: every destructive action asks first.
     *
     * @return true only when the user clicked OK.
     */
    public static boolean confirm(String title, String message) {
        Alert alert = base(Alert.AlertType.CONFIRMATION, title, message);
        alert.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    private static void show(Alert.AlertType type, String title, String message) {
        base(type, title, message).showAndWait();
    }

    private static Alert base(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        alert.initModality(Modality.APPLICATION_MODAL);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.getDialogPane().setMinWidth(460);
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        // Keep the popup consistent with the rest of the app.
        var css = AlertUtil.class.getResource("/css/app.css");
        if (css != null) {
            alert.getDialogPane().getStylesheets().add(css.toExternalForm());
        }
        return alert;
    }
}
