package com.university.service;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Phase 4 fallback for the University AI Assistant: whatever {@link AIAssistantService}
 * cannot answer from the database (a general question — "explain Java inheritance", "help me
 * write an email") is handed to Google's Gemini API instead.
 *
 * <p>This class never touches the database and is never given anything from it — only the
 * asker's own chat text goes into the request. {@link AIAssistantService} is what decides
 * whether a question is safe/appropriate to forward here in the first place (it already refuses
 * password/hash questions and anything outside the asker's own authorization before falling
 * through to this class).</p>
 *
 * <p>The API key is read from the {@code GEMINI_API_KEY} environment variable and is never
 * hardcoded, logged, or sent anywhere except as the query-string credential Google's API
 * requires. Any failure — missing key, network error, timeout, rate limit, a non-200 response,
 * or an empty/blocked completion — is swallowed and turned into {@link #UNAVAILABLE_MESSAGE} so
 * a Gemini outage never crashes the JavaFX UI or blocks university-data questions, which never
 * reach this class at all.</p>
 */
public class GeneralAIService {

    private static final Logger LOG = LoggerFactory.getLogger(GeneralAIService.class);

    static final String UNAVAILABLE_MESSAGE = "AI service is currently unavailable.";

    /**
     * Gemini occasionally answers a request with a transient {@code 429} (rate limit) or
     * {@code 503} (model overloaded), or the request simply times out under load. There is no
     * time to keep retrying the same overloaded model during a live presentation, so
     * {@link #respond} instead fails over once: {@link #PRIMARY_MODEL} is tried first, and only
     * on one of those three transient failures is {@link #FALLBACK_MODEL} tried with the same
     * question, for at most {@link #MODELS}{@code .length} (two) total attempts. Any other
     * failure (a bad key's {@code 401}/{@code 403}, a malformed request's {@code 400}, a
     * non-timeout network error, an empty completion) is not retried on the fallback model
     * either, since retrying would not help.
     *
     * <p>{@code gemini-2.5-flash-lite} and {@code gemini-2.5-flash} were retired for this API
     * key (HTTP 404, "no longer available to new users") even though Google's own ListModels
     * endpoint still lists them as supporting {@code generateContent} — that endpoint does not
     * reflect real per-key availability, so the models below were chosen by actually calling
     * {@code generateContent} on every candidate, not by reading the model list. Both are
     * Google's auto-updating {@code -latest}/version aliases rather than a single pinned
     * version, which is what let the previous pinned name silently go stale.
     */
    private static final String PRIMARY_MODEL = "gemini-flash-lite-latest";

    private static final String FALLBACK_MODEL = "gemini-3.5-flash";

    private static final String[] MODELS = {PRIMARY_MODEL, FALLBACK_MODEL};

    private static String endpointFor(String model) {
        return "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent";
    }

    private static final String SYSTEM_INSTRUCTION =
            "You are the general-knowledge half of a university system's AI assistant. Answer "
                    + "general questions (concepts, writing help, study help, coding help, and "
                    + "so on) concisely and helpfully. You have no access to this university's "
                    + "database, so never claim to know the asker's personal records, grades, "
                    + "schedule, or any other private university data — if asked, say you can't "
                    + "see that and suggest they ask the assistant directly.";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .build();

    /**
     * Answers a general question with Gemini, or {@link #UNAVAILABLE_MESSAGE} when the service
     * cannot be reached or the key is not configured.
     */
    public String respond(String message) {
        String apiKey = resolveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return UNAVAILABLE_MESSAGE;
        }
        try {
            for (int i = 0; i < MODELS.length; i++) {
                String model = MODELS[i];
                boolean isLastModel = i == MODELS.length - 1;
                HttpResponse<String> response;
                try {
                    response = send(message, apiKey, model);
                } catch (HttpTimeoutException e) {
                    if (isLastModel) {
                        LOG.warn("Gemini model {} timed out; no more models to try.", model);
                        return UNAVAILABLE_MESSAGE;
                    }
                    LOG.warn("Gemini model {} timed out - failing over to {}.", model, MODELS[i + 1]);
                    continue;
                }
                if (isRetryableStatus(response.statusCode())) {
                    if (isLastModel) {
                        LOG.warn("Gemini model {} returned HTTP {}; no more models to try. Body: {}",
                                model, response.statusCode(), truncate(response.body()));
                        return UNAVAILABLE_MESSAGE;
                    }
                    LOG.warn("Gemini model {} returned HTTP {} - failing over to {}. Body: {}",
                            model, response.statusCode(), MODELS[i + 1], truncate(response.body()));
                    continue;
                }
                if (response.statusCode() != 200) {
                    LOG.warn("Gemini model {} failed with HTTP {}. Body: {}",
                            model, response.statusCode(), truncate(response.body()));
                    return UNAVAILABLE_MESSAGE;
                }
                String text = extractText(response.body());
                return (text == null || text.isBlank()) ? UNAVAILABLE_MESSAGE : text.trim();
            }
            return UNAVAILABLE_MESSAGE;
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("Gemini request failed: {}: {}", e.getClass().getName(), e.getMessage());
            return UNAVAILABLE_MESSAGE;
        }
    }

    /** One HTTP attempt against the given Gemini model. Never logs the API key (it is in the URI). */
    private HttpResponse<String> send(String message, String apiKey, String model)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpointFor(model) + "?key=" + apiKey))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(message)))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** {@code 429} (rate limit) and {@code 503} (model overloaded) are the only statuses a bare retry can fix. */
    private static boolean isRetryableStatus(int statusCode) {
        return statusCode == 429 || statusCode == 503;
    }

    /** Keeps a large error body out of the log without losing the useful part of it. */
    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 500 ? body.substring(0, 500) + "..." : body;
    }

    /**
     * The {@code GEMINI_API_KEY} value, or null when it cannot be found anywhere.
     *
     * <p>{@link System#getenv(String)} only ever reflects the environment block the JVM was
     * launched with — on Windows, setting a User/System environment variable (Control Panel,
     * {@code setx}, PowerShell's {@code [Environment]::SetEnvironmentVariable}) writes it to the
     * registry immediately but does not update any process that is already running. That left the
     * JavaFX app (started, directly or via an IDE/terminal, before the key was set) permanently
     * unable to see it without an app or machine restart, even though the key existed and the
     * terminal test — a fresh process — picked it up fine. Falling back to the registry value that
     * {@code setx} actually wrote sidesteps that without requiring a restart.</p>
     */
    private static String resolveApiKey() {
        String fromEnv = System.getenv("GEMINI_API_KEY");
        fromEnv = fromEnv == null ? null : fromEnv.trim();
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return readWindowsRegistryEnvVar("GEMINI_API_KEY");
    }

    /**
     * Reads a persisted User, then System, environment variable straight from the Windows
     * registry via {@code reg query} — unlike {@link System#getenv(String)}, this reflects the
     * value currently on disk rather than the snapshot taken when this JVM started. Returns null
     * on any failure, including on non-Windows platforms where {@code reg} does not exist.
     */
    private static String readWindowsRegistryEnvVar(String name) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            return null;
        }
        String value = queryRegistry("HKCU\\Environment", name);
        if (value != null) {
            return value;
        }
        return queryRegistry("HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment", name);
    }

    private static String queryRegistry(String key, String name) {
        try {
            Process process = new ProcessBuilder("reg", "query", key, "/v", name)
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                output = sb.toString();
            }
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished || process.exitValue() != 0) {
                return null;
            }
            // "    GEMINI_API_KEY    REG_SZ    <value>" — the value is everything after the type.
            Matcher matcher = Pattern.compile(
                    Pattern.quote(name) + "\\s+REG_(?:EXPAND_)?SZ\\s+(.+)").matcher(output);
            if (matcher.find()) {
                String value = matcher.group(1).trim();
                return value.isBlank() ? null : value;
            }
            return null;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private static String buildRequestBody(String message) {
        JSONObject userPart = new JSONObject().put("text", message);
        JSONObject userContent = new JSONObject()
                .put("role", "user")
                .put("parts", new JSONArray().put(userPart));

        JSONObject systemContent = new JSONObject()
                .put("parts", new JSONArray().put(new JSONObject().put("text", SYSTEM_INSTRUCTION)));

        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(userContent))
                .put("systemInstruction", systemContent);
        return body.toString();
    }

    /** The concatenated text of the first candidate's parts, or null when none can be found. */
    private static String extractText(String responseBody) {
        try {
            JSONObject json = new JSONObject(responseBody);
            if (json.has("error")) {
                return null;
            }
            JSONArray candidates = json.optJSONArray("candidates");
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
            JSONArray parts = content == null ? null : content.optJSONArray("parts");
            if (parts == null || parts.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                sb.append(parts.getJSONObject(i).optString("text", ""));
            }
            return sb.toString();
        } catch (JSONException e) {
            return null;
        }
    }
}
