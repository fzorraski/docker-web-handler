package br.com.fzdevx.domain.model;

public class ContainerStats {

    private double cpuPercent;
    private long memoryUsage;
    private long memoryLimit;
    private double memoryPercent;
    private long networkRxBytes;
    private long networkTxBytes;
    private long networkRxRate;
    private long networkTxRate;
    private long blockReadBytes;
    private long blockWriteBytes;
    private long pids;
    private String timestamp;

    public ContainerStats() {}

    public double getCpuPercent() { return cpuPercent; }
    public void setCpuPercent(double cpuPercent) { this.cpuPercent = cpuPercent; }

    public long getMemoryUsage() { return memoryUsage; }
    public void setMemoryUsage(long memoryUsage) { this.memoryUsage = memoryUsage; }

    public long getMemoryLimit() { return memoryLimit; }
    public void setMemoryLimit(long memoryLimit) { this.memoryLimit = memoryLimit; }

    public double getMemoryPercent() { return memoryPercent; }
    public void setMemoryPercent(double memoryPercent) { this.memoryPercent = memoryPercent; }

    public long getNetworkRxBytes() { return networkRxBytes; }
    public void setNetworkRxBytes(long networkRxBytes) { this.networkRxBytes = networkRxBytes; }

    public long getNetworkTxBytes() { return networkTxBytes; }
    public void setNetworkTxBytes(long networkTxBytes) { this.networkTxBytes = networkTxBytes; }

    public long getNetworkRxRate() { return networkRxRate; }
    public void setNetworkRxRate(long networkRxRate) { this.networkRxRate = networkRxRate; }

    public long getNetworkTxRate() { return networkTxRate; }
    public void setNetworkTxRate(long networkTxRate) { this.networkTxRate = networkTxRate; }

    public long getBlockReadBytes() { return blockReadBytes; }
    public void setBlockReadBytes(long blockReadBytes) { this.blockReadBytes = blockReadBytes; }

    public long getBlockWriteBytes() { return blockWriteBytes; }
    public void setBlockWriteBytes(long blockWriteBytes) { this.blockWriteBytes = blockWriteBytes; }

    public long getPids() { return pids; }
    public void setPids(long pids) { this.pids = pids; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
}
