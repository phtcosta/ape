package com.android.commands.monkey.ape.llm;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for the OpenAI-compatible SGLang inference server.
 *
 * Uses java.net.HttpURLConnection — no external HTTP library needed.
 * Supports both text-only and multimodal (image + text) messages.
 * Throws LlmException on HTTP errors, timeouts, or response parse failures.
 */
public class SglangClient {

    private final String baseUrl;
    private final String model;
    private final double temperature;
    private final double topP;
    private final int topK;
    private final int maxTokens;
    private final int timeoutMs;
    private JSONArray tools;

    public SglangClient(String baseUrl, String model, double temperature,
                        double topP, int topK, int maxTokens, int timeoutMs) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.temperature = temperature;
        this.topP = topP;
        this.topK = topK;
        this.maxTokens = maxTokens;
        this.timeoutMs = timeoutMs;
    }

    /**
     * Set the OpenAI tools schema. Required for Qwen3-VL to generate
     * structured tool calls when processing multimodal input.
     */
    public void setTools(JSONArray tools) {
        this.tools = tools;
    }

    /**
     * Send a chat completion request and return the model response.
     * Per INV-LLM-01: never throws unchecked exceptions to the caller.
     *
     * @param messages conversation messages (system, user, assistant roles)
     * @return parsed response, or null if the request fails
     */
    public ChatResponse chat(List<Message> messages) {
        try {
            String requestBody = buildRequestBody(messages);
            String responseBody = sendRequest(requestBody);
            return parseResponse(responseBody);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Build the JSON request body for the chat completions endpoint.
     */
    String buildRequestBody(List<Message> messages) {
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("temperature", temperature);
            body.put("top_p", topP);
            body.put("top_k", topK);
            body.put("max_tokens", maxTokens);

            JSONArray messagesArray = new JSONArray();
            for (Message msg : messages) {
                JSONObject msgObj = new JSONObject();
                msgObj.put("role", msg.getRole());

                if (msg.getContentParts() != null && !msg.getContentParts().isEmpty()) {
                    // Multimodal: content is a list of parts
                    JSONArray parts = new JSONArray();
                    for (ContentPart part : msg.getContentParts()) {
                        JSONObject partObj = new JSONObject();
                        partObj.put("type", part.getType());
                        if ("text".equals(part.getType())) {
                            partObj.put("text", part.getText());
                        } else if ("image_url".equals(part.getType())) {
                            JSONObject imageUrl = new JSONObject();
                            imageUrl.put("url", part.getImageUrl());
                            partObj.put("image_url", imageUrl);
                        }
                        parts.put(partObj);
                    }
                    msgObj.put("content", parts);
                } else {
                    // Plain text content
                    msgObj.put("content", msg.getTextContent() != null ? msg.getTextContent() : "");
                }
                messagesArray.put(msgObj);
            }
            body.put("messages", messagesArray);

            // OpenAI tools parameter — required for Qwen3-VL to generate
            // structured tool calls when processing multimodal input
            if (tools != null && tools.length() > 0) {
                body.put("tools", tools);
            }

            return body.toString();
        } catch (Exception e) {
            throw new LlmException("Failed to build request body: " + e.getMessage(), e);
        }
    }

    /**
     * Send HTTP POST to {baseUrl}/chat/completions and return the raw response body.
     * baseUrl already contains the /v1 prefix (e.g. "http://192.168.0.36:30000/v1").
     */
    private String sendRequest(String requestBody) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + "/chat/completions");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setDoOutput(true);

            byte[] bodyBytes = requestBody.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            int statusCode = conn.getResponseCode();
            if (statusCode != HttpURLConnection.HTTP_OK) {
                throw new LlmException("LLM server returned HTTP " + statusCode + " for " + baseUrl);
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();

        } catch (LlmException e) {
            throw e;
        } catch (IOException e) {
            throw new LlmException("LLM request failed: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Parse the OpenAI-format JSON response into a ChatResponse.
     *
     * Expected structure:
     * {"choices": [{"message": {"content": "...", "tool_calls": [...]}}],
     *  "usage": {"prompt_tokens": N, "completion_tokens": M}}
     */
    ChatResponse parseResponse(String responseBody) {
        try {
            JSONObject root = new JSONObject(responseBody);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                throw new LlmException("LLM response has no choices: " + responseBody);
            }

            JSONObject message = choices.getJSONObject(0).getJSONObject("message");
            String content = !message.isNull("content") && message.has("content")
                    ? message.getString("content")
                    : "";

            List<ToolCall> toolCalls = new ArrayList<>();
            if (message.has("tool_calls") && !message.isNull("tool_calls")) {
                JSONArray tcArray = message.getJSONArray("tool_calls");
                for (int i = 0; i < tcArray.length(); i++) {
                    JSONObject tcObj = tcArray.getJSONObject(i);
                    JSONObject functionObj = tcObj.has("function")
                            ? tcObj.getJSONObject("function")
                            : tcObj;
                    String name = functionObj.getString("name");
                    // arguments may be a JSON string or a JSON object
                    Map<String, Object> args = Collections.emptyMap();
                    if (functionObj.has("arguments")) {
                        Object argsRaw = functionObj.get("arguments");
                        if (argsRaw instanceof JSONObject) {
                            args = parseArgsObject((JSONObject) argsRaw);
                        } else if (argsRaw instanceof String) {
                            // Arguments encoded as a JSON string
                            String argsStr = (String) argsRaw;
                            try {
                                JSONObject argsObj = new JSONObject(argsStr);
                                args = parseArgsObject(argsObj);
                            } catch (Exception ignored) {
                                // Leave args empty on parse failure
                            }
                        }
                    }
                    toolCalls.add(new ToolCall(name, args));
                }
            }

            int promptTokens = 0;
            int completionTokens = 0;
            if (root.has("usage") && !root.isNull("usage")) {
                JSONObject usage = root.getJSONObject("usage");
                if (usage.has("prompt_tokens")) promptTokens = usage.getInt("prompt_tokens");
                if (usage.has("completion_tokens")) completionTokens = usage.getInt("completion_tokens");
            }

            return new ChatResponse(content, toolCalls, promptTokens, completionTokens);

        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("Failed to parse LLM response: " + e.getMessage(), e);
        }
    }

    /**
     * Convert a JSONObject of primitive values into a Map<String, Object>.
     *
     * Handles Qwen3-VL native tool-call format where coordinates may arrive as an array
     * in the "x" field (e.g. "x": [540, 399]) instead of separate "x"/"y" primitives.
     * This mirrors RVAgent's normalize_tool_args() array-expansion logic.
     */
    private Map<String, Object> parseArgsObject(JSONObject obj) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();

        // Expand "x": [x, y] array into separate "x" and "y" entries before normal parsing.
        // Qwen3-VL occasionally emits coordinates as a two-element array under "x".
        JSONArray xArr = obj.optJSONArray("x");
        if (xArr != null && xArr.length() >= 2) {
            result.put("x", xArr.optDouble(0));
            if (!obj.has("y")) {
                result.put("y", xArr.optDouble(1));
            }
            // Process remaining keys normally (skip "x" since we already handled it)
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if ("x".equals(key)) continue;
                putPrimitive(result, key, obj.opt(key));
            }
            return result;
        }

        java.util.Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            putPrimitive(result, key, obj.opt(key));
        }
        return result;
    }

    private void putPrimitive(Map<String, Object> result, String key, Object val) {
        if (val == null || val == JSONObject.NULL) {
            result.put(key, null);
        } else if (val instanceof Number) {
            // Use double for all numbers — callers cast as needed
            result.put(key, ((Number) val).doubleValue());
        } else if (val instanceof Boolean) {
            result.put(key, val);
        } else if (val instanceof String) {
            result.put(key, val);
        } else {
            result.put(key, val.toString());
        }
    }

    // --- Inner classes ---

    /**
     * A single message in the conversation.
     * Role is "system", "user", or "assistant".
     * Content is either plain text (textContent) or a list of parts (contentParts) for multimodal.
     */
    public static class Message {
        private final String role;
        private final String textContent;
        private final List<ContentPart> contentParts;

        /** Text-only message. */
        public Message(String role, String textContent) {
            this.role = role;
            this.textContent = textContent;
            this.contentParts = null;
        }

        /** Multimodal message with a list of content parts. */
        public Message(String role, List<ContentPart> contentParts) {
            this.role = role;
            this.textContent = null;
            this.contentParts = contentParts;
        }

        public String getRole() { return role; }
        public String getTextContent() { return textContent; }
        public List<ContentPart> getContentParts() { return contentParts; }
    }

    /**
     * A single part of a multimodal message content array.
     * Type is "text" or "image_url".
     */
    public static class ContentPart {
        private final String type;
        private final String text;
        private final String imageUrl;

        public static ContentPart text(String text) {
            return new ContentPart("text", text, null);
        }

        public static ContentPart imageUrl(String url) {
            return new ContentPart("image_url", null, url);
        }

        private ContentPart(String type, String text, String imageUrl) {
            this.type = type;
            this.text = text;
            this.imageUrl = imageUrl;
        }

        public String getType() { return type; }
        public String getText() { return text; }
        public String getImageUrl() { return imageUrl; }
    }

    /**
     * Parsed model response.
     */
    public static class ChatResponse {
        private final String content;
        private final List<ToolCall> toolCalls;
        private final int promptTokens;
        private final int completionTokens;

        public ChatResponse(String content, List<ToolCall> toolCalls,
                            int promptTokens, int completionTokens) {
            this.content = content;
            this.toolCalls = toolCalls != null ? toolCalls : Collections.<ToolCall>emptyList();
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
        }

        public String getContent() { return content; }
        public List<ToolCall> getToolCalls() { return toolCalls; }
        public int getPromptTokens() { return promptTokens; }
        public int getCompletionTokens() { return completionTokens; }
    }

    /**
     * A single tool call returned by the model.
     */
    public static class ToolCall {
        private final String name;
        private final Map<String, Object> arguments;

        public ToolCall(String name, Map<String, Object> arguments) {
            this.name = name;
            this.arguments = arguments != null ? arguments : Collections.<String, Object>emptyMap();
        }

        public String getName() { return name; }
        public Map<String, Object> getArguments() { return arguments; }
    }
}
