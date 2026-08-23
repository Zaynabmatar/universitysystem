package com.university.controller;

import com.university.enums.NotificationType;
import com.university.model.Notification;
import com.university.service.NotificationService;
import com.university.service.Session;
import com.university.util.AlertUtil;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.time.format.DateTimeFormatter;
import java.util.List;

/** The bell's popup: every notification for the logged-in user, newest first. */
public class NotificationsController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd MMM yyyy  HH:mm");

    @FXML private ListView<Notification> notificationList;
    @FXML private Label unreadLabel;
    @FXML private Label emptyLabel;
    @FXML private Button markAllButton;
    @FXML private Button closeButton;

    private final NotificationService notificationService = new NotificationService();

    /** Lets the caller (the shell) refresh the bell's badge once this popup closes. */
    private Runnable onCloseCallback;

    public void setOnCloseCallback(Runnable onCloseCallback) {
        this.onCloseCallback = onCloseCallback;
    }

    @FXML
    private void initialize() {
        notificationList.setPlaceholder(new Label("You have no notifications yet."));
        notificationList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Notification n, boolean empty) {
                super.updateItem(n, empty);
                if (empty || n == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                Label title = new Label(n.getTitle());
                title.getStyleClass().add(n.isRead() ? "notif-title-read" : "notif-title-unread");

                Label body = new Label(n.getMessage());
                body.setWrapText(true);
                body.getStyleClass().add("notif-body");

                Label meta = new Label(n.getType() + "  ·  "
                        + (n.getCreatedAt() == null ? "" : FMT.format(n.getCreatedAt())));
                meta.getStyleClass().add("notif-meta");

                VBox box = new VBox(3, title, body, meta);
                box.getStyleClass().add(n.isRead() ? "notif-cell-read" : "notif-cell-unread");

                if (n.getType() == NotificationType.WARNING) {
                    box.getStyleClass().add("notif-high-absence");
                    title.getStyleClass().add("notif-high-absence-title");
                }
                setGraphic(box);
                setText(null);
            }
        });

        notificationList.setOnMouseClicked(e -> {
            Notification selected = notificationList.getSelectionModel().getSelectedItem();
            if (selected != null && !selected.isRead()) {
                try {
                    notificationService.markRead(selected.getNotificationId());
                    // Flip the flag on the item already in the list and repaint in place --
                    // NOT reload(): with many notifications, re-fetching the whole inbox and
                    // replacing ListView's items on every single read-click was the actual cause
                    // of "scrolling gets stuck" (each click threw away the ListView's scroll
                    // position along with the list it just rebuilt from a fresh DB round trip).
                    selected.setRead(true);
                    notificationList.refresh();
                    updateUnreadSummary();
                } catch (RuntimeException ex) {
                    AlertUtil.error("Notifications", "This notification could not be marked as read.");
                }
            }
        });

        reload();
    }

    private void reload() {
        try {
            int userId = Session.current().getUser().getUserId();
            List<Notification> all = notificationService.inbox(userId);
            notificationList.setItems(FXCollections.observableArrayList(all));
            updateUnreadSummary();
            emptyLabel.setText("");
        } catch (RuntimeException e) {
            AlertUtil.error("Notifications", "Your notifications could not be loaded.");
        }
    }

    /** Unread count/badge, derived from the list already in memory -- no DB round trip. */
    private void updateUnreadSummary() {
        long unread = notificationList.getItems().stream().filter(n -> !n.isRead()).count();
        unreadLabel.setText(unread + " unread");
        markAllButton.setDisable(unread == 0);
    }

    @FXML
    private void handleMarkAllRead() {
        try {
            notificationService.markAllRead(Session.current().getUser().getUserId());
            notificationList.getItems().forEach(n -> n.setRead(true));
            notificationList.refresh();
            updateUnreadSummary();
        } catch (RuntimeException e) {
            AlertUtil.error("Notifications", "Your notifications could not be marked as read.");
        }
    }
@FXML
    private void handleMaximize() {
        Stage stage = (Stage) closeButton.getScene().getWindow();
        stage.setMaximized(!stage.isMaximized());
    }

    @FXML
    private void handleClose() {
        if (onCloseCallback != null) {
            onCloseCallback.run();
        }
        ((Stage) closeButton.getScene().getWindow()).close();
    }
}








