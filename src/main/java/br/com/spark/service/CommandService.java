package br.com.spark.service;


import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.core.MediaType;

public interface CommandService {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)

    public String executeCommand(String command);
}
