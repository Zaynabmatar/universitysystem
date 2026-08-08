package com.university.controller;

import com.university.service.AIAssistantService;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.List;

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

    private final AIAssistantService aiAssistantService = new AIAssistantService();

    private String greeting = "Hi! I'm the University AI Assistant.";

    /**
     * True from the moment a message is sent until its reply (or the Gemini timeout) comes back.
     * Guards against a second Send/quick-question/Enter firing a second background lookup while
     * one is already in flight. Only ever read or written on the JavaFX Application Thread — the
     * background lookup thread never touches it directly, it hops back via {@link Platform#runLater}
     * first — so a plain boolean is enough.
     */
    private boolean waitingForReply = false;

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
        if (text == null || text.isBlank()) {
            return;
        }
        sendToAssistant(text.trim());
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
        appendAssistantMessage(greeting);
    }

    /**
     * Ignores the request outright while a previous one is still in flight, so a fast double-click
     * on Send, a quick-question click during a pending lookup, or an Enter-key repeat can never
     * fire two overlapping background lookups (and never appends two assistant replies for one
     * question). {@link #sendButton} and {@link #chatInputField} are disabled for the same
     * duration as a visible cue, on top of this guard.
     */
    private void sendToAssistant(String message) {
        if (waitingForReply) {
            return;
        }
        waitingForReply = true;
        setInputEnabled(false);

        appendUserMessage(message);
        chatInputField.clear();
        showAssistantTyping(true);

        PauseTransition thinkingDelay = new PauseTransition(Duration.millis(500));
        thinkingDelay.setOnFinished(event -> fetchAssistantReply(message));
        thinkingDelay.play();
    }

    private void setInputEnabled(boolean enabled) {
        chatInputField.setDisable(!enabled);
        sendButton.setDisable(!enabled);
    }

    /**
     * {@link AIAssistantService#respond} may now call out to Gemini for general questions, which
     * is a blocking network call — running it straight off the {@link PauseTransition} callback
     * would freeze the whole window for however long that request takes. So the lookup runs on a
     * background thread and only the UI update hops back onto the JavaFX Application Thread.
     */
    private void fetchAssistantReply(String message) {
        Thread lookup = new Thread(() -> {
            String reply = aiAssistantService.respond(message);
            Platform.runLater(() -> {
                showAssistantTyping(false);
                appendAssistantMessage(reply);
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

    private void appendAssistantMessage(String text) {
        chatMessagesBox.getChildren().add(buildChatBubble(text, false));
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
        typingIndicatorLabel.setVisible(visible);
        typingIndicatorLabel.setManaged(visible);
    }

    /** Runs after the new bubble has been laid out, so the scrollbar's max reflects it. */
    private void scrollChatToBottom() {
        Platform.runLater(() -> chatScrollPane.setVvalue(1.0));
    }
}
