package br.com.spark.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class CommandHandler {

    public List<String> executeCommand(String command) {
        List<String> resultCommandList = new ArrayList<>();

        try {
            Process p = Runtime.getRuntime().exec(command);
            BufferedReader stdInput = new BufferedReader(new
                    InputStreamReader(p.getInputStream()));

            resultCommandList = readOutputCommand(stdInput);

            if (resultCommandList.isEmpty()) {
                stdInput = new BufferedReader(new
                        InputStreamReader(p.getErrorStream()));

                resultCommandList = readOutputCommand(stdInput);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println(CommandHandler.class.getSimpleName() + " "
                + resultCommandList.size());

        return resultCommandList;
    }


    private List<String> readOutputCommand(BufferedReader stdOut) {
        List<String> outputList = new ArrayList<>();
        String str;

        try {
            //read the output from the command
            while ((str = stdOut.readLine()) != null) {
                outputList.add(str);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return outputList;
    }
}




