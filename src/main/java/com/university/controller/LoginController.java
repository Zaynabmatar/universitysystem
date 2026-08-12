package com.university.controller;

import com.university.dao.InstructorDAO;
import com.university.dao.StudentDAO;
import com.university.dao.UserDAO;
import com.university.enums.UserRole;
import com.university.service.AuthService;
import com.university.service.ServiceException;
import com.university.service.Session;
import com.university.util.AlertUtil;
import com.university.util.SceneManager;
import com.university.util.ValidationUtil;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Text;
import javafx.util.Duration;

/**
 * The sign-in screen, opened by RoleSelectionController once the user has
 * picked Admin, Instructor or Student.
 *
 * <p>The chosen role arrives via {@link #setRole(UserRole)} right after this
 * screen is loaded: it is handed to the service, which refuses an account
 * that does not belong to that role.</p>
 *
 * <p>The number typed here is always {@code users.user_id}, for every role
 * (see {@link AuthService} for why) â€” the role picked on the previous screen
 * is a door the account must match, not a table the number is looked up in.</p>
 *
 * <p>The two "please enter yourâ€¦" checks happen here rather than in the service
 * so the wording is a prompt rather than a complaint. Everything else â€” the
 * password itself, whether the account is active â€” is the service's business,
 * and its message is shown exactly as it comes back.</p>
 */
public class LoginController {

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private TextField passwordTextField;
    @FXML private SVGPath eyeIcon;
    @FXML private Label errorLabel;
    @FXML private Label nameHintLabel;
    @FXML private Button loginButton;
    @FXML private Pane dotsTopRight;
    @FXML private Pane dotsBottomLeft;
    @FXML private Button backButton;
    @FXML private StackPane backGraphic;
    @FXML private Circle backHoverCircle;

    private UserRole selectedRole;

    /** Runs the back arrow's hover/press easing; kept so a new state can cut the old one short. */
    private Timeline backAnimation;

    /** Debounces the name lookup so it fires once typing pauses, not on every keystroke. */
    private PauseTransition nameLookupDelay;

    private final StudentDAO studentDAO = new StudentDAO();
    private final InstructorDAO instructorDAO = new InstructorDAO();
    private final UserDAO userDAO = new UserDAO();

    private static final String EYE_OPEN =
            "M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zm0 12.5c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z";
    private static final String EYE_CLOSED =
            "M12 6.5c2.76 0 5 2.24 5 5 0 .65-.13 1.26-.36 1.83l2.92 2.92c1.51-1.26 2.7-2.89 3.44-4.75-1.73-4.39-6-7.5-11-7.5-1.4 0-2.74.25-3.98.7l2.16 2.16c.57-.23 1.18-.36 1.82-.36zM2.71 3.16 1.29 4.58 3.7 7c-1.68 1.31-3 3.03-3.71 5 1.73 4.39 6 7.5 11 7.5 1.79 0 3.47-.41 4.97-1.14l2.54 2.54 1.41-1.41L2.71 3.16zM12 16.5c-2.76 0-5-2.24-5-5 0-.77.18-1.5.49-2.15l1.57 1.57c-.03.19-.06.38-.06.58 0 1.66 1.34 3 3 3 .2 0 .38-.03.57-.07l1.57 1.57c-.65.32-1.37.5-2.14.5zm2.97-5.33-3.64-3.64.02-.02c1.66 0 3 1.34 3 3 0 .02-.01.03-.01.05z";

    private final AuthService authService = new AuthService();

    @FXML
    private void initialize() {
        Platform.runLater(() -> usernameField.requestFocus());
        usernameField.textProperty().addListener((o, old, val) -> {
            if (!val.matches("\\d*")) {
                usernameField.setText(val.replaceAll("\\D", ""));
            }
        });
        usernameField.textProperty().addListener((o, a, b) -> hideError());
        passwordField.textProperty().addListener((o, a, b) -> hideError());

        nameLookupDelay = new PauseTransition(Duration.millis(250));
        nameLookupDelay.setOnFinished(e -> updateNameHint());
        usernameField.textProperty().addListener((o, a, b) -> {
            hideNameHint();
            nameLookupDelay.stop();
            if (!ValidationUtil.isBlank(b)) {
                nameLookupDelay.playFromStart();
            }
        });
        // Enter in the password field submits.
        passwordField.setOnAction(e -> handleLogin());
        passwordTextField.setOnAction(e -> handleLogin());

        // The visible/hidden fields share one value; only one is shown at a time.
        passwordTextField.textProperty().bindBidirectional(passwordField.textProperty());

        // Decorative dotted halftone corners â€” dots fade out from the near screen corner.
        buildHalftone(dotsTopRight, dotsTopRight.getPrefWidth(), 0);
        buildHalftone(dotsBottomLeft, 0, dotsBottomLeft.getPrefHeight());

        wireBackArrow();
    }

