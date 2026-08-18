package ai.wanaku.cli.main;

import jakarta.inject.Inject;

import java.util.concurrent.Callable;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import io.quarkus.runtime.QuarkusApplication;
import ai.wanaku.cli.main.commands.BaseCommand;
import ai.wanaku.cli.main.commands.credentials.Credentials;
import ai.wanaku.cli.main.commands.realm.Realm;
import ai.wanaku.cli.main.commands.users.Users;
import ai.wanaku.cli.main.support.WanakuExceptionHandler;
import ai.wanaku.core.util.VersionHelper;
import picocli.CommandLine;

@TopCommand
@CommandLine.Command(
        name = "wanaku-keycloak-admin",
        description = "Keycloak administration commands for Wanaku",
        subcommands = {Users.class, Credentials.class, Realm.class})
public class KeycloakAdminMain implements Callable<Integer>, QuarkusApplication {
    @Inject
    CommandLine.IFactory factory;

    @CommandLine.Option(
            names = {"-h", "--help"},
            usageHelp = true,
            description = "Display the help and sub-commands")
    private boolean helpRequested = false;

    @CommandLine.Option(
            names = {"-v", "--version"},
            description = "Display the current version of the Wanaku Keycloak admin tool")
    private boolean versionRequested = false;

    @CommandLine.Option(
            names = {"--verbose"},
            description = "Display detailed error messages including stack traces",
            scope = CommandLine.ScopeType.INHERIT)
    private boolean verbose = false;

    @Override
    public int run(String... args) {
        CommandLine commandLine = new CommandLine(this, factory);
        commandLine.setExecutionExceptionHandler(new WanakuExceptionHandler());
        return commandLine.execute(args);
    }

    @Override
    public Integer call() {
        if (versionRequested) {
            System.out.println("Wanaku Keycloak admin version " + VersionHelper.VERSION);
            return BaseCommand.EXIT_OK;
        }

        CommandLine.usage(this, System.out);
        return BaseCommand.EXIT_ERROR;
    }
}
