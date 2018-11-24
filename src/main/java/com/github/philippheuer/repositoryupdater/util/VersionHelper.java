package com.github.philippheuer.repositoryupdater.util;

import java.util.regex.Pattern;

public class VersionHelper {

    /**
     * Tries to make a version follow the default version convetions
     *
     * @param version Broken or correct version
     * @return correct version
     */
    public static String makeValid(String version) {
        // remove v prefix if present
        version = version.startsWith("v") ? version.substring(1) : version;

        // check for missing patch version and append .0
        if (Pattern.compile("^(((\\d+)\\.)(\\d+|\\*))$").matcher(version).find()) {
            version = version + ".0";
        }


        // result
        return version;
    }

}
