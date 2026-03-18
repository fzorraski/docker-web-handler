package br.com.fzdevx.application.dto;

public class PruneImagesRequest {

    private String password;
    private int minDays;

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public int getMinDays() {
        return minDays;
    }

    public void setMinDays(int minDays) {
        this.minDays = minDays;
    }
}
