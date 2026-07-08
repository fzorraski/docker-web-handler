package br.com.fzdevx.domain.model.auth;

public enum Permission {

    CONTAINERS_VIEW("CONTAINERS"),
    CONTAINERS_OPERATE("CONTAINERS"),
    CONTAINERS_RUN("CONTAINERS"),
    IMAGES_VIEW("IMAGES"),
    IMAGES_MANAGE("IMAGES"),
    DATABASE_VIEW("DATABASE"),
    DATABASE_OPERATE("DATABASE"),
    DATABASE_UPLOAD("DATABASE"),
    SCHEDULES_VIEW("SCHEDULES"),
    SCHEDULES_MANAGE("SCHEDULES"),
    TERMINAL_ACCESS("TERMINAL"),
    LOGS_VIEW("LOGS"),
    LOGS_ANALYZE("LOGS"),
    USERS_MANAGE("ADMINISTRATION"),
    SYSTEM_CONFIG("ADMINISTRATION");

    private final String category;

    Permission(String category) {
        this.category = category;
    }

    public String getCategory() {
        return category;
    }
}
