package br.com.spark.util;

import br.com.spark.model.Container;

import java.util.ArrayList;
import java.util.List;

public class ContainerMapper {

    public List<Container> map(List<String> resultContainerLine) {
        List<Container> mappedContainerList = new ArrayList<>();
        if (resultContainerLine.size() == 1) return mappedContainerList;

        String[] containerValuesPerColumn;

        for (int i = 1; i < resultContainerLine.size(); i++) {
            containerValuesPerColumn = resultContainerLine.get(i).split("\\s{2,}");
            mappedContainerList.add(containerParser(containerValuesPerColumn));
        }

        return mappedContainerList;
    }

    private Container containerParser(String[] containerInfo) {
        Container container = new Container();

        for (int i = 0; i < containerInfo.length; i++) {
            if (i == CONTAINER_ID_INDEX) {
                container.setContainerId(containerInfo[i]);
            } else if (i == IMAGE_INDEX) {
                container.setImage(containerInfo[i]);
            } else if (i == COMMAND_INDEX) {
                container.setCommand(containerInfo[i]);
            } else if (i == CREATED_INDEX) {
                container.setCreated(containerInfo[i]);
            } else if (i == STATUS_INDEX) {
                container.setStatus(containerInfo[i]);
            } else if (i == PORTS_INDEX && container.isPort(containerInfo[i])) {
                container.setPorts(containerInfo[i]);
            } else {
                container.setNames(containerInfo[i]);
            }
        }
        return container;
    }

    private static final int CONTAINER_ID_INDEX = 0;
    private static final int IMAGE_INDEX = 1;
    private static final int COMMAND_INDEX = 2;
    private static final int CREATED_INDEX = 3;
    private static final int STATUS_INDEX = 4;
    private static final int PORTS_INDEX = 5;
    private static final int NAMES_INDEX = 6;
}
