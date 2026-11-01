package skidmyau.management;

import skidmyau.enums.ChatColors;

import java.awt.*;
import java.io.File;

public class FriendManager extends PlayerFileManager {
    public FriendManager() {
        super(new File("./config/SkidMyau/", "friends.txt"), new Color(ChatColors.DARK_GREEN.toAwtColor()));
    }
}
