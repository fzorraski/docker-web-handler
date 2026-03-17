package br.com.fzdevx.domain.model;

public class ContainerEvent {

    public enum EventType {
        INFO, PROGRESS, SUCCESS, ERROR
    }

    private EventType type;
    private String step;
    private String message;
    private Integer progress;

    public ContainerEvent() {}

    private ContainerEvent(EventType type, String step, String message, Integer progress) {
        this.type = type;
        this.step = step;
        this.message = message;
        this.progress = progress;
    }

    public static ContainerEvent info(String step, String message) {
        return new ContainerEvent(EventType.INFO, step, message, null);
    }

    public static ContainerEvent progress(String step, String message, int progress) {
        return new ContainerEvent(EventType.PROGRESS, step, message, progress);
    }

    public static ContainerEvent success(String step, String message) {
        return new ContainerEvent(EventType.SUCCESS, step, message, null);
    }

    public static ContainerEvent error(String step, String message) {
        return new ContainerEvent(EventType.ERROR, step, message, null);
    }

    public EventType getType() { return type; }
    public void setType(EventType type) { this.type = type; }
    public String getStep() { return step; }
    public void setStep(String step) { this.step = step; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }
}
