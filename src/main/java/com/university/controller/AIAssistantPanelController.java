package com.university.controller;

import com.university.service.AIAssistantService;
import com.university.service.VoiceInputService;
import com.university.service.VoiceInputService.Status;
import com.university.service.VoiceInputService.VoiceResult;
import com.university.util.Async;
import com.university.util.DocumentTextExtractor;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

/**
 * The University AI Assistant chat panel — embedded via {@code ai_assistant_panel.fxml} on the
 * Student, Instructor and Admin screens. Each host screen calls {@link #configure} once, right
 * after this panel loads, with a greeting and a set of quick questions appropriate to that role.
 * Everything else — bubbles, the typing indicator, scrolling, New Chat, Enter-to-send — is
 * identical across roles and lives only here.
 *
 * <p>Every reply comes from {@link AIAssistantService#respond}, which for now always returns the
 * same placeholder text regardless of role. A later phase can make that method role-aware
 * (student data, instructor data, admin data, general questions) without any of the wiring below
 * needing to change.</p>
 */
public class AIAssistantPanelController {

    @FXML private FlowPane   quickQuestionsPane;
    @FXML private ScrollPane chatScrollPane;
    @FXML private VBox       chatMessagesBox;
    @FXML private TextField  chatInputField;
    @FXML private Label      typingIndicatorLabel;
    @FXML private Button     sendButton;
    @FXML private Button     micButton;
    @FXML private Button     attachButton;
    @FXML private HBox       attachmentPreviewBox;
    @FXML private Label      attachmentNameLabel;

    private final AIAssistantService aiAssistantService = new AIAssistantService();
    private final VoiceInputService voiceInputService = new VoiceInputService();

    private String greeting = "Hi! I'm the University AI Assistant.";

    /** True while the microphone is actively being recorded/recognized; guards against overlap. */
    private boolean listening = false;
    private PauseTransition voiceStatusResetDelay;
    private ScaleTransition micPulseAnimation;

    /**
     * The Photo or PDF/Document picked via {@link #attachButton}, waiting to be sent with the
     * next message; null when nothing is attached. Set by {@link #handleAttachClick}'s file
     * choosers, cleared by {@link #handleRemoveAttachment} and by {@link #sendToAssistant} once
     * it has been handed off to {@link #fetchAssistantReply}, and reset on {@link #handleNewChat}.
     */
    private PendingAttachment pendingAttachment;

    private enum AttachmentKind { PHOTO, DOCUMENT }

    /** @param imageBytes null for a DOCUMENT attachment; @param documentText null for a PHOTO one. */
    private record PendingAttachment(AttachmentKind kind, String fileName,
                                      byte[] imageBytes, String mimeType, String documentText) {
        static PendingAttachment photo(String fileName, byte[] imageBytes, String mimeType) {
            return new PendingAttachment(AttachmentKind.PHOTO, fileName, imageBytes, mimeType, null);
        }

        static PendingAttachment document(String fileName, String documentText) {
            return new PendingAttachment(AttachmentKind.DOCUMENT, fileName, null, null, documentText);
        }
    }

    /**
     * True from the moment a message is sent until its reply (or the Gemini timeout) comes back.
     * Guards against a second Send/quick-question/Enter firing a second background lookup while
     * one is already in flight. Only ever read or written on the JavaFX Application Thread — the
     * background lookup thread never touches it directly, it hops back via {@link Platform#runLater}
     * first — so a plain boolean is enough.
     */
    private boolean waitingForReply = false;
    private Timeline typingDotsTimeline;

    /** Called once by the host controller, right after this panel is loaded. */
    public void configure(String greeting, List<String> quickQuestions) {
        this.greeting = greeting;

        quickQuestionsPane.getChildren().clear();
        for (String question : quickQuestions) {
            Button button = new Button(question);
            button.getStyleClass().add("quick-question-btn");
            button.setOnAction(this::handleQuickQuestion);
            quickQuestionsPane.getChildren().add(button);
        }

        startNewAssistantChat();
    }