    /**
     * Hover and press feedback for the back arrow.
     *
     * <p>Done here rather than in CSS because {@code -fx-transition} only exists
     * from JavaFX 23 and this project builds against 21, so a CSS {@code :hover}
     * rule would snap instead of easing. Pressed is checked before hovered: the
     * pointer is by definition over the button while pressing it, and the smaller
     * pressed scale has to win.</p>
     */
    private void wireBackArrow() {
        backButton.hoverProperty().addListener((o, was, isHovered) -> applyBackState());
        backButton.pressedProperty().addListener((o, was, isPressed) -> applyBackState());
    }

    private void applyBackState() {
        if (backButton.isPressed()) {
            animateBackArrow(0.92, 1, 90);
        } else if (backButton.isHover()) {
            animateBackArrow(1.05, 1, 160);
        } else {
            animateBackArrow(1.0, 0, 160);
        }
    }

    private void animateBackArrow(double scale, double haloOpacity, double millis) {
        if (backAnimation != null) {
            backAnimation.stop();
        }
        backAnimation = new Timeline(new KeyFrame(Duration.millis(millis),
                new KeyValue(backGraphic.scaleXProperty(), scale, Interpolator.EASE_BOTH),
                new KeyValue(backGraphic.scaleYProperty(), scale, Interpolator.EASE_BOTH),
                new KeyValue(backHoverCircle.opacityProperty(), haloOpacity, Interpolator.EASE_BOTH)));
        backAnimation.play();
    }

    /**
     * Called by RoleSelectionController right after this screen is loaded.
     * Scopes sign-in to one role; the ID field always means {@code
     * users.user_id} regardless of which role was picked.
     */
    public void setRole(UserRole role) {
        this.selectedRole = role;
        usernameField.setPromptText("User ID");
    }

    @FXML
    private void handleBack() {
        SceneManager.getInstance().switchRoot("role_selection.fxml",
                "University Registration System â€” Select Role");
    }

    private void buildHalftone(Pane pane, double anchorX, double anchorY) {
        double width = pane.getPrefWidth();
        double height = pane.getPrefHeight();
        double spacing = 15;
        double maxDist = Math.hypot(width, height);

        for (double y = 6; y < height; y += spacing) {
            for (double x = 6; x < width; x += spacing) {
                double dist = Math.hypot(x - anchorX, y - anchorY);
                double opacity = 1 - dist / (maxDist * 0.9);
                if (opacity <= 0.03) {
                    continue;
                }
                Circle dot = new Circle(x, y, 1.4);
                dot.getStyleClass().add("halftone-dot");
                dot.setOpacity(opacity);
                pane.getChildren().add(dot);
            }
        }
    }

    @FXML
    private void togglePasswordVisibility() {
        boolean showingText = passwordTextField.isVisible();
        passwordTextField.setVisible(!showingText);
        passwordTextField.setManaged(!showingText);
        passwordField.setVisible(showingText);
        passwordField.setManaged(showingText);
        eyeIcon.setContent(showingText ? EYE_CLOSED : EYE_OPEN);

        TextField nowVisible = showingText ? passwordField : passwordTextField;
        nowVisible.requestFocus();
        nowVisible.positionCaret(nowVisible.getText().length());
    }

    @FXML
    private void handleLogin() {
        hideError();

        if (ValidationUtil.isBlank(usernameField.getText())) {
            showError("Please enter your ID.");
            usernameField.requestFocus();
            return;
        }
        if (ValidationUtil.isBlank(passwordField.getText())) {
            showError("Please enter your password.");
            passwordField.requestFocus();
            return;
        }

        loginButton.setDisable(true);
        try {
            String failureMessage = attemptLogin();
            if (failureMessage != null) {
                showError(failureMessage);
            }
        } finally {
            loginButton.setDisable(false);
        }
    }

