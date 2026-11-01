package skidmyau.command.commands;

import skidmyau.SkidMyau;
import skidmyau.command.Command;
import skidmyau.module.Module;
import skidmyau.util.ChatUtil;

import java.util.ArrayList;
import java.util.Arrays;

public class ListCommand extends Command {
    public ListCommand() {
        super(new ArrayList<>(Arrays.asList("list", "l", "modules", "skidmyau")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        if (!SkidMyau.moduleManager.modules.isEmpty()) {
            ChatUtil.sendFormatted(String.format("%sModules:&r", SkidMyau.clientName));
            for (Module module : SkidMyau.moduleManager.modules.values()) {
                ChatUtil.sendFormatted(String.format("%s»&r %s&r", module.isHidden() ? "&8" : "&7", module.formatModule()));
            }
        }
    }
}
