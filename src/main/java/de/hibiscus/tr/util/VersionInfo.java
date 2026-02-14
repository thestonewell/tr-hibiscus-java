package de.hibiscus.tr.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Utility class to load version information from build-time filtered properties.
 */
public class VersionInfo {
    private static final String VERSION;

    static {
        String version = "unknown";
        try (InputStream input = VersionInfo.class.getClassLoader()
                .getResourceAsStream("version.properties")) {
            if (input != null) {
                Properties prop = new Properties();
                prop.load(input);
                version = prop.getProperty("version", "unknown");
            }
        } catch (IOException e) {
            // Use default "unknown" if properties cannot be loaded
        }
        VERSION = version;
    }

    /**
     * Get the application version.
     * @return the version string from pom.xml, or "unknown" if not available
     */
    public static String getVersion() {
        return VERSION;
    }
}
