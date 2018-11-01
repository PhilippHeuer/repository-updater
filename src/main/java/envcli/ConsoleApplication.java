package envcli;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.Banner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import envcli.services.GitHubService;

@EnableScheduling
@SpringBootApplication
public class ConsoleApplication implements CommandLineRunner {

    @Autowired
    private GitHubService gitHubService;

    public static void main(String[] args) throws Exception {
        // disable spring banner
        SpringApplication app = new SpringApplication(ConsoleApplication.class);
        app.setBannerMode(Banner.Mode.OFF);
        app.run(args);
    }

    // Put your logic here.
    @Override
    public void run(String... args) throws Exception {
        // run check
        gitHubService.checkRepositoriesForUpstreamUpdates();
    }
}