package com.botmaker.plugin.host;

/**
 * A companion plugin declared on the project's classpath could not be made to work.
 *
 * <p><b>Checked, and that is the whole design.</b> The one promise this module makes about out-of-process
 * plugins is that a broken one cannot stop a project opening — a process that will not start, will not
 * answer, or answers nonsense has to become a line in the status bar rather than a dialog nobody can
 * dismiss. A checked exception is how the compiler makes a host decide what to do about that, instead of an
 * unchecked one that a host discovers it forgot at the worst moment.
 *
 * <p>It is thrown only from {@link ProcessPlugin#launch}. Once a plugin has handshaked it never comes back:
 * a plugin that dies later is supervised, restarted once, and then reported through the failure reporter,
 * because by then the host has drawn its buttons and there is no call left to fail.
 *
 * @see ProcessPlugin
 */
public class CompanionLaunchException extends Exception {

    private final String pluginId;

    /**
     * @param pluginId the descriptor's id — the plugin the host could not start, which is the one thing a
     *                 diagnostic must always be able to name
     * @param message  what went wrong, phrased for a user's status bar rather than for a stack trace
     */
    public CompanionLaunchException(String pluginId, String message) {
        this(pluginId, message, null);
    }

    public CompanionLaunchException(String pluginId, String message, Throwable cause) {
        super(message, cause);
        this.pluginId = pluginId == null ? "" : pluginId;
    }

    /** The plugin that failed, for attributing the failure without parsing {@link #getMessage()}. */
    public String pluginId() {
        return pluginId;
    }
}
