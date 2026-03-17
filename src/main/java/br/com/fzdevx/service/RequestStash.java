package br.com.fzdevx.service;

import br.com.fzdevx.model.CreateSnapshotRequest;
import br.com.fzdevx.model.RestoreDumpRequest;
import br.com.fzdevx.model.RunContainerRequest;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class RequestStash {

    private final ConcurrentHashMap<String, RunContainerRequest> stash = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RestoreDumpRequest> restoreStash = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CreateSnapshotRequest> snapshotStash = new ConcurrentHashMap<>();

    public String stash(RunContainerRequest request) {
        String ticket = UUID.randomUUID().toString();
        stash.put(ticket, request);
        return ticket;
    }

    public RunContainerRequest retrieve(String ticket) {
        return stash.remove(ticket);
    }

    public String stashRestore(RestoreDumpRequest request) {
        String ticket = UUID.randomUUID().toString();
        restoreStash.put(ticket, request);
        return ticket;
    }

    public RestoreDumpRequest retrieveRestore(String ticket) {
        return restoreStash.remove(ticket);
    }

    public String stashSnapshot(CreateSnapshotRequest request) {
        String ticket = UUID.randomUUID().toString();
        snapshotStash.put(ticket, request);
        return ticket;
    }

    public CreateSnapshotRequest retrieveSnapshot(String ticket) {
        return snapshotStash.remove(ticket);
    }
}
