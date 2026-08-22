package io.paradaux.hibernia.framework.guice;

import com.google.inject.Guice;
import com.google.inject.Injector;
import io.papermc.paper.dialog.DialogResponseView;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.hibernia.framework.i18n.PapiSupport;
import io.paradaux.hibernia.framework.usher.DialogView;
import io.paradaux.hibernia.framework.usher.annotations.Dialog;
import io.paradaux.hibernia.framework.usher.annotations.Screen;
import io.paradaux.hibernia.framework.usher.spi.BedrockSupport;
import io.paradaux.hibernia.framework.usher.spi.DialogHandler;
import io.paradaux.hibernia.framework.usher.spi.InputBinder;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises the {@link HiberniaModule} builder options the baseline module test omits: dialog handlers,
 * custom input binders, Bedrock support, a custom PlaceholderAPI bridge, an eager {@code Message}
 * binding, and opting out of defaults reconciliation.
 */
class HiberniaModuleCoverageTest {

    @Dialog("sample")
    public static class SampleDialog implements DialogHandler {
        @Screen
        public DialogView main() {
            return DialogView.notice("t").build();
        }
    }

    public static class UpperBinder implements InputBinder<String> {
        @Override
        public Class<String> type() {
            return String.class;
        }

        @Override
        public String read(DialogResponseView view, String key) {
            return view.getText(key);
        }
    }

    public static class TestBedrock implements BedrockSupport {
        @Override
        public boolean isBedrock(Player player) {
            return false;
        }
    }

    public static class TestPapi implements PapiSupport {
        @Override
        public String resolve(@Nullable OfflinePlayer player, String text) {
            return text;
        }
    }

    @TempDir
    Path dataFolder;

    private JavaPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(mock(java.util.logging.Logger.class));
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        // Message bootstrap writes a default bundle then reloads from the (empty) data folder.
        doAnswer(inv -> null).when(plugin).saveResource(anyString(), anyBoolean());
        lenient().when(plugin.getResource(anyString())).thenReturn(null);
    }

    private HiberniaModule fullModule() {
        return HiberniaModule.forPlugin(plugin)
                .dialogs(SampleDialog.class)
                .inputBinders(UpperBinder.class)
                .bedrockSupport(TestBedrock.class)
                .placeholders(TestPapi.class)
                .withoutDefaultsReconciliation()
                .build();
    }

    @Test
    void injector_bindsDialogTierMessageAndOverrides() {
        Injector injector = Guice.createInjector(fullModule());

        // Eager Message singleton constructed from the bundled-default bootstrap.
        assertNotNull(injector.getInstance(Message.class));

        // Bedrock + Papi overrides resolve to the configured implementations.
        assertInstanceOf(TestBedrock.class, injector.getInstance(BedrockSupport.class));
        assertInstanceOf(TestPapi.class, injector.getInstance(PapiSupport.class));

        // The whole dialog tier is constructible from the module alone.
        assertNotNull(injector.getInstance(
                io.paradaux.hibernia.framework.usher.DialogManager.class));
    }
}
