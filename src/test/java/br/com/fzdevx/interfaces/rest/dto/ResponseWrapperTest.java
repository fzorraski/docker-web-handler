package br.com.fzdevx.interfaces.rest.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResponseWrapperTest {

    @Test
    void wrapper_successMessage_returnsState1() {
        Response response = ResponseWrapper.wrapper(List.of("Image removed successfully."));

        assertEquals(1, response.getState());
    }

    @Test
    void wrapper_imageInUse_returnsState100() {
        Response response = ResponseWrapper.wrapper(List.of("conflict: image is being used by container abc"));

        assertEquals(100, response.getState());
        assertTrue(response.getMessage().contains("image is being used"));
    }

    @Test
    void wrapper_imageHasChildren_returnsState101() {
        Response response = ResponseWrapper.wrapper(List.of("conflict: image has dependent child images"));

        assertEquals(101, response.getState());
    }

    @Test
    void wrapper_multipleMessages_lastOneWins() {
        Response response = ResponseWrapper.wrapper(List.of(
                "Some success",
                "conflict: image is being used by container xyz"
        ));

        assertEquals(100, response.getState());
    }

    @Test
    void wrapper_emptyList_defaultState() {
        Response response = ResponseWrapper.wrapper(List.of());

        assertEquals(0, response.getState());
    }

    // ---- ResponseState enum ----

    @Test
    void responseState_successCode() {
        assertEquals(1, ResponseWrapper.ResponseState.SUCCESS.code());
    }

    @Test
    void responseState_imageInUseCode() {
        assertEquals(100, ResponseWrapper.ResponseState.IMAGE_IN_USE.code());
    }

    @Test
    void responseState_imageHasChildrenCode() {
        assertEquals(101, ResponseWrapper.ResponseState.IMAGE_HAS_CHILDREN.code());
    }
}
