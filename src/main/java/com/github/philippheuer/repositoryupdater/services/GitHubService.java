package com.github.philippheuer.repositoryupdater.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.github.zafarkhaja.semver.Version;
import com.github.philippheuer.repositoryupdater.domain.UpdateConfigFile;
import com.github.philippheuer.repositoryupdater.util.ExecHelper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.kohsuke.github.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.*;

@Service()
@Slf4j
public class GitHubService {

    @Value("${github.username}")
    private String username;

    @Value("${github.password}")
    private String password;

    @Value("${targetOrganization}")
    private String targetOrganization;

    /**
     * Git - Commit Author - Name
     */
    @Value("${git.authorName}")
    private String authorName;

    /**
     * Git - Commit Author - Email
     */
    @Value("${git.authorEmail}")
    private String authorEmail;

    private OkHttpClient okHttpClient = new OkHttpClient();

    private ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public void checkRepositoriesForUpstreamUpdates() {
        log.info(String.format("Starting to analyse repositories within organization [%s].", targetOrganization));

        try {
            GitHub github = GitHub.connectUsingPassword(username, password);
            Map<String, GHRepository> repositories = github.getOrganization(targetOrganization).getRepositories();

            repositories.values().forEach(repository -> {
                try {
                    log.info(String.format("Starting to analyse repository [%s / %s].", repository.getOwnerName(), repository.getName()));

                    // generate dl url for the configuration file
                    String repositoryDownloadUrl = String.format("https://raw.githubusercontent.com/%s/%s/%s", repository.getOwnerName(), repository.getName(), repository.getDefaultBranch());
                    String configFileUrl = repositoryDownloadUrl + "/updater.yml";
                    log.info(String.format("Trying to download update configuration file from [%s].", configFileUrl));

                    // download config file content
                    Request request = new Request.Builder()
                            .url(configFileUrl)
                            .build();
                    Call call = okHttpClient.newCall(request);
                    Response response = call.execute();

                    // success?
                    if (response.isSuccessful()) {
                        // parse response into object
                        UpdateConfigFile configFile = yamlMapper.readValue(response.body().string(), UpdateConfigFile.class);

                        // current version
                        Version currentVersion = Version.valueOf(repository.getLatestRelease() == null ? "0.0.0" : repository.getLatestRelease().getTagName().replace("v", ""));

                        // get latest release from the upstream repo
                        GHRepository upstreamRepository = null;
                        try {
                            upstreamRepository = github.getOrganization(configFile.getRepositoryNamespace()).getRepository(configFile.getRepositoryName());
                        } catch (Exception ex) {
                            // nothing
                        }
                        try {
                            upstreamRepository = github.getUser(configFile.getRepositoryNamespace()).getRepository(configFile.getRepositoryName());
                        } catch (Exception ex) {
                            // nothing
                        }
                        if (upstreamRepository == null) {
                            log.warn("Can't find upstream repository ... skipping!");
                            return;
                        }
                        log.info("Current version in repository is: " + currentVersion.toString());
                        log.info("Searching for latest release in repo: " + upstreamRepository.getFullName());

                        // find latest release in upstream repository
                        Version upstreamVersion = Version.valueOf("0.0.0");
                        String tagShaShort = "";
                        String tagShaLong = "";
                        GHRelease upstreamRelease = null;

                        // generate tag list
                        List<GHTag> tagList = new ArrayList<>();
                        for(GHTag tag : upstreamRepository.listTags()) {
                            tagList.add(tag);
                        }

                        // reverse to start from oldest
                        Collections.reverse(tagList);

                        // iterate over all tags
                        for(GHTag tag : tagList) {
                            try {
                                Version tempVersion = Version.valueOf(tag.getName().replace("v", ""));
                                // ignore pre-releases
                                if (tempVersion.getPreReleaseVersion().isEmpty() && tempVersion.greaterThan(upstreamVersion) && tempVersion.greaterThan(currentVersion)) {
                                    upstreamVersion = tempVersion;
                                    tagShaShort = tag.getCommit().getSHA1().substring(0, 7);
                                    tagShaLong = tag.getCommit().getSHA1();

                                    log.info("Found version: " + upstreamVersion.toString() + " - SHA Short: " + tagShaShort + " - SHA Long: " + tagShaLong);

                                    // try to find upstream release to copy release notes
                                    try {
                                        upstreamRelease = upstreamRepository.getReleaseByTagName(tag.getName());
                                    } catch (Exception ex) {
                                        // ignore
                                        upstreamRelease = null;
                                    }
                                    break;
                                }
                            } catch (Exception ex) {
                                // ignore
                            }
                        }

                        // new version check (upstream >= current repo)
                        if (upstreamVersion.compareTo(currentVersion) > 0) {
                            log.info(String.format("Upstream repository has a newer release [%s] than the current repository [%s].", upstreamVersion, currentVersion));

                            // new version found, we need to commit the new version and create a new release
                            String tempDirectory = System.getProperty("java.io.tmpdir");
                            String repoDirectory = tempDirectory + "/repo-" + new Random().nextInt(500);
                            String repoContentDirectory = repoDirectory + "/" + repository.getName();
                            File fileRepoDirectory = new File(repoDirectory);
                            File fileRepoContentDirectory = new File(repoContentDirectory);
                            fileRepoDirectory.mkdirs();

                            // git clone
                            Optional<String> gitCloneCmd = ExecHelper.runCommandAndCatchOutput(fileRepoDirectory, String.format("git clone https://%s:%s@github.com/%s/%s.git", username, password, repository.getOwnerName(), repository.getName()));
                            if (!gitCloneCmd.isPresent()) {
                                log.error("Failed to execute git clone ... skipping! - " + gitCloneCmd.get());
                                return;
                            }

                            // modify file
                            String fileContent = "";
                            BufferedReader reader = new BufferedReader(new FileReader(repoContentDirectory + "/" + "Dockerfile"));
                            String line = reader.readLine();
                            while (line != null)
                            {
                                fileContent = fileContent + line + System.lineSeparator();
                                line = reader.readLine();
                            }

                            // - Replacing oldString with newString in the oldContent
                            fileContent = fileContent.replaceAll("ENV VERSION \".+\"", String.format("ENV VERSION \"%s\"", upstreamVersion.toString()));
                            fileContent = fileContent.replaceAll("ENV VERSION_TAG_SHA_SHORT \".+\"", String.format("ENV VERSION_TAG_SHA_SHORT \"%s\"", tagShaShort));
                            fileContent = fileContent.replaceAll("ENV VERSION_TAG_SHA_LONG \".+\"", String.format("ENV VERSION_TAG_SHA_LONG \"%s\"", tagShaLong));

                            // - Rewriting the input text file with newContent
                            FileWriter writer = new FileWriter(repoContentDirectory + "/" + "Dockerfile");
                            writer.write(fileContent);
                            writer.flush();
                            writer.close();
                            log.info("Updated Dockerfile ENV VERSION for latest release.");

                            // commit
                            Optional<String> gitAddCmd = ExecHelper.runCommandAndCatchOutput(fileRepoContentDirectory, "git add Dockerfile");
                            if (!gitAddCmd.isPresent()) {
                                log.error("Failed to execute git add ... skipping! - " + gitAddCmd.get());
                                return;
                            }

                            Optional<String> gitCommitCmd = ExecHelper.runCommandAndCatchOutput(fileRepoContentDirectory, String.format("git commit --no-gpg-sign --author=\"%s <%s>\" -m \"feature: upgrade to v%s\"", authorName, authorEmail, upstreamVersion.toString()));
                            if (!gitCommitCmd.isPresent()) {
                                log.error("Failed to execute git commit ... skipping! - " + gitCommitCmd.get());
                                return;
                            }

                            log.info(String.format("Created commit to upgrade to [v%s].", upstreamVersion.toString()));

                            // create tag
                            Optional<String> gitTagCmd = ExecHelper.runCommandAndCatchOutput(fileRepoContentDirectory, String.format("git tag -a v%s -m \"Release %s\"", upstreamVersion.toString(), upstreamVersion.toString()));
                            if (!gitTagCmd.isPresent()) {
                                log.error("Failed to execute git tag ... skipping! - " + gitTagCmd.get());
                                return;
                            }

                            log.info(String.format("Created new tag in repository [v%s].", upstreamVersion.toString()));

                            // push branches
                            Optional<String> gitPushCmd = ExecHelper.runCommandAndCatchOutput(fileRepoContentDirectory, String.format("git push --follow-tags https://%s:%s@github.com/%s/%s.git master", username, password, repository.getOwnerName(), repository.getName()));
                            if (!gitPushCmd.isPresent()) {
                                log.error("Failed to execute git push ... skipping! - " + gitPushCmd.get());
                                return;
                            }

                            // create release for tag
                            StringBuilder releaseNotes = new StringBuilder();

                            if (upstreamRelease != null) {
                                releaseNotes.append("\n\n");
                                releaseNotes.append(upstreamRelease.getBody());
                            } else {
                                releaseNotes.append("Automated release of [v" + upstreamVersion.toString() + "]");
                            }
                            repository.createRelease("v" + upstreamVersion.toString())
                                    .name("v" + upstreamVersion.toString())
                                    .body(releaseNotes.toString())
                                    .draft(false)
                                    .prerelease(false)
                                    .create();

                            // cleanup
                            fileRepoDirectory.delete();
                        } else {
                            log.warn("Repository is already up2date!");
                        }
                    } else {
                        log.warn("Repository does not contain config file to automated upgrades!");
                    }

                    log.warn("Waiting 1 second before checking the next repo ...");
                    Thread.sleep(1000);
                } catch (Exception ex) {
                    log.warn("Unexpected error: " + ex.getMessage());
                    ex.printStackTrace();
                }
            });

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

}
