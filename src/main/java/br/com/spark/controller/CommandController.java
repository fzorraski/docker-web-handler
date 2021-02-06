package br.com.spark.controller;

import br.com.spark.util.CommandHandler;
import br.com.spark.model.Command;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.List;

@Path("/command")
public class CommandController extends CommandHandler {

//    @Inject
//    @RestClient
//    CommandService commandService;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    @Path("/2")
    public List<String> commandResponse(Command command) {
        return executeCommand(command.getCommand());
    }


}