    /**
     * Checks the ID and password against the role picked on the role-selection
     * screen only â€” never the other two tables â€” so an Admin ID and a Student
     * ID can validly be the same number without either one being able to sign
     * in through the other's button.
     *
     * <p>Signing in and opening the dashboard are two separate steps with two
     * separate failure messages. Rolling them into one {@code try} was what
     * made a broken screen look like a rejected password: whatever went wrong,
     * the user was told "something went wrong while signing in" even when the
     * sign-in itself had already succeeded.</p>
     *
     * @return null on success (the session is already open and the main
     *         shell already shown), otherwise the message to display
     */
    private String attemptLogin() {
        String id = usernameField.getText();
        String password = passwordField.getText();

        // ---- step 1: authenticate -------------------------------------------------
        try {
            long authStart = System.nanoTime();
            authService.login(selectedRole, id, password);
            System.out.printf("LOGIN TIMING auth: %.1f ms%n",
                    (System.nanoTime() - authStart) / 1_000_000.0);
            passwordField.clear();
        } catch (ServiceException se) {
            // Covers ValidationException too â€” it extends ServiceException.
            // A refusal, not a fault: the wording is already the user's answer.
            return se.getMessage();
        } catch (Exception ex) {
            report("AUTHENTICATION", ex);
            AlertUtil.error("Login failed",
                    "Signing in could not be completed. " + technicalSummary(ex), ex);
            return null;
        }

        // ---- step 2: open the dashboard -------------------------------------------
        // Past this line the account is verified and the session is open, so any
        // failure below belongs to the screen, not to the credentials.
        try {
            String username = Session.current().getUser().getUsername();
            long dashboardStart = System.nanoTime();
            SceneManager.getInstance().switchRoot("main_shell.fxml",
                    "University Registration System â€” " + username);
            System.out.printf("LOGIN TIMING dashboard: %.1f ms%n",
                    (System.nanoTime() - dashboardStart) / 1_000_000.0);
            return null;
        } catch (Exception ex) {
            report("DASHBOARD LOADING", ex);
            AlertUtil.error("Cannot open the dashboard",
                    "You were signed in, but the main screen could not be opened. "
                    + technicalSummary(ex), ex);
            return null;
        }
    }

    /**
     * Puts the whole failure on the console â€” message, stack trace and every
     * nested cause â€” so the real problem is readable in the run log instead of
     * only behind "Show details" in a popup that may already have been closed.
     */
    private void report(String stage, Throwable failure) {
        System.out.println("[LOGIN] FAILED during " + stage);
        failure.printStackTrace(System.out);
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            System.out.println("[LOGIN] caused by:");
            cause.printStackTrace(System.out);
        }
        System.out.flush();
    }

    /**
     * The innermost cause in one line â€” that is the sentence that actually names
     * the problem ("Invalid column name 'email'."), where the outer wrapper only
     * says which query it happened in.
     */
    private String technicalSummary(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank()
                ? root.getClass().getSimpleName()
                : root.getClass().getSimpleName() + ": " + message;
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private void hideError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }

    /**
     * Looks up the display name behind the ID currently typed, scoped to the
     * role picked on the previous screen â€” the same scoping {@link #attemptLogin()}
     * uses, so this hint can never confirm an ID under a role it does not
     * belong to. Only a name is shown; never the password hash, email or role.
     */
    private void updateNameHint() {
        String idText = usernameField.getText();
        if (ValidationUtil.isBlank(idText)) {
            hideNameHint();
            return;
        }
        int userId;
        try {
            userId = Integer.parseInt(idText);
        } catch (NumberFormatException e) {
            hideNameHint();
            return;
        }

        String name = lookupDisplayName(userId);
        showNameHint(name == null ? "User not found" : name);
    }

    /** @return the person's display name for the selected role, or null if no such account exists */
    private String lookupDisplayName(int userId) {
        if (selectedRole == null) {
            return null;
        }
        try {
            switch (selectedRole) {
                case STUDENT:
                    return studentDAO.findByUserId(userId).map(s -> s.getFullName()).orElse(null);
                case INSTRUCTOR:
                    return instructorDAO.findByUserId(userId).map(i -> i.getFullName()).orElse(null);
                case ADMIN:
                    return userDAO.findById(userId)
                            .filter(u -> u.getRole() == UserRole.ADMIN)
                            .map(u -> u.getUsername())
                            .orElse(null);
                default:
                    return null;
            }
        } catch (Exception e) {
            // The lookup is a convenience hint, not part of authentication â€” a
            // database hiccup here should not block or confuse the sign-in flow.
            return null;
        }
    }

    /** Horizontal gap, in pixels, between the typed ID and the separator that precedes the name hint. */
    private static final double NAME_HINT_GAP = 8;

    private void showNameHint(String text) {
        nameHintLabel.setText(text);
        nameHintLabel.setTranslateX(typedIdWidth() + NAME_HINT_GAP);
        nameHintLabel.setVisible(true);
        nameHintLabel.setManaged(true);
    }

    /**
     * Pixel width of the ID currently typed, rendered in the field's own font,
     * so the name hint can be nudged to sit right after it instead of being
     * pinned to the far edge of the field.
     */
    private double typedIdWidth() {
        Text measurer = new Text(usernameField.getText());
        measurer.setFont(usernameField.getFont());
        return measurer.getLayoutBounds().getWidth();
    }

    private void hideNameHint() {
        nameHintLabel.setVisible(false);
        nameHintLabel.setManaged(false);
    }
}
