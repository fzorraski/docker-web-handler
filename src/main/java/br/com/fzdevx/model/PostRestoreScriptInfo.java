package br.com.fzdevx.model;

public class PostRestoreScriptInfo {

    private String filename;
    private int sortOrder;
    private long fileSize;

    public PostRestoreScriptInfo() {}

    public PostRestoreScriptInfo(String filename, int sortOrder, long fileSize) {
        this.filename = filename;
        this.sortOrder = sortOrder;
        this.fileSize = fileSize;
    }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }
}
