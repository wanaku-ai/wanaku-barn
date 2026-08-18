package ai.wanaku.cli.main;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * Main entry point for the Wanaku Keycloak admin application.
 * <p>
 * This class bootstraps the Quarkus-based command-line interface for
 * administering the Keycloak instance used by Wanaku. It provides commands for:
 * <ul>
 *   <li>Managing realm users</li>
 *   <li>Managing service client credentials</li>
 *   <li>Importing realm configurations</li>
 * </ul>
 * <p>
 * The actual CLI command implementation is delegated to {@link KeycloakAdminMain},
 * which is executed within the Quarkus runtime environment.
 *
 * @see KeycloakAdminMain
 */
@QuarkusMain
public class Main {

    /**
     * Application entry point.
     * <p>
     * Initializes the Quarkus runtime and executes the CLI application.
     *
     * @param args command-line arguments passed to the application
     */
    public static void main(String[] args) {
        Quarkus.run(KeycloakAdminMain.class, args);
    }
}
