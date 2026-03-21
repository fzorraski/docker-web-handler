package br.com.fzdevx.application.dto;

import java.util.Map;

public class WebhookPayload {

    public enum Operation { CONTAINER_CREATION, DATABASE_RESTORE }
    public enum Status { SUCCESS, FAILURE }

    private Operation operation;
    private Status status;
    private String message;
    private String timestamp;
    private String errorMessage;
    private Map<String, String> details;

    public WebhookPayload() {}

    public WebhookPayload(Operation operation, Status status, String message,
                          String timestamp, String errorMessage, Map<String, String> details) {
        this.operation = operation;
        this.status = status;
        this.message = message;
        this.timestamp = timestamp;
        this.errorMessage = errorMessage;
        this.details = details;
    }

    public Operation getOperation() { return operation; }
    public void setOperation(Operation operation) { this.operation = operation; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Map<String, String> getDetails() { return details; }
    public void setDetails(Map<String, String> details) { this.details = details; }
}