    @FXML
    private void handleSendMessage() {
        String text = chatInputField.getText();
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty() && pendingAttachment == null) {
            return;
        }
        sendToAssistant(trimmed);
    }

    private void handleQuickQuestion(ActionEvent event) {
        if (event.getSource() instanceof Button quickQuestionButton) {
            sendToAssistant(quickQuestionButton.getText());
        }
    }

    @FXML
    private void handleNewChat() {
        startNewAssistantChat();
    }

    private void startNewAssistantChat() {
        chatMessagesBox.getChildren().clear();
        chatInputField.clear();
        showAssistantTyping(false);
        waitingForReply = false;
        setInputEnabled(true);
        clearAttachment();
        appendAssistantMessage(greeting);
    }

    /**
     * Ignores the request outright while a previous one is still in flight, so a fast double-click
     * on Send, a quick-question click during a pending lookup, or an Enter-key repeat can never
     * fire two overlapping background lookups (and never appends two assistant replies for one
     * question). {@link #sendButton} and {@link #chatInputField} are disabled for the same
     * duration as a visible cue, on top of this guard.
     *
     * <p>When {@link #pendingAttachment} is set, it travels with this one message only — it is
     * captured into a local variable and {@link #clearAttachment() cleared from the UI} here, so a
     * second message typed right after never accidentally re-sends the same Photo/PDF.</p>
     */
    private void sendToAssistant(String message) {
        if (waitingForReply) {
            return;
        }
        waitingForReply = true;
        setInputEnabled(false);

        PendingAttachment attachment = pendingAttachment;
        clearAttachment();

        if (message.isEmpty() && attachment != null) {
            appendUserAttachment(attachment);
        } else {
            appendUserMessage(message);
        }
        chatInputField.clear();
        showAssistantTyping(true);

        PauseTransition thinkingDelay = new PauseTransition(Duration.millis(500));
        thinkingDelay.setOnFinished(event -> fetchAssistantReply(message, attachment));
        thinkingDelay.play();
    }

    private void setInputEnabled(boolean enabled) {
        chatInputField.setDisable(!enabled);
        sendButton.setDisable(!enabled);
        sendButton.setText("Send");
        micButton.setDisable(!enabled);
        attachButton.setDisable(!enabled);
    }

    /**
     * "+" button — offers exactly the two attachment kinds the panel supports (a photo for Gemini
     * to look at, or a PDF/Word/text document for Gemini to read); each opens its own {@link
     * FileChooser} via {@link #chooseImageAttachment()}/{@link #chooseDocumentAttachment()}.
     */
    @FXML
    private void handleAttachClick() {
        ContextMenu menu = new ContextMenu();
        MenuItem photoItem = new MenuItem("Photo");
        photoItem.setOnAction(e -> chooseImageAttachment());
        MenuItem documentItem = new MenuItem("PDF or Documents");
        documentItem.setOnAction(e -> chooseDocumentAttachment());
        menu.getItems().addAll(photoItem, documentItem);
        menu.show(attachButton, Side.TOP, 0, 0);
    }

    private static final String[] IMAGE_EXTENSIONS = {"*.png", "*.jpg", "*.jpeg", "*.webp"};
    private static final String[] DOCUMENT_EXTENSIONS = {"*.pdf", "*.docx", "*.txt"};

    /**
     * Where the attachment file choosers should start. {@code user.home} can resolve to a
     * non-Windows-looking path depending on how the JVM was launched, so this reads the real
     * Windows profile folder from {@code USERPROFILE} first and defaults to its Downloads folder
     * — the native dialog's sidebar still gives one-click access to Desktop, Documents and
     * Pictures from there.
     */
    private static File defaultAttachmentDirectory() {
        String userProfile = System.getenv("USERPROFILE");
        File profileDir = (userProfile != null && !userProfile.isBlank())
                ? new File(userProfile)
                : new File(System.getProperty("user.home"));

        File downloads = new File(profileDir, "Downloads");
        if (downloads.isDirectory()) {
            return downloads;
        }
        if (profileDir.isDirectory()) {
            return profileDir;
        }
        return null;
    }

    private void chooseImageAttachment() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select a photo");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", IMAGE_EXTENSIONS));
        chooser.setInitialDirectory(defaultAttachmentDirectory());
        File file = chooser.showOpenDialog(attachButton.getScene().getWindow());
        if (file == null) {
            return;
        }
        String mimeType = imageMimeType(file.getName());
        if (mimeType == null) {
            appendAssistantMessage("That image type isn't supported — please use PNG, JPG or WEBP.");
            return;
        }
        setInputEnabled(false);
        Async.run(
                () -> readImageBytes(file),
                bytes -> {
                    setInputEnabled(true);
                    pendingAttachment = PendingAttachment.photo(file.getName(), bytes, mimeType);
                    showAttachmentChip("Photo: " + file.getName());
                },
                error -> {
                    setInputEnabled(true);
                    appendAssistantMessage("Couldn't read that image file. Please try again.");
                });
    }

    private void chooseDocumentAttachment() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select a PDF or document");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF or Documents", DOCUMENT_EXTENSIONS));
        chooser.setInitialDirectory(defaultAttachmentDirectory());
        File file = chooser.showOpenDialog(attachButton.getScene().getWindow());
        if (file == null) {
            return;
        }
        setInputEnabled(false);
        Async.run(
                () -> readDocumentText(file),
                text -> {
                    setInputEnabled(true);
                    if (text == null || text.isBlank()) {
                        appendAssistantMessage("I couldn't find any readable text in that file.");
                        return;
                    }
                    pendingAttachment = PendingAttachment.document(file.getName(), text);
                    showAttachmentChip("Document: " + file.getName());
                },
                error -> {
                    setInputEnabled(true);
                    appendAssistantMessage("Couldn't read that file. Please try again.");
                });
    }

    /** Runs on {@link Async}'s background thread; wraps the checked {@link IOException} in an
     *  unchecked one so it can flow through {@link java.util.function.Supplier}. */
    private static byte[] readImageBytes(File file) {
        try {
            return Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Runs on {@link Async}'s background thread; see {@link #readImageBytes}. */
    private static String readDocumentText(File file) {
        try {
            return DocumentTextExtractor.extractText(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Gemini accepts inline images as PNG, JPEG or WEBP; null for anything else (including the
     *  extensions the chooser did not offer, as a defensive backstop). */
    private static String imageMimeType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return null;
    }

    private void showAttachmentChip(String label) {
        attachmentNameLabel.setText(label);
        attachmentPreviewBox.setVisible(true);
        attachmentPreviewBox.setManaged(true);
    }

    @FXML
    private void handleRemoveAttachment() {
        clearAttachment();
    }

    private void clearAttachment() {
        pendingAttachment = null;
        attachmentNameLabel.setText("");
        attachmentPreviewBox.setVisible(false);
        attachmentPreviewBox.setManaged(false);
    }

    /**
     * 🎤 button — records one utterance and drops the recognized text into {@link #chatInputField},
     * exactly as if the user had typed it. Never sends the message itself; the user still has to
     * press {@link #sendButton}. Ignored while a reply is already in flight or a recording is
     * already underway, so a fast double-click can't start two overlapping recordings.
     */
    @FXML
    private void handleVoiceInput() {
        if (listening) {
            stopVoiceInput();
            return;
        }
        if (waitingForReply) {
            return;
        }
        if (voiceStatusResetDelay != null) {
            voiceStatusResetDelay.stop();
        }
        listening = true;
        chatInputField.setDisable(true);
        sendButton.setDisable(true);
        sendButton.setText("Send");
        micButton.getStyleClass().add("ai-mic-btn-listening");
        startMicPulse();

        Thread recognitionLookup = new Thread(() -> {
            VoiceResult result = voiceInputService.recognizeSpeech();
            Platform.runLater(() -> onVoiceResult(result));
        }, "voice-input-lookup");
        recognitionLookup.setDaemon(true);
        recognitionLookup.start();
    }

    /**
     * Second click on {@link #micButton} while {@link #listening}. Stops capture immediately
     * instead of waiting for Vosk's trailing-silence endpoint detection or the hard timeout:
     * {@link VoiceInputService#stopListening()} interrupts the blocked microphone read on the
     * background thread right away. UI reflects "not listening" synchronously here rather than
     * waiting for that background thread to finish recognizing whatever it already captured;
     * {@link #micButton} stays disabled in the meantime so a rapid third click can't race a new
     * recording against the one still finishing, and {@link #onVoiceResult} re-enables it.
     */
    private void stopVoiceInput() {
        listening = false;
        micButton.getStyleClass().remove("ai-mic-btn-listening");
        micButton.setDisable(true);
        stopMicPulse();
        voiceInputService.stopListening();
    }

    private void onVoiceResult(VoiceResult result) {
        listening = false;
        chatInputField.setDisable(false);
        sendButton.setDisable(false);
        sendButton.setText("Send");
        micButton.setDisable(false);
        micButton.getStyleClass().remove("ai-mic-btn-listening");
        stopMicPulse();

        if (result.status() == Status.SUCCESS) {
            chatInputField.setText(result.text());
            chatInputField.positionCaret(chatInputField.getText().length());
            chatInputField.requestFocus();
            return;
        }

        String originalPrompt = chatInputField.getPromptText();
        chatInputField.setPromptText(voiceErrorMessage(result.status()));
        voiceStatusResetDelay = new PauseTransition(Duration.seconds(3));
        voiceStatusResetDelay.setOnFinished(e -> chatInputField.setPromptText(originalPrompt));
        voiceStatusResetDelay.play();
    }

    /** Gentle grow/shrink loop on {@link #micButton} itself, on top of the red
     *  {@code ai-mic-btn-listening} style class, so the "recording" state is unmistakable. */
    private void startMicPulse() {
        if (micPulseAnimation != null) {
            micPulseAnimation.stop();
        }
        micPulseAnimation = new ScaleTransition(Duration.millis(500), micButton);
        micPulseAnimation.setFromX(1.0);
        micPulseAnimation.setFromY(1.0);
        micPulseAnimation.setToX(1.15);
        micPulseAnimation.setToY(1.15);
        micPulseAnimation.setCycleCount(Animation.INDEFINITE);
        micPulseAnimation.setAutoReverse(true);
        micPulseAnimation.play();
    }

    private void stopMicPulse() {
        if (micPulseAnimation != null) {
            micPulseAnimation.stop();
            micPulseAnimation = null;
        }
        micButton.setScaleX(1.0);
        micButton.setScaleY(1.0);
    }

    /** Only ever called for a non-{@link Status#SUCCESS} result; see {@link #onVoiceResult}. */
    private String voiceErrorMessage(Status status) {
        return switch (status) {
            case NO_MICROPHONE -> "Microphone not available.";
            case NO_SPEECH -> "No speech detected — try again.";
            case RECOGNITION_FAILED -> "Voice recognition failed — try again.";
            case SERVICE_UNAVAILABLE, SUCCESS -> "Voice input is unavailable right now.";
        };
    }

    /**
     * {@link AIAssistantService#respond} may now call out to Gemini for general questions, which
     * is a blocking network call — running it straight off the {@link PauseTransition} callback
     * would freeze the whole window for however long that request takes. So the lookup runs on a
     * background thread and only the UI update hops back onto the JavaFX Application Thread.
     *
     * <p>When {@code attachment} is non-null (see {@link #sendToAssistant}), the question is
     * routed to {@link AIAssistantService#respondToImage} or {@link
     * AIAssistantService#respondToDocument} instead of the normal database-backed {@link
     * AIAssistantService#respond} — an attachment is always a general Gemini question, never a
     * university-data lookup.</p>
     *
     * <p>Wrapped in a {@code try/catch(Throwable)} — unlike {@link AIAssistantService}'s own
     * methods, which only ever catch {@link RuntimeException} — so that absolutely nothing thrown
     * while resolving a reply (a PDF/document parsing failure, a classloading error, anything) can
     * skip the {@link Platform#runLater} hop below. Without this, such a failure would kill this
     * background thread before it re-enables the UI, leaving {@link #sendButton} permanently
     * disabled with {@link #waitingForReply} stuck {@code true} — the message/attachment would look
     * like it never sent and no reply would ever appear.</p>
     */
    private void fetchAssistantReply(String message, PendingAttachment attachment) {
        Thread lookup = new Thread(() -> {
            String reply;
            try {
                reply = attachment == null
                        ? aiAssistantService.respond(message)
                        : attachment.kind() == AttachmentKind.PHOTO
                                ? aiAssistantService.respondToImage(message, attachment.imageBytes(), attachment.mimeType())
                                : aiAssistantService.respondToDocument(message, attachment.documentText(), attachment.fileName());
            } catch (Throwable t) {
                reply = "Something went wrong sending that. Please try again.";
            }
            String finalReply = reply;
            Platform.runLater(() -> {
                showAssistantTyping(false);
                appendAssistantMessage(finalReply);
                waitingForReply = false;
                setInputEnabled(true);
            });
        }, "ai-assistant-lookup");
        lookup.setDaemon(true);
        lookup.start();
    }

    private void appendUserMessage(String text) {
        chatMessagesBox.getChildren().add(buildChatBubble(text, true));
        scrollChatToBottom();
    }

    /** Sent Photo/PDF/Document with no accompanying text — rendered as a compact file-attachment
     *  card (icon + file name) instead of a plain "Attached: ..." text bubble; see #sendToAssistant. */
    private void appendUserAttachment(PendingAttachment attachment) {
        chatMessagesBox.getChildren().add(buildAttachmentCard(attachment));
        scrollChatToBottom();
    }

    /** Material-style "document" glyph (page with folded corner + text lines) for PDF/Document
     *  attachments, and a plain "image" glyph (frame + mountain) for Photo attachments. */
    private static final String DOCUMENT_ICON_PATH =
            "M6 2c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6H6zm7 1.5L18.5 9H13V3.5zM8 12.5h8V14H8v-1.5zm0 3h8V17H8v-1.5zm0-6h4V11H8V9.5z";
    private static final String PHOTO_ICON_PATH =
            "M21 5H3c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 12H3V7h18v10zM8.5 12.5l2.5 3.01L14.5 11l4.5 6H5l3.5-4.5z";

    private HBox buildAttachmentCard(PendingAttachment attachment) {
        SVGPath icon = new SVGPath();
        icon.setContent(attachment.kind() == AttachmentKind.DOCUMENT ? DOCUMENT_ICON_PATH : PHOTO_ICON_PATH);
        icon.getStyleClass().add("chat-attachment-icon");
        icon.setScaleX(0.62);
        icon.setScaleY(0.62);

        Label nameLabel = new Label(attachment.fileName());
        nameLabel.getStyleClass().add("chat-attachment-name");
        nameLabel.setWrapText(true);

        HBox card = new HBox(6, icon, nameLabel);
        card.getStyleClass().add("chat-attachment-card");
        card.setAlignment(Pos.CENTER_LEFT);

        HBox row = new HBox(card);
        row.getStyleClass().add("chat-bubble-row-student");
        return row;
    }

    private void appendAssistantMessage(String text) {
        HBox row = buildChatBubble(text, false);
        row.setOpacity(0.0);
        chatMessagesBox.getChildren().add(row);

        FadeTransition fade = new FadeTransition(Duration.millis(250), row);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);
        fade.play();

        scrollChatToBottom();
    }

    private HBox buildChatBubble(String text, boolean fromUser) {
        Label bubble = new Label(text);
        bubble.setWrapText(true);
        bubble.getStyleClass().add("chat-bubble");
        bubble.getStyleClass().add(fromUser ? "chat-bubble-student" : "chat-bubble-assistant");

        HBox row = new HBox(bubble);
        row.getStyleClass().add(fromUser ? "chat-bubble-row-student" : "chat-bubble-row-assistant");
        return row;
    }

    private void showAssistantTyping(boolean visible) {
        if (visible) {
            typingIndicatorLabel.setText("●");
            typingIndicatorLabel.setVisible(true);
            typingIndicatorLabel.setManaged(true);

            if (typingDotsTimeline != null) {
                typingDotsTimeline.stop();
            }

            typingDotsTimeline = new Timeline(
                    new KeyFrame(Duration.ZERO, e -> typingIndicatorLabel.setText("●")),
                    new KeyFrame(Duration.millis(150), e -> typingIndicatorLabel.setText("● ●")),
                    new KeyFrame(Duration.millis(300), e -> typingIndicatorLabel.setText("● ● ●")),
                    new KeyFrame(Duration.millis(450), e -> typingIndicatorLabel.setText("●"))
            );
            typingDotsTimeline.setCycleCount(Timeline.INDEFINITE);
            typingDotsTimeline.play();
        } else {
            if (typingDotsTimeline != null) {
                typingDotsTimeline.stop();
            }
            typingIndicatorLabel.setVisible(false);
            typingIndicatorLabel.setManaged(false);
        }
    }

    /** Runs after the new bubble has been laid out, so the scrollbar's max reflects it. */
    private void scrollChatToBottom() {
        Platform.runLater(() -> chatScrollPane.setVvalue(1.0));
    }
}
