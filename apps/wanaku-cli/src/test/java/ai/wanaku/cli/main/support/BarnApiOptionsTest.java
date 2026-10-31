package ai.wanaku.cli.main.support;

import ai.wanaku.cli.main.commands.backup.BackupExport;
import ai.wanaku.cli.main.commands.backup.BackupImport;
import ai.wanaku.cli.main.commands.datastores.DataStoresAdd;
import ai.wanaku.cli.main.commands.datastores.DataStoresGet;
import ai.wanaku.cli.main.commands.datastores.DataStoresLabelAdd;
import ai.wanaku.cli.main.commands.datastores.DataStoresLabelRemove;
import ai.wanaku.cli.main.commands.datastores.DataStoresList;
import ai.wanaku.cli.main.commands.datastores.DataStoresRemove;
import ai.wanaku.cli.main.commands.service.ServiceCatalogList;
import ai.wanaku.cli.main.commands.service.ServiceCatalogRemove;
import ai.wanaku.cli.main.commands.service.ServiceDeploy;
import ai.wanaku.cli.main.commands.service.ServiceInstructions;
import ai.wanaku.cli.main.commands.service.ServiceRemoved;
import ai.wanaku.cli.main.commands.service.ServiceRestore;
import ai.wanaku.cli.main.commands.service.ServiceTemplateInstantiate;
import ai.wanaku.cli.main.commands.service.ServiceTemplateList;
import ai.wanaku.cli.main.commands.service.ServiceValidate;
import ai.wanaku.cli.main.commands.service.ServiceVersionsDownload;
import ai.wanaku.cli.main.commands.service.ServiceVersionsList;
import ai.wanaku.cli.main.commands.service.ServiceVersionsRestore;
import picocli.CommandLine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** The Barn commands reach Barn through the Wanaku plugin proxy unless --direct-access is set (#346). */
class BarnApiOptionsTest {

    @Test
    void defaultsToTheWanakuPluginProxy() {
        assertEquals("http://localhost:8080/api/plugins/wanaku-barn/wanaku-barn-api", parse().baseUri());
    }

    @Test
    void appendsThePluginPathToTheHost() {
        assertEquals(
                "https://wanaku.example.com/api/plugins/wanaku-barn/wanaku-barn-api",
                parse("--host", "https://wanaku.example.com/").baseUri());
    }

    @Test
    void directAccessCallsBarnAtTheHost() {
        assertEquals(
                "http://localhost:8180",
                parse("--host", "http://localhost:8180", "--direct-access").baseUri());
    }

    @ParameterizedTest
    @ValueSource(
            classes = {
                BackupExport.class,
                BackupImport.class,
                DataStoresAdd.class,
                DataStoresGet.class,
                DataStoresLabelAdd.class,
                DataStoresLabelRemove.class,
                DataStoresList.class,
                DataStoresRemove.class,
                ServiceCatalogList.class,
                ServiceCatalogRemove.class,
                ServiceDeploy.class,
                ServiceInstructions.class,
                ServiceRemoved.class,
                ServiceRestore.class,
                ServiceTemplateInstantiate.class,
                ServiceTemplateList.class,
                ServiceValidate.class,
                ServiceVersionsDownload.class,
                ServiceVersionsList.class,
                ServiceVersionsRestore.class
            })
    void barnCommandsUseTheBarnOptions(Class<?> command) {
        CommandLine.Model.CommandSpec spec = CommandLine.Model.CommandSpec.forAnnotatedObject(command);
        assertEquals("http://localhost:8080", spec.findOption("--host").defaultValue());
        assertNotNull(spec.findOption("--direct-access"));
    }

    private static BarnApiOptions parse(String... args) {
        BarnApiOptions options = new BarnApiOptions();
        new CommandLine(options).parseArgs(args);
        return options;
    }
}
