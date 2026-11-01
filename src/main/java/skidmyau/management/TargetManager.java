package skidmyau.management;

import skidmyau.enums.ChatColors;

import java.awt.*;
import java.io.File;

public class TargetManager extends PlayerFileManager {
    public TargetManager() {
        super(new File("./config/SkidMyau/", "enemies.txt"), new Color(ChatColors.DARK_RED.toAwtColor()));
    }
}
