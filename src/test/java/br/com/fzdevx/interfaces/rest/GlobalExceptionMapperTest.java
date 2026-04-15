package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.domain.exception.*;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionMapperTest {

    private GlobalExceptionMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new GlobalExceptionMapper();
    }

    // ---- RateLimitedException ----

    @Test
    void rateLimitedException_returns429() {
        Response res = mapper.toResponse(new RateLimitedException(30));
        assertEquals(429, res.getStatus());
    }

    @Test
    void rateLimitedException_includesRetryAfterHeader() {
        Response res = mapper.toResponse(new RateLimitedException(45));
        assertEquals(45L, res.getHeaders().getFirst("Retry-After"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rateLimitedException_includesRetryAfterInBody() {
        Response res = mapper.toResponse(new RateLimitedException(60));
        Map<String, Object> entity = (Map<String, Object>) res.getEntity();
        assertEquals(60L, entity.get("retryAfter"));
        assertEquals("TOO_MANY_REQUESTS", entity.get("code"));
        assertTrue(entity.get("message").toString().contains("60"));
    }

    // ---- Domain exceptions ----

    @Test
    void entityNotFoundException_returns404() {
        Response res = mapper.toResponse(new EntityNotFoundException("not found"));
        assertEquals(404, res.getStatus());
    }

    @Test
    void invalidInputException_returns400() {
        Response res = mapper.toResponse(new InvalidInputException("bad input"));
        assertEquals(400, res.getStatus());
    }

    @Test
    void duplicateEntityException_returns409() {
        Response res = mapper.toResponse(new DuplicateEntityException("duplicate"));
        assertEquals(409, res.getStatus());
    }

    @Test
    void operationInProgressException_returns409() {
        Response res = mapper.toResponse(new OperationInProgressException("in progress"));
        assertEquals(409, res.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void domainException_includesCodeAndMessage() {
        Response res = mapper.toResponse(new EntityNotFoundException("item missing"));
        Map<String, Object> entity = (Map<String, Object>) res.getEntity();
        assertEquals("NOT_FOUND", entity.get("code"));
        assertEquals("item missing", entity.get("message"));
    }

    // ---- WebApplicationException ----

    @Test
    void webApplicationException_mapsStatusCode() {
        Response res = mapper.toResponse(new NotFoundException("not here"));
        assertEquals(404, res.getStatus());
    }

    // ---- Generic exception ----

    @Test
    void genericException_returns500() {
        Response res = mapper.toResponse(new RuntimeException("boom"));
        assertEquals(500, res.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void genericException_hidesInternalDetails() {
        Response res = mapper.toResponse(new RuntimeException("boom"));
        Map<String, Object> entity = (Map<String, Object>) res.getEntity();
        assertEquals("INTERNAL_ERROR", entity.get("code"));
        assertEquals("An unexpected error occurred.", entity.get("message"));
    }
}
