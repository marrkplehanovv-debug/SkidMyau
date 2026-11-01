package skidmyau.events;

import skidmyau.event.events.Event;
import skidmyau.event.types.EventType;

public class TickEvent implements Event {
    private final EventType type;

    public TickEvent(EventType type) {
        this.type = type;
    }

    public EventType getType() {
        return this.type;
    }
}
