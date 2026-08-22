package io.paradaux.hibernia.framework.guice;

import com.google.inject.AbstractModule;
import com.google.inject.TypeLiteral;
import com.google.inject.multibindings.Multibinder;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.commander.spi.ParameterResolver;
import io.paradaux.hibernia.framework.configurator.ConfigurationLoader;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.hibernia.framework.upgrade.DefaultsReconciler;
import io.paradaux.hibernia.framework.i18n.PapiSupport;
import io.paradaux.hibernia.framework.i18n.PlaceholderApiSupport;
import io.paradaux.hibernia.framework.usher.render.DialogRenderer;
import io.paradaux.hibernia.framework.usher.render.PaperDialogRenderer;
import io.paradaux.hibernia.framework.usher.spi.BedrockSupport;
import io.paradaux.hibernia.framework.usher.spi.DialogHandler;
import io.paradaux.hibernia.framework.usher.spi.InputBinder;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Framework-owned Guice module: binds everything the framework needs in one
 * place so consuming plugins no longer hand-roll a plugin module, a commander
 * module and a configuration-component loop.
 *
 * <p>Binds:</p>
 * <ul>
 *   <li>{@link JavaPlugin} / {@link Plugin} to the running plugin instance</li>
 *   <li>{@link ConfigurationLoader} plus every {@code @ConfigurationComponent}
 *       discovered in the configured packages, each as a singleton instance</li>
 *   <li>{@link Message} as an eager singleton (disable with
 *       {@link Builder#withoutMessages()} if the plugin bundles no
 *       {@code messages.properties})</li>
 *   <li>Multibinder sets for {@link CommandHandler}, {@link ParameterResolver},
 *       Bukkit {@link Listener}, {@link DialogHandler} and {@link InputBinder}
 *       implementations</li>
 *   <li>{@link DialogRenderer} to the Paper-backed renderer, plus an optional
 *       {@link BedrockSupport} when {@link Builder#bedrockSupport(Class)} is set</li>
 * </ul>
 *
 * <p>Typical bootstrap:</p>
 * <pre>
 * {@literal @}Override
 * public void onEnable() {
 *     HiberniaModule hibernia = HiberniaModule.forPlugin(this)
 *             .scanConfiguration("net.example.myplugin.model.config")
 *             .handlers(EconomyCommands.class, AdminCommands.class)
 *             .resolvers(FirmPlayerResolver.class)
 *             .listeners(JoinListener.class)
 *             .build();
 *
 *     // Typed config is available before the injector exists, e.g. for a
 *     // DatabaseModule that needs connection settings:
 *     DatabaseConfiguration db = hibernia.configuration(DatabaseConfiguration.class);
 *
 *     Injector injector = Guice.createInjector(hibernia, new DatabaseModule(db), new ServicesModule());
 *     injector.getInstance(CommandManager.class).registerAll();
 *     injector.getInstance(ListenerManager.class).registerAll();
 * }
 * </pre>
 */
public final class HiberniaModule extends AbstractModule {

    private final JavaPlugin plugin;
    private final ConfigurationLoader configurationLoader;
    private final List<Class<? extends CommandHandler>> handlers;
    private final List<Class<? extends ParameterResolver<?>>> resolvers;
    private final List<Class<? extends Listener>> listeners;
    private final List<Class<? extends DialogHandler>> dialogs;
    private final List<Class<? extends InputBinder<?>>> inputBinders;
    private final Class<? extends BedrockSupport> bedrockSupport;
    private final Class<? extends PapiSupport> papiSupport;
    private final boolean bindMessage;

    private HiberniaModule(Builder builder) {
        this.plugin = builder.plugin;
        this.handlers = List.copyOf(builder.handlers);
        this.resolvers = List.copyOf(builder.resolvers);
        this.listeners = List.copyOf(builder.listeners);
        this.dialogs = List.copyOf(builder.dialogs);
        this.inputBinders = List.copyOf(builder.inputBinders);
        this.bedrockSupport = builder.bedrockSupport;
        this.papiSupport = builder.papiSupport;
        this.bindMessage = builder.bindMessage;

        // Fill in any config.yml / messages.properties keys a new release added, before the
        // ConfigurationLoader (saveDefaultConfig) and Message (ensureDefaultFile + reload) below
        // read those files — so they load the freshly merged content.
        if (builder.reconcileDefaults) {
            DefaultsReconciler.reconcile(plugin);
        }

        this.configurationLoader = new ConfigurationLoader(plugin);
        builder.configurationPackages.forEach(configurationLoader::scanPackage);
    }

    public static Builder forPlugin(JavaPlugin plugin) {
        return new Builder(plugin);
    }

    /**
     * Typed access to a loaded {@code @ConfigurationComponent} before the
     * injector exists — e.g. to construct another module that needs settings.
     *
     * @throws IllegalStateException when no such component was loaded
     */
    public <T> T configuration(Class<T> componentClass) {
        return configurationLoader.getComponent(componentClass);
    }

    @Override
    protected void configure() {
        bind(JavaPlugin.class).toInstance(plugin);
        bind(Plugin.class).toInstance(plugin);
        bind(ConfigurationLoader.class).toInstance(configurationLoader);

        for (Map.Entry<Class<?>, Object> entry : configurationLoader.getComponents().entrySet()) {
            bindComponent(entry.getKey(), entry.getValue());
        }

        if (bindMessage) {
            bind(Message.class).asEagerSingleton();
        }

        // PlaceholderAPI bridge for Message %token% resolution. The default is a reflective bridge that
        // no-ops when PlaceholderAPI isn't installed, so this is safe to bind unconditionally.
        bind(PapiSupport.class).to(papiSupport);

        Multibinder<CommandHandler> handlerBinder = Multibinder.newSetBinder(binder(), CommandHandler.class);
        handlers.forEach(h -> handlerBinder.addBinding().to(h));

        Multibinder<ParameterResolver<?>> resolverBinder =
                Multibinder.newSetBinder(binder(), new TypeLiteral<ParameterResolver<?>>() {});
        resolvers.forEach(r -> resolverBinder.addBinding().to(r));

        Multibinder<Listener> listenerBinder = Multibinder.newSetBinder(binder(), Listener.class);
        listeners.forEach(l -> listenerBinder.addBinding().to(l));

        // Dialog tier. The set binders are created unconditionally so DialogManager
        // is always injectable (with empty sets when the plugin uses no dialogs).
        Multibinder<DialogHandler> dialogBinder = Multibinder.newSetBinder(binder(), DialogHandler.class);
        dialogs.forEach(d -> dialogBinder.addBinding().to(d));

        Multibinder<InputBinder<?>> inputBinderBinder =
                Multibinder.newSetBinder(binder(), new TypeLiteral<InputBinder<?>>() {});
        inputBinders.forEach(b -> inputBinderBinder.addBinding().to(b));

        bind(DialogRenderer.class).to(PaperDialogRenderer.class);
        if (bedrockSupport != null) {
            bind(BedrockSupport.class).to(bedrockSupport);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void bindComponent(Class<T> key, Object value) {
        bind(key).toInstance((T) value);
    }

    /** Fluent configuration for {@link HiberniaModule}. */
    public static final class Builder {
        private final JavaPlugin plugin;
        private final List<String> configurationPackages = new ArrayList<>();
        private final List<Class<? extends CommandHandler>> handlers = new ArrayList<>();
        private final List<Class<? extends ParameterResolver<?>>> resolvers = new ArrayList<>();
        private final List<Class<? extends Listener>> listeners = new ArrayList<>();
        private final List<Class<? extends DialogHandler>> dialogs = new ArrayList<>();
        private final List<Class<? extends InputBinder<?>>> inputBinders = new ArrayList<>();
        private Class<? extends BedrockSupport> bedrockSupport;
        private Class<? extends PapiSupport> papiSupport = PlaceholderApiSupport.class;
        private boolean bindMessage = true;
        private boolean reconcileDefaults = true;

        private Builder(JavaPlugin plugin) {
            this.plugin = Objects.requireNonNull(plugin, "plugin");
        }

        /** Scan a package for {@code @ConfigurationComponent} classes. Repeatable. */
        public Builder scanConfiguration(String packageName) {
            configurationPackages.add(Objects.requireNonNull(packageName, "packageName"));
            return this;
        }

        /** Command handler classes to bind into the {@code Set<CommandHandler>} multibinder. */
        @SafeVarargs
        public final Builder handlers(Class<? extends CommandHandler>... classes) {
            handlers.addAll(List.of(classes));
            return this;
        }

        /** Parameter resolver classes to bind into the {@code Set<ParameterResolver<?>>} multibinder. */
        @SafeVarargs
        public final Builder resolvers(Class<? extends ParameterResolver<?>>... classes) {
            resolvers.addAll(List.of(classes));
            return this;
        }

        /** Bukkit listener classes to bind into the {@code Set<Listener>} multibinder. */
        @SafeVarargs
        public final Builder listeners(Class<? extends Listener>... classes) {
            listeners.addAll(List.of(classes));
            return this;
        }

        /** Dialog handler classes to bind into the {@code Set<DialogHandler>} multibinder. */
        @SafeVarargs
        public final Builder dialogs(Class<? extends DialogHandler>... classes) {
            dialogs.addAll(List.of(classes));
            return this;
        }

        /** Custom {@link InputBinder} classes to bind into the {@code Set<InputBinder<?>>} multibinder. */
        @SafeVarargs
        public final Builder inputBinders(Class<? extends InputBinder<?>>... classes) {
            inputBinders.addAll(List.of(classes));
            return this;
        }

        /**
         * Bind a {@link BedrockSupport} implementation (e.g. Floodgate-backed) used by the dialog tier to
         * detect Bedrock viewers. When unset, everyone is treated as a Java player.
         */
        public Builder bedrockSupport(Class<? extends BedrockSupport> impl) {
            this.bedrockSupport = Objects.requireNonNull(impl, "impl");
            return this;
        }

        /**
         * Override the {@link PapiSupport} used by {@link Message} for {@code %token%} resolution. Defaults
         * to {@link PlaceholderApiSupport} (bridges to PlaceholderAPI when installed, no-ops otherwise).
         * Bind {@code PapiSupport.NONE}'s class or a custom implementation to change it.
         */
        public Builder placeholders(Class<? extends PapiSupport> impl) {
            this.papiSupport = Objects.requireNonNull(impl, "impl");
            return this;
        }

        /**
         * Skip binding {@link Message}. Use when the plugin does not bundle a
         * {@code messages.properties} resource (the Message constructor would
         * fail trying to save the default file).
         */
        public Builder withoutMessages() {
            this.bindMessage = false;
            return this;
        }

        /**
         * Skip the on-upgrade reconciliation that fills new jar-default keys into the
         * operator's existing {@code config.yml} / {@code messages.properties}
         * (see {@link DefaultsReconciler}). Reconciliation is additive and on by default.
         */
        public Builder withoutDefaultsReconciliation() {
            this.reconcileDefaults = false;
            return this;
        }

        /** Build the module; configuration packages are scanned eagerly here. */
        public HiberniaModule build() {
            return new HiberniaModule(this);
        }
    }
}
