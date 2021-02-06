package br.com.spark.util;

import br.com.spark.model.Response;

import java.util.List;

public class ResponseWrapper {

    public static Response wrapper(List<String> commandResponse) {
        Response response = new Response();

        for (String s : commandResponse) {
            if (s.contains("image is being used")) {
                response.setState(ERROR_DELETE_DOCKER_IMAGE_IN_USE);
                response.setMessage(s);
            } else if (s.contains("image has dependent child images")) {
                response.setState(ERROR_DELETE_DOCKER_IMAGE_WITH_CHILD);
                response.setMessage(s);
            } else {
                response.setState(SUCCESS);
            }
        }

        return response;
    }

    private static final int SUCCESS = 1;
    private static final int ERROR_DELETE_DOCKER_IMAGE_IN_USE = 100;
    private static final int ERROR_DELETE_DOCKER_IMAGE_WITH_CHILD = 101;

}
