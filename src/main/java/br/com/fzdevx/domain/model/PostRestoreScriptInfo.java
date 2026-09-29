package br.com.fzdevx.domain.model;

public class PostRestoreScriptInfo {

    private String filename;
    private int sortOrder;
    private long fileSize;
    /** Mandatory scripts always run and a failure always stops the restore. */
    private boolean mandatory;

    public PostRestoreScriptInfo() {}

    public PostRestoreScriptInfo(String filename, int sortOrder, long fileSize) {
        this(filename, sortOrder, fileSize, false);
    }

    public PostRestoreScriptInfo(String filename, int sortOrder, long fileSize, boolean mandatory) {
        this.filename = filename;
        this.sortOrder = sortOrder;
        this.fileSize = fileSize;
        this.mandatory = mandatory;
    }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public boolean isMandatory() { return mandatory; }
    public void setMandatory(boolean mandatory) { this.mandatory = mandatory; }
}
