package ai.wanaku.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * A utility class that provides a way to retrieve Wanaku current version
 */
public final class VersionHelper {

    /**
     * The current version of this library, loaded from the "version.txt" resource in this Java package.
     */
    public static final String VERSION;

    static {
        // The package-specific path avoids the SDK's unrelated root /version.txt resource.
        VERSION = initVersion();
    }

    /**
     * Private constructor to prevent instantiation and ensure this class is used as a utility.
     */
    private VersionHelper() {}

    /**
     * Initializes and returns the version string by reading from the "version.txt" resource in this Java package.
     *
     * @return The current version of this library.
     */
    private static String initVersion() {
        try (InputStream stream = VersionHelper.class.getResourceAsStream("version.txt")) {
            if (stream == null) {
                throw new IllegalStateException("ai/wanaku/core/util/version.txt not found on classpath");
            }
            byte[] bytes = stream.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
