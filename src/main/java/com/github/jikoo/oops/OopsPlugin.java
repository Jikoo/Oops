package com.github.jikoo.oops;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * A plugin adding a command used to fix mistakes in the last command issued.
 * 
 * @author Jikoo
 */
public class OopsPlugin extends JavaPlugin implements Listener {

	// The reason for not using UUIDs is that this storage is not persistent and should support console.
	private final Map<String, String> oopsCommands = new HashMap<>();
	private final Map<String, Command> overridden = new HashMap<>();
	private final List<String> reusable = new ArrayList<>();

	private SimpleCommandMap cmdMap;
	private String oopsMessage;
	private List<String> aliases;

	@Override
	public void onEnable() {
		try {
			Method getCommandMap = this.getServer().getClass().getMethod("getCommandMap");
			cmdMap = (SimpleCommandMap) getCommandMap.invoke(getServer());
		} catch (IllegalArgumentException | IllegalAccessException | SecurityException
				| NoSuchMethodException | InvocationTargetException e) {
			this.getLogger().severe("Error fetching SimpleCommandMap from CraftServer, plugin cannot function.");
			e.printStackTrace();
			this.getServer().getPluginManager().disablePlugin(this);
			return;
		}

		this.getServer().getPluginManager().registerEvents(this, this);

		this.load();
	}

	private void load() {
		this.saveDefaultConfig();

		oopsMessage = ChatColor.translateAlternateColorCodes('&', this.getConfig().getString("lang.oops"));

		Command oops = this.getCommand("oops");
		this.aliases = new ArrayList<>(this.getAllAliases(oops));

		HashMap<String, Command> cmdMapKnownCommands;
		try {
			cmdMapKnownCommands = this.getInternalCommandMap();
		} catch (NoSuchFieldException | SecurityException | IllegalArgumentException
				| IllegalAccessException e) {
			this.getLogger().severe("Unable to modify SimpleCommandMap.knownCommands! No aliases will be registered!");
			e.printStackTrace();
			return;
		}

		String overrideFormat = "Overriding %s by %s. Aliases: %s";
		List<String> configAliases = this.getConfig().getStringList("aliases");
		oops.setAliases(configAliases);
		for (String alias : configAliases) {
			Command override = cmdMapKnownCommands.put(alias, oops);
			if (override == null) {
				continue;
			}
			overridden.put(alias, override);
			this.getLogger().info(String.format(overrideFormat, alias,
					override instanceof PluginIdentifiableCommand
							? ((PluginIdentifiableCommand) override).getPlugin().getName()
							: this.getServer().getVersion(),
					override.getAliases().toString()));
		}
	}

	@Override
	public void onDisable() {
		this.unload();
	}

	private void unload() {
		try {
			HashMap<String, Command> cmdMapKnownCommands = this.getInternalCommandMap();
			for (String alias :  this.getConfig().getStringList("aliases")) {
				cmdMapKnownCommands.remove(alias);
			}
			cmdMapKnownCommands.putAll(overridden);
		} catch (NoSuchFieldException | SecurityException | IllegalArgumentException
				| IllegalAccessException e) {
			// No need to alert twice if access failed.
			return;
		}
	}

	@SuppressWarnings("unchecked")
	private HashMap<String, Command> getInternalCommandMap() throws NoSuchFieldException,
			SecurityException, IllegalArgumentException, IllegalAccessException {
		if (cmdMap == null) {
			return null;
		}
		Field field = cmdMap.getClass().getDeclaredField("knownCommands");
		field.setAccessible(true);
		return (HashMap<String, Command>) field.get(cmdMap);
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length > 0 && sender.hasPermission("oops.reload") && args[0].equalsIgnoreCase("reload")) {
			this.unload();
			this.reloadConfig();
			this.load();
			sender.sendMessage("Oops configuration reloaded successfully!");
			return true;
		}
		if (oopsCommands.containsKey(sender.getName())) {
			this.getServer().dispatchCommand(sender, oopsCommands.remove(sender.getName()));
		}
		return true;
	}

	@EventHandler(ignoreCancelled = true)
	public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
		if (handleFailedCommand(event.getPlayer(), event.getMessage())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onServerCommand(ServerCommandEvent event) {
		if (handleFailedCommand(event.getSender(), event.getCommand())) {
			event.setCancelled(true);
		}
	}

	private boolean handleFailedCommand(CommandSender sender, String executed) {
		int space = executed.indexOf(' ');
		boolean slash = executed.length() > 0 && executed.charAt(0) == '/';
		String commandName = executed.substring(slash ? 1 : 0, space > 0 ? space : executed.length()).toLowerCase();

		String command = getMatchingCommand(sender, commandName);
		if (command == null) {
			// Valid or severely invalid command
			if (!aliases.contains(commandName) && oopsCommands.containsKey(sender.getName())) {
				oopsCommands.remove(sender.getName());
			}
			return false;
		}
		if (getConfig().getBoolean("instantly-correct")) {
			this.getServer().dispatchCommand(sender, command + executed.substring(space));
			return true;
		}
		sender.sendMessage(oopsMessage.replace("{0}", slash ? "/" : "").replace("{1}", command));
		if (aliases.contains(command)) {
			// Don't store /oops as anyone's /oops
			return true;
		}
		if (space > -1) {
			command += executed.substring(space);
		}
		oopsCommands.put(sender.getName(), command);
		return true;
	}

	private String getMatchingCommand(CommandSender sender, String commandName) {
		if (cmdMap.getCommand(commandName) != null) {
			// Valid command, nothing to oops.
			return null;
		}

		int matchLevel = Integer.MAX_VALUE;
		String correctCommandName = null;
		for (Command command : cmdMap.getCommands()) {
			String permission = command.getPermission();
			if (permission != null && !sender.hasPermission(permission)) {
				// Can't use the command, don't check.
				continue;
			}
			for (String alias : getAllAliases(command)) {
				int current = StringUtils.getLevenshteinDistance(commandName, alias);
				if (current == 0) {
					return null;
				}
				if (current < matchLevel) {
					matchLevel = current;
					correctCommandName = alias;
				}
			}
		}

		// Allow more fuzziness for longer commands
		if (matchLevel < (3 + correctCommandName.length() / 4)) {
			return correctCommandName;
		}
		return null;
	}

	private List<String> getAllAliases(Command command) {
		reusable.clear();
		reusable.addAll(command.getAliases());
		reusable.add(command.getName());
		return reusable;
	}
}
