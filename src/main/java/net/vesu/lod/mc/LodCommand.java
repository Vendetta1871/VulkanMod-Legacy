package net.vesu.lod.mc;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;

import java.util.List;

/** Client-side {@code /vesu lod ...} (AUDIT 5.18). */
final class LodCommand extends CommandBase {
    @Override
    public String getName() {
        return "vesu";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/vesu lod <status|reload|import current>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
        return true;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2 || !args[0].equals("lod")) throw new WrongUsageException(getUsage(sender));
        switch (args[1]) {
            case "status" -> {
                for (String l : LodSystem.status()) sender.sendMessage(new TextComponentString(l));
            }
            case "reload" -> {
                LodSystem.reload();
                sender.sendMessage(new TextComponentString("LOD: re-meshing"));
            }
            case "import" -> {
                if (args.length >= 3 && args[2].equals("current")) {
                    int n = LodSystem.importLoaded();
                    sender.sendMessage(new TextComponentString("LOD: importing " + n + " loaded chunks"));
                } else if (args.length >= 3 && args[2].equals("cancel")) {
                    LodSystem.cancelImport();
                    sender.sendMessage(new TextComponentString("LOD: import cancelled"));
                } else if (args.length >= 4 && args[2].equals("world")) {
                    String name = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
                    sender.sendMessage(new TextComponentString(LodSystem.importWorld(name)
                            ? "LOD: importing " + name : "LOD: no save named " + name));
                } else {
                    throw new WrongUsageException("/vesu lod import <current|world <name>|cancel>");
                }
            }
            default -> throw new WrongUsageException(getUsage(sender));
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args,
                                          net.minecraft.util.math.BlockPos pos) {
        if (args.length == 1) return getListOfStringsMatchingLastWord(args, "lod");
        if (args.length == 2) return getListOfStringsMatchingLastWord(args, "status", "reload", "import");
        if (args.length == 3 && args[1].equals("import")) return getListOfStringsMatchingLastWord(args, "current", "world", "cancel");
        return java.util.Collections.emptyList();
    }
}
