package com.github.philippheuer.repositoryupdater.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.SystemUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.Optional;

@Slf4j
public class ExecHelper {

    public static Optional<String> runCommandAndCatchOutput(File directory, String command) {
        String line;

        String[] execCommand;
        if (SystemUtils.IS_OS_LINUX) {
            String[] commands = {"bash", "-c", command};
            execCommand = commands;
        } else {
            String[] commands = {command};
            execCommand = commands;
        }

        try {
            StringBuilder stringBuilder = new StringBuilder();
            Process procInst = Runtime.getRuntime().exec(execCommand, null, directory);
            procInst.waitFor();

            // clean up if any output in stdout
            BufferedReader brCleanUp = new BufferedReader (new InputStreamReader (procInst.getInputStream()));
            while ((line = brCleanUp.readLine()) != null) {
                stringBuilder.append(line);
            }
            brCleanUp.close();

            // clean up if any output in stderr
            brCleanUp = new BufferedReader(new InputStreamReader(procInst.getErrorStream()));
            while ((line = brCleanUp.readLine()) != null) {
                stringBuilder.append(line);
            }
            brCleanUp.close();

            log.debug("Command Output: " + stringBuilder.toString());

            if (procInst.exitValue() != 0) {
                log.error("Error when trying to run command: " + stringBuilder.toString());
                return Optional.empty();
            }

            return Optional.ofNullable(stringBuilder.toString());
        } catch (Exception ex) {
            ex.printStackTrace();
            return Optional.empty();
        }
    }

}
