package envcli.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class UpdateConfigFile {
    /**
     * Repository Namespace
     */
    @JsonProperty("repositoryNamespace")
    private String repositoryNamespace;

    /**
     * Repository Namespace
     */
    @JsonProperty("repositoryName")
    private String repositoryName;

    /**
     * Repository Namespace
     */
    @JsonProperty("pattern")
    private String pattern;
}
