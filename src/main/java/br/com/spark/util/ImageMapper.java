package br.com.spark.util;

import br.com.spark.model.DockerImage;

import java.util.ArrayList;
import java.util.List;

public class ImageMapper {

    public List<DockerImage> mapDockerImage(List<String> resultImageLine) {
        List<DockerImage> mappedImageList = new ArrayList<>();
        if (resultImageLine.size() == 1) return mappedImageList;


        String[] imagesValuesPerColumn;

        for (int i = 1; i < resultImageLine.size(); i++) {
            imagesValuesPerColumn = resultImageLine.get(i).split("\\s{2,}");
            mappedImageList.add(imageParser(imagesValuesPerColumn));
        }

        return mappedImageList;
    }


    private DockerImage imageParser(String[] imagesInfos) {
        DockerImage dockerImage = new DockerImage();

        for (int i = 0; i < imagesInfos.length; i++) {
            if (i == REPOSITORY_INDEX) {
                dockerImage.setRepository(imagesInfos[i]);
            } else if (i == TAG_INDEX) {
                dockerImage.setTag(imagesInfos[i]);
            } else if (i == IMAGE_ID_INDEX) {
                dockerImage.setImageId(imagesInfos[i]);
            } else if (i == CREATED_INDEX) {
                dockerImage.setCreated(imagesInfos[i]);
            } else if (i == SIZE_INDEX) {
                dockerImage.setSize(imagesInfos[i]);
            }
        }

        return dockerImage;
    }

    private static final int REPOSITORY_INDEX = 0;
    private static final int TAG_INDEX = 1;
    private static final int IMAGE_ID_INDEX = 2;
    private static final int CREATED_INDEX = 3;
    private static final int SIZE_INDEX = 4;
}

