package cool.lasthope.mjolnir;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class MjolnirPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(new MjolnirListener(this), this);
        getLogger().info("Mjolnir plugin enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("Mjolnir plugin disabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("givemjolnir")) return false;

        Player target;

        if (args.length == 0) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Usage: /givemjolnir <player>");
                return true;
            }
            target = (Player) sender;
        } else {
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage("Player not found: " + args[0]);
                return true;
            }
        }

        target.getInventory().addItem(MjolnirListener.createMjolnir());
        sender.sendMessage("Mjolnir given to " + target.getName() + ".");
        return true;
    }
}
