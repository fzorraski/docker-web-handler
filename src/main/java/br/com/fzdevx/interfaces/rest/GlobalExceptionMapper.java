package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.domain.exception.DomainException;
import br.com.fzdevx.domain.exception.DuplicateDumpException;
import br.com.fzdevx.domain.exception.DuplicateEntityException;
import br.com.fzdevx.domain.exception.EntityNotFoundException;
import br.com.fzdevx.domain.exception.InvalidInputException;
import br.com.fzdevx.domain.exception.OperationInProgressException;
import br.com.fzdevx.domain.exception.RateLimitedException;
import io.quarkus.logging.Log;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;



@Provider
public class GlobalExceptionMapper implements ExceptionMapper<Exception> {

    @Override
    public Response toResponse(Exception exception) {
        // Handle JAX-RS exceptions (NotFoundException, etc.)
        if (exception instanceof WebApplicationException wae) {
            int status = wae.getResponse().getStatus();
            Log.debugf("WebApplicationException [%d]: %s", status, exception.getMessage());
            return buildResponse(status, mapStatusToCode(status), sanitizeMessage(exception.getMessage()));
        }

        // Handle rate limiting (before general DomainException handling)
        if (exception instanceof RateLimitedException rle) {
            Log.debugf("RateLimitedException: %s", rle.getMessage());
            return Response.status(429)
                    .type(MediaType.APPLICATION_JSON)
                    .header("Retry-After", rle.getRetryAfterSeconds())
                    .entity(Map.of("code", "TOO_MANY_REQUESTS",
                                   "message", rle.getMessage(),
                                   "retryAfter", rle.getRetryAfterSeconds()))
                    .build();
        }

        // Handle sealed domain exception hierarchy
        if (exception instanceof DomainException de) {
            int status = mapDomainExceptionStatus(de);
            Log.debugf("DomainException [%s]: %s", de.getCode(), de.getMessage());
            return buildResponse(status, de.getCode(), de.getMessage());
        }

        // Handle DuplicateDumpException (legacy, not part of sealed hierarchy)
        if (exception instanceof DuplicateDumpException dde) {
            return buildResponse(409, "DUPLICATE", dde.getMessage());
        }

        Log.errorf(exception, "Unhandled exception: %s", exception.getMessage());

        return buildResponse(500, "INTERNAL_ERROR", "An unexpected error occurred.");
    }

    private int mapDomainExceptionStatus(DomainException de) {
        return switch (de) {
            case EntityNotFoundException e -> 404;
            case InvalidInputException e -> 400;
            case DuplicateEntityException e -> 409;
            case OperationInProgressException e -> 409;
            case RateLimitedException e -> 429;
            default -> 500;
        };
    }

    private Response buildResponse(int status, String code, String message) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("code", code, "message", message))
                .build();
    }

    private String mapStatusToCode(int status) {
        return switch (status) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            case 429 -> "TOO_MANY_REQUESTS";
            default -> "ERROR";
        };
    }

    private String sanitizeMessage(String message) {
        if (message == null) {
            return "An error occurred.";
        }
        // Strip potential internal details (class names, stack info)
        if (message.contains("at ") && message.contains("(")) {
            return "An error occurred.";
        }
        return message;
    }
}
