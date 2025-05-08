package com.VeloDynamic.veloDynamic;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;

import net.kyori.adventure.text.Component;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationOptions;
import org.spongepowered.configurate.loader.ConfigurationLoader;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;
import org.bstats.velocity.Metrics;


@Plugin(id = "velodynamic", name = "VeloDynamic", version = "1.0")
public class VeloDynamic {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final Metrics.Factory metricsFactory;
    private ConfigurationLoader<CommentedConfigurationNode> configLoader;
    private CommentedConfigurationNode config;
    private final Map<String, ServerInfo> dynamicServers = new ConcurrentHashMap<>();

    @Inject
    public VeloDynamic(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory, Metrics.Factory metricsFactory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.metricsFactory = metricsFactory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        // Initialize bStats metrics
        int pluginId = 25624; // Replace with your actual bStats plugin ID
        Metrics metrics = metricsFactory.make(this, pluginId);
    }
    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        try {
            Path configFile = dataDirectory.resolve("config.yml");
            if (!Files.exists(configFile)) {
                try {
                    // Ensure the directory exists
                    Files.createDirectories(configFile.getParent());

                    try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                        Files.copy(in, configFile);
                    }
                } catch (IOException e) {
                    logger.error("Failed to load or create config.yml", e);
                }
            }


            configLoader = YamlConfigurationLoader.builder()
                    .path(configFile)
                    .defaultOptions(ConfigurationOptions.defaults())
                    .build();
            config = configLoader.load();

            Optional.ofNullable(config.node("servers").childrenMap()).ifPresent(servers -> {
                servers.forEach((key, node) -> {
                    String name = key.toString();
                    String address = node.getString();
                    if (address != null && address.contains(":")) {
                        String[] parts = address.split(":");
                        ServerInfo info = new ServerInfo(name,
                                new InetSocketAddress(parts[0], Integer.parseInt(parts[1])));
                        proxy.registerServer(info);
                        dynamicServers.put(name, info);
                        logger.info("Loaded server from config: {} -> {}", name, address);
                    }
                });
            });
        } catch (IOException e) {
            logger.error("Failed to load config.yml", e);
        }

        proxy.getCommandManager().register("dynamic", new DynamicCommand());
        logger.info("VeloDynamic initialized.");
    }

    private void saveServerToConfig(String name, String host, int port) throws SerializationException {
        config.node("servers", name).set(String.class, host + ":" + port);
        try {
            configLoader.save(config);
        } catch (IOException e) {
            logger.error("Failed to save server to config", e);
        }
    }
    private void removefromServerToConfig(String name) throws SerializationException {
        // Check if the server entry exists under "servers"
        if (config.node("servers", name).virtual()) {
            logger.warn("Server {} does not exist in config, skipping removal", name);
            return;
        }

        // Remove the server entry under "servers"
        config.node("servers").removeChild(name);

        // Save the config after removing the server
        try {
            configLoader.save(config);
        } catch (IOException e) {
            logger.error("Failed to save server to config", e);
        }
    }

    public InetSocketAddress getServerAddress(ProxyServer proxy, String serverName) {
        Optional<RegisteredServer> serverOpt = proxy.getServer(serverName);
        if (serverOpt.isPresent()) {
            return serverOpt.get().getServerInfo().getAddress(); // returns InetSocketAddress
        } else {
            return null; // or throw an error / handle it another way
        }
    }

    public class DynamicCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            String[] args = invocation.arguments();
            CommandSource source = invocation.source();

            if (args[0].equalsIgnoreCase("add")) {

                String name = args[1];
                String[] addressParts = args[2].split(":");
                if (addressParts.length != 2) {
                    source.sendMessage(Component.text("Invalid address format. Use host:port."));
                    return;
                }

                String host = addressParts[0];
                int port;
                try {
                    port = Integer.parseInt(addressParts[1]);
                } catch (NumberFormatException e) {
                    source.sendMessage(Component.text("Invalid port number."));
                    return;
                }

                ServerInfo serverInfo = new ServerInfo(name, new InetSocketAddress(host, port));
                if (proxy.getAllServers().stream().noneMatch(s -> s.getServerInfo().getName().equalsIgnoreCase(name))) {
                    proxy.registerServer(serverInfo);
                    dynamicServers.put(name, serverInfo);
                    if (!name.startsWith("player-server-")) {
                        try {
                            saveServerToConfig(name, host, port);
                        } catch (SerializationException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    source.sendMessage(Component.text("Server " + name + " added successfully!"));
                    logger.info("Server {} added at {}:{}", name, host, port);
                } else {
                    source.sendMessage(Component.text("Failed to add server. Name may already exist."));
                }
            } else {
                if (args[0].equalsIgnoreCase("remove")) {
                    String name = args[1];

                    Optional<RegisteredServer> registered = proxy.getServer(name);
                    if (registered.isPresent()) {
                        RegisteredServer server = registered.get();
                        ServerInfo info = server.getServerInfo();

                        // Fetch the fallback server from config
                        String fallbackServerName = config.node("fallback-server").getString();
                        if (fallbackServerName == null || fallbackServerName.isEmpty()) {
                            source.sendMessage(Component.text("No fallback server configured."));
                            return;
                        }

                        Optional<RegisteredServer> fallbackServerOpt = proxy.getServer(fallbackServerName);
                        if (!fallbackServerOpt.isPresent()) {
                            source.sendMessage(Component.text("Fallback server not found."));
                            return;
                        }

                        RegisteredServer fallbackserver = fallbackServerOpt.get();

                        // Kick players from the server before removing it and send them to the fallback server
                        for (Player player : server.getPlayersConnected()) {
                            // Use fireAndForget to immediately attempt the connection
                            player.createConnectionRequest(fallbackserver).fireAndForget();
                            player.sendMessage(Component.text("You have been disconnected because the server is being removed. You are being sent back to the main server."));
                        }

                        // Unregister the server after kicking the players
                        proxy.unregisterServer(info);

                        dynamicServers.remove(name);
                        if (!name.startsWith("player-server-")) {
                            try {
                                removefromServerToConfig(name);
                            } catch (SerializationException e) {
                                throw new RuntimeException(e);
                            }
                        }
                        source.sendMessage(Component.text("Server " + name + " removed successfully!"));
                        logger.info("Server {} removed at {}", name, info.getAddress());
                    } else {
                        source.sendMessage(Component.text("Server not found or not registered."));
                    }
                } else {
                    source.sendMessage(Component.text("Usage: /dynamic add/remove <name> <host:port>"));
                    return;
                }
            }
        }
    }
}