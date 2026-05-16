package me.arrow.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class ArrowLoadEvent extends Event {

    private static final HandlerList handlers = new HandlerList();
    private final Object arrowInstance;

    public ArrowLoadEvent(Object arrowInstance) {
        this.arrowInstance = arrowInstance;
    }

    public Object getArrowInstance() {
        return arrowInstance;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
