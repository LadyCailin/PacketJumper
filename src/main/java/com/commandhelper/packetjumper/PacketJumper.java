package com.commandhelper.packetjumper;

import com.comphenix.protocol.ProtocolLibrary;
import com.laytonsmith.PureUtilities.Common.FileWriteMode;
import com.laytonsmith.PureUtilities.Common.ReflectionUtils;
import com.laytonsmith.PureUtilities.SimpleVersion;
import com.laytonsmith.PureUtilities.Version;
import com.laytonsmith.PureUtilities.Web.RequestSettings;
import com.laytonsmith.PureUtilities.Web.WebUtility;
import com.laytonsmith.PureUtilities.ZipReader;
import com.laytonsmith.abstraction.enums.MCVersion;
import com.laytonsmith.commandhelper.CommandHelperFileLocations;
import com.laytonsmith.commandhelper.CommandHelperPlugin;
import com.laytonsmith.core.Static;
import com.laytonsmith.core.extensions.AbstractExtension;
import com.laytonsmith.core.extensions.MSExtension;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.fabricmc.mappingio.format.tiny.Tiny2FileReader;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

/**
 *
 */
@MSExtension("PacketJumper")
public class PacketJumper extends AbstractExtension {

	private Thread protocolThread;

    @Override
    public Version getVersion() {
        return new SimpleVersion(1, 0, 0);
    }

	@Override
	public void onLoad() {
		protocolThread = new Thread("PacketJumperLoader") {
			@Override
			public void run() {
				try {
					PacketJumper.Startup();
				} catch(IOException ex) {
					Logger.getLogger(CommandHelperPlugin.class.getName()).log(Level.SEVERE, null, ex);
				}
			}
		};
		protocolThread.start();
	}

	@Override
	public void onStartup() {
		if(protocolThread.isAlive()) {
			Logger.getLogger(PacketJumper.class.getName()).log(Level.INFO, "Waiting for protocol mapping to complete...");
			try {
				protocolThread.join();
			} catch (InterruptedException ex) {
				Logger.getLogger(PacketJumper.class.getName()).log(Level.SEVERE, null, ex);
			}
		}
	}

	@Override
	public void onShutdown() {
		PacketJumper.Shutdown();
	}



	/**
	 * The current server mapping type.
	 */
	private static int serverType = -1;

	/**
	 * A mapping from minecraft versions to takenaka urls.
	 */
	private static final Map<Version, String> VERSION_MAP = new HashMap<>() {
		{
			put(MCVersion.MC1_20_4, "https://repo.screamingsandals.org/releases/me/kcra/takenaka/mappings/1.8.8+1.20.4/mappings-1.8.8+1.20.4.jar");
		}
	};

	private static final MemoryMappingTree MINECRAFT_MAPPINGS = new MemoryMappingTree();
	private static int spigotNamespace;
	private static int mojangNamespace;

	private static Optional<PacketEventNotifier> packetEventNotifier = Optional.empty();

	/**
	 * Returns the best url for the current version of minecraft, or null if this version is not supported. Reads from
	 * the settings if applicable.
	 *
	 * @return
	 */
	private static String GetUrl() {
		Version currentVersion = Static.getServer().getMinecraftVersion();
		Version bestCandidate = null;
		for(Version v : VERSION_MAP.keySet()) {
			// Get the highest version that supports the current version.
			if(v.lt(bestCandidate)) {
				continue;
			}
			if(v.gte(currentVersion)) {
				bestCandidate = v;
			}
		}
		return VERSION_MAP.get(bestCandidate);
	}

	public static void Startup() throws MalformedURLException, IOException {
		long time = System.currentTimeMillis();
		boolean downloading = false;
		File protocolLibCache = new File(CommandHelperFileLocations.getDefault().getCacheDirectory(), "ProtocolLib");
		URL url = new URL(GetUrl());
		String[] parts = url.getPath().split("/");
		String filename = parts[parts.length - 1];
		protocolLibCache.mkdirs();
		File mappingsJar = new File(protocolLibCache, filename);
		if(!mappingsJar.exists()) {
			downloading = true;
			RequestSettings requestSettings = new RequestSettings()
					.setDownloadTo(mappingsJar)
					.setDownloadStrategy(FileWriteMode.SAFE_WRITE)
					.setBlocking(true);
			WebUtility.GetPage(url, requestSettings);
		}
		Version currentVersion = Static.getServer().getMinecraftVersion();
		String mcVersion = currentVersion.getMajor() + "." + currentVersion.getMinor() + "." + currentVersion.getSupplemental();
		ZipReader tinyFileReader = new ZipReader(new File(mappingsJar, mcVersion + ".tiny"));
		Reader reader = new BufferedReader(new InputStreamReader(tinyFileReader.getInputStream(), StandardCharsets.UTF_8));
		Tiny2FileReader.read(reader, MINECRAFT_MAPPINGS);
		spigotNamespace = MINECRAFT_MAPPINGS.getNamespaceId("spigot");
		mojangNamespace = MINECRAFT_MAPPINGS.getNamespaceId("mojang");
		long ms = System.currentTimeMillis() - time;
		Static.getLogger().log(Level.INFO,
				"Loading {0}mappings took {1}ms",
				new Object[]{
					downloading ? "and downloading " : "",
					ms
				});
		packetEventNotifier = Optional.of(new PacketEventNotifier(CommandHelperPlugin.self, ProtocolLibrary.getProtocolManager()));
	}

	public static void Shutdown() {
		packetEventNotifier.ifPresent(e -> e.unregister());
	}

	public static Optional<PacketEventNotifier> GetPacketEventNotifier() {
		return packetEventNotifier;
	}

	/**
	 * Returns the mapping tree, if loaded, null otherwise.
	 *
	 * @return
	 */
	public static MappingTree GetMappingTree() {
		return MINECRAFT_MAPPINGS;
	}

	/**
	 * Returns the server namespace. This value can be sent to the MappingTree to get the actual class that is running.
	 *
	 * @return
	 */
	public static int GetServerNamespace() {
		if(serverType == -1) {
			// Paper has 2 versions, "moj-mapped" and "spigot" mapped. As we support paper and also spigot, we need
			// to check which mapping version the server is running, and select the correct mapping segment.
			serverType = PacketJumper.GetMojangNamespace();
			// This class is named net.minecraft.network.protocol.status.ServerboundStatusRequestPacket in mojang mappings
			if(ReflectionUtils.classExists("net.minecraft.network.protocol.status.PacketStatusInStart")) {
				serverType = PacketJumper.GetSpigotNamespace();
			}
		}
		return serverType;
	}

	/**
	 * Returns the Spigot namespace. Generally, one shouls use GetServerNamespace() instead.
	 *
	 * @return
	 */
	public static int GetSpigotNamespace() {
		return spigotNamespace;
	}

	/**
	 * Returns the Mojang namespace. This should be used when sending and receiving names from users.
	 *
	 * @return
	 */
	public static int GetMojangNamespace() {
		return mojangNamespace;
	}
}
