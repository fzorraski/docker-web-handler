package br.com.fzdevx.interfaces.rest.dto;

import br.com.fzdevx.interfaces.rest.dto.Response;

import java.util.List;

public class ResponseWrapper {


    public enum ResponseState {
        SUCCESS(1),
        IMAGE_IN_USE(100),
        IMAGE_HAS_CHILDREN(101);

        private final int code;

        ResponseState(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    public static Response wrapper(List<String> commandResponse) {
        Response response = new Response();

        for (String s : commandResponse) {
            if (s.contains("image is being used")) {
                response.setState(ResponseState.IMAGE_IN_USE.code());
                response.setMessage(s);
            } else if (s.contains("image has dependent child images")) {
                response.setState(ResponseState.IMAGE_HAS_CHILDREN.code());
                response.setMessage(s);
            } else {
                response.setState(ResponseState.SUCCESS.code());
            }
        }

        return response;
    }
}
