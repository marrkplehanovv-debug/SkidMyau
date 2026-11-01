package skidmyau.module.modules;

import skidmyau.SkidMyau;
import skidmyau.enums.BlinkModules;
import skidmyau.event.EventTarget;
import skidmyau.event.types.EventType;
import skidmyau.event.types.Priority;
import skidmyau.events.LoadWorldEvent;
import skidmyau.events.TickEvent;
import skidmyau.module.Module;
import skidmyau.property.properties.IntProperty;
import skidmyau.property.properties.ModeProperty;

public class Blink extends Module {
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"DEFAULT", "PULSE"});
    public final IntProperty ticks = new IntProperty("ticks", 20, 0, 1200);

    public Blink() {
        super("Blink", false);
    }

    @EventTarget(Priority.LOWEST)
    public void onTick(TickEvent event) {
        if (this.isEnabled() && event.getType() == EventType.POST) {
            if (!SkidMyau.blinkManager.getBlinkingModule().equals(BlinkModules.BLINK)) {
                this.setEnabled(false);
            } else {
                if (this.ticks.getValue() > 0 && SkidMyau.blinkManager.countMovement() > (long) this.ticks.getValue()) {
                    switch (this.mode.getValue()) {
                        case 0:
                            this.setEnabled(false);
                            break;
                        case 1:
                            SkidMyau.blinkManager.setBlinkState(false, BlinkModules.BLINK);
                            SkidMyau.blinkManager.setBlinkState(true, BlinkModules.BLINK);
                    }
                }
            }
        }
    }

    @EventTarget
    public void onWorldLoad(LoadWorldEvent event) {
        this.setEnabled(false);
    }

    @Override
    public void onEnabled() {
        SkidMyau.blinkManager.setBlinkState(false, SkidMyau.blinkManager.getBlinkingModule());
        SkidMyau.blinkManager.setBlinkState(true, BlinkModules.BLINK);
    }

    @Override
    public void onDisabled() {
        SkidMyau.blinkManager.setBlinkState(false, BlinkModules.BLINK);
    }
}
