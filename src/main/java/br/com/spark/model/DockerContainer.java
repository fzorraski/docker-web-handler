package br.com.spark.model;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DockerContainer {

    private String containerId;

    private String image;

    private String command;

    private String created;

    private String status;

    private String ports;

    private String names;

    public String getContainerId() {
        return containerId;
    }

    public void setContainerId(String containerId) {
        this.containerId = containerId;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String getCreated() {
        return created;
    }

    public void setCreated(String created) {
        this.created = created;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPorts() {
        return ports;
    }

    public void setPorts(String ports) {
        if (ports.equals("-")) {
            this.ports = ports;
        } else {
            this.ports = extractUniquePublicPorts(ports)
                    .toString()
                    .replaceAll("[\\[\\]]", "");
        }
    }

    private Set<Integer> extractUniquePublicPorts(String inputString) {
        Set<Integer> uniquePublicPorts = new HashSet<>();
        Pattern pattern = Pattern.compile("publicPort=(\\d+)");
        Matcher matcher = pattern.matcher(inputString);
        while (matcher.find()) {
            String portStr = matcher.group(1);
            try {
                if (portStr != null && !portStr.isEmpty()) {
                    uniquePublicPorts.add(Integer.parseInt(portStr));
                }
            } catch (NumberFormatException e) {
                System.err.println("Erro ao converter porta para inteiro: " + portStr);
            }
        }
        return uniquePublicPorts;
    }


    public String getNames() {
        return names;
    }

    public void setNames(String names) {
        this.names = names;
    }

    @Override
    public String toString() {
        return "Container{" +
                "containerId='" + containerId + '\'' +
                ", image='" + image + '\'' +
                ", command='" + command + '\'' +
                ", created='" + created + '\'' +
                ", status='" + status + '\'' +
                ", ports=" + ports +
                ", names='" + names + '\'' +
                '}';
    }
}
