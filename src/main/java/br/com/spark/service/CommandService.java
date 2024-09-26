package br.com.spark.service;


import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.core.MediaType;

public interface CommandService {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)

    public String executeCommand(String command);
}
