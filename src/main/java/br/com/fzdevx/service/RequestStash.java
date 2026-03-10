package br.com.fzdevx.service;

import br.com.fzdevx.model.RunContainerRequest;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class RequestStash {

    private final ConcurrentHashMap<String, RunContainerRequest> stash = new ConcurrentHashMap<>();

    public String stash(RunContainerRequest request) {
        String ticket = UUID.randomUUID().toString();
        stash.put(ticket, request);
        return ticket;
    }

    public RunContainerRequest retrieve(String ticket) {
        return stash.remove(ticket);
    }
}
