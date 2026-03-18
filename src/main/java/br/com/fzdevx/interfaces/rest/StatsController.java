package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.infrastructure.persistence.ResourceCounterService;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Path("/stats")
public class StatsController {

    @Inject
    ResourceCounterService resourceCounterService;

    private String startedAt;

    @PostConstruct
    void init() {
        startedAt = Instant.now().toString();
    }

    @GET
    @Path("/summary")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> getSummary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.putAll(resourceCounterService.getAll());
        result.put("startedAt", startedAt);
        return result;
    }
}
