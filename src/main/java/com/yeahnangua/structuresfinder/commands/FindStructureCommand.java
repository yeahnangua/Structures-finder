package com.yeahnangua.structuresfinder.commands;

import com.yeahnangua.structuresfinder.StructuresFinder;
import com.yeahnangua.structuresfinder.data.StructureData;
import com.yeahnangua.structuresfinder.data.StructureDataLoader;
import com.yeahnangua.structuresfinder.map.ExplorerMapCreator;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.map.MapView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Command handler for /findstructure command.
 * Usage: /findstructure <world> <player> [type] [scale] [--notclear]
 * Scale: 0=closest, 1=close, 2=normal, 3=far, 4=farthest
 * --notclear: Only select structures that haven't been cleared
 */
public class FindStructureCommand implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Check permission
        if (!sender.hasPermission("structuresfinder.use")) {
            sender.sendMessage("§cYou don't have permission to use this command!");
            return true;
        }

        // Check arguments - need at least world and player
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /findstructure <world> <player> [type] [scale] [--notclear]");
            sender.sendMessage("§7Scale: 0=closest, 1=close, 2=normal, 3=far, 4=farthest");
            sender.sendMessage("§7--notclear: Only select structures that haven't been cleared");
            sender.sendMessage("§7Available worlds: §f" + String.join(", ", StructureDataLoader.getAvailableWorlds()));
            return true;
        }

        // Check for --notclear flag anywhere in args
        boolean notCleared = false;
        List<String> filteredArgs = new ArrayList<>();
        for (String arg : args) {
            if (arg.equalsIgnoreCase("--notclear") || arg.equalsIgnoreCase("-nc")) {
                notCleared = true;
            } else {
                filteredArgs.add(arg);
            }
        }
        args = filteredArgs.toArray(new String[0]);

        String worldName = args[0];
        String playerName = args[1];

        // Get target player
        Player targetPlayer = Bukkit.getPlayer(playerName);
        if (targetPlayer == null) {
            sender.sendMessage("§cPlayer not found: " + playerName);
            return true;
        }

        // Parse structure type (3rd argument, if not a number)
        String structureType = args.length > 2 && !args[2].matches("\\d") ? args[2].toUpperCase() : null;

        // Parse scale parameter (can be 3rd or 4th argument)
        MapView.Scale scale = MapView.Scale.NORMAL; // default
        String scaleArg = null;
        if (args.length > 3) {
            scaleArg = args[3];
        } else if (args.length > 2 && args[2].matches("\\d")) {
            scaleArg = args[2];
        }

        if (scaleArg != null) {
            scale = parseScale(scaleArg);
            if (scale == null) {
                sender.sendMessage("§cInvalid scale! Use 0-4 (0=closest, 4=farthest)");
                return true;
            }
        }

        // Check if world has structure data
        List<String> availableWorlds = StructureDataLoader.getAvailableWorlds();
        if (!availableWorlds.contains(worldName)) {
            sender.sendMessage("§cNo structure data found for world: " + worldName);
            sender.sendMessage("§7Available worlds: §f" + String.join(", ", availableWorlds));
            return true;
        }

        // Get random structure
        StructureData structure;
        if (structureType != null) {
            structure = StructureDataLoader.getRandomStructureByType(worldName, structureType, notCleared);
            if (structure == null) {
                String filterMsg = notCleared ? " (not cleared)" : "";
                sender.sendMessage("§cNo structures of type '" + structureType + "'" + filterMsg + " found in world: " + worldName);
                Set<String> types = StructureDataLoader.getAvailableTypes(worldName);
                sender.sendMessage("§7Available types: §f" + String.join(", ", types));
                return true;
            }
        } else {
            structure = StructureDataLoader.getRandomStructure(worldName, notCleared);
            if (structure == null) {
                String filterMsg = notCleared ? " (not cleared)" : "";
                sender.sendMessage("§cNo structures" + filterMsg + " found in world: " + worldName);
                return true;
            }
        }

        // Create and give the explorer map to target player
        boolean success = ExplorerMapCreator.createAndGiveMap(targetPlayer, structure, scale);

        if (success) {
            StructuresFinder plugin = StructuresFinder.getInstance();

            // Send messages to target player using config
            String receivedMsg = plugin.getConfigString("messages.received");
            String pointingMsg = plugin.getConfigString("messages.pointing-to");
            String typeMsg = plugin.getConfigString("messages.type");
            String scaleMsg = plugin.getConfigString("messages.scale");

            targetPlayer.sendMessage(ExplorerMapCreator.replacePlaceholders(receivedMsg, structure, scale));
            targetPlayer.sendMessage(ExplorerMapCreator.replacePlaceholders(pointingMsg, structure, scale));
            targetPlayer.sendMessage(ExplorerMapCreator.replacePlaceholders(typeMsg, structure, scale));
            targetPlayer.sendMessage(ExplorerMapCreator.replacePlaceholders(scaleMsg, structure, scale));

            // Notify sender if different from target
            if (sender != targetPlayer) {
                String sentMsg = plugin.getConfigString("messages.sent-to");
                sentMsg = sentMsg.replace("%player%", targetPlayer.getName());
                sender.sendMessage(ExplorerMapCreator.replacePlaceholders(sentMsg, structure, scale));
                sender.sendMessage(ExplorerMapCreator.replacePlaceholders(pointingMsg, structure, scale));
                sender.sendMessage(ExplorerMapCreator.replacePlaceholders(typeMsg, structure, scale));
            }
        } else {
            sender.sendMessage("§cFailed to create explorer map. Is the world loaded?");
        }

        return true;
    }

    private MapView.Scale parseScale(String input) {
        try {
            int level = Integer.parseInt(input);
            return switch (level) {
                case 0 -> MapView.Scale.CLOSEST;
                case 1 -> MapView.Scale.CLOSE;
                case 2 -> MapView.Scale.NORMAL;
                case 3 -> MapView.Scale.FAR;
                case 4 -> MapView.Scale.FARTHEST;
                default -> null;
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> completions = new ArrayList<>();

        // Check if --notclear is already used
        boolean hasNotClear = false;
        for (String arg : args) {
            if (arg.equalsIgnoreCase("--notclear") || arg.equalsIgnoreCase("-nc")) {
                hasNotClear = true;
                break;
            }
        }

        if (args.length == 1) {
            // Complete world names
            String partial = args[0].toLowerCase();
            for (String world : StructureDataLoader.getAvailableWorlds()) {
                if (world.toLowerCase().startsWith(partial)) {
                    completions.add(world);
                }
            }
        } else if (args.length == 2) {
            // Complete player names
            String partial = args[1].toLowerCase();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(partial)) {
                    completions.add(player.getName());
                }
            }
        } else if (args.length == 3) {
            // Complete structure types or scale
            String worldName = args[0];
            String partial = args[2].toLowerCase();
            Set<String> types = StructureDataLoader.getAvailableTypes(worldName);
            for (String type : types) {
                if (type.toLowerCase().startsWith(partial)) {
                    completions.add(type);
                }
            }
            // Also suggest scale numbers
            for (int i = 0; i <= 4; i++) {
                if (String.valueOf(i).startsWith(partial)) {
                    completions.add(String.valueOf(i));
                }
            }
            // Suggest --notclear
            if (!hasNotClear && "--notclear".startsWith(partial)) {
                completions.add("--notclear");
            }
        } else if (args.length == 4) {
            // Complete scale
            String partial = args[3].toLowerCase();
            for (int i = 0; i <= 4; i++) {
                if (String.valueOf(i).startsWith(partial)) {
                    completions.add(String.valueOf(i));
                }
            }
            // Suggest --notclear
            if (!hasNotClear && "--notclear".startsWith(partial)) {
                completions.add("--notclear");
            }
        } else if (args.length >= 5) {
            // Suggest --notclear if not already used
            String partial = args[args.length - 1].toLowerCase();
            if (!hasNotClear && "--notclear".startsWith(partial)) {
                completions.add("--notclear");
            }
        }

        return completions;
    }
}
