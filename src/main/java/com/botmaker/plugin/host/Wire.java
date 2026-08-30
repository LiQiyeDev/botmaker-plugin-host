package com.botmaker.plugin.host;

import com.botmaker.plugin.api.EnabledWhen;
import com.botmaker.plugin.api.Region;
import com.botmaker.plugin.api.ThemeTokens;
import com.botmaker.plugin.api.ToolbarGroup;
import com.botmaker.plugin.protocol.WireColor;
import com.botmaker.plugin.protocol.WireRegion;
import com.botmaker.plugin.protocol.WireThemeTokens;
import com.botmaker.plugin.protocol.WireToolbarItem;

import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.paint.Color;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

/**
 * Contract records to wire records and back — the translation {@code botmaker-plugin-protocol} refuses to
 * do, gathered in one place so it is one file to check rather than a habit to maintain.
 *
 * <p><b>Why this is the host's job and not the protocol's.</b> The protocol module names no BotMaker type,
 * because the contract's own records do not all serialise: {@code ToolbarItem} carries a
 * {@code Consumer<ActionContext>} and two {@code Supplier<String>}s. Importing only the ones that
 * <em>would</em> cross leaves the rule "some contract types cross and some don't", which nobody can apply.
 * So the wire records are a deliberate parallel and somebody has to map them; the host is the only party
 * that has both, which makes it the only party that can.
 *
 * <p><b>The cost is that a record added to either side must be added to the other by hand</b>, and nothing
 * makes that a compile error. The mitigation is that the mapping is here, in one class, with a test: a
 * component added to {@code ThemeTokens} and not to {@code WireThemeTokens} does not compile <em>here</em>,
 * because the constructor call below is positional.
 *
 * <p>Two directions with different tolerances, and the asymmetry is deliberate. Host to plugin is
 * total — every contract value has a wire spelling. Plugin to host is <b>lenient</b>: a plugin built
 * against a newer protocol may name a {@code ToolbarGroup} this build has never heard of, and that has to
 * be one dropped button with a warning rather than a parse failure costing the plugin every button it has.
 */
final class Wire {

    /**
     * The contract's theme, as it crosses.
     *
     * <p>Positional and deliberately not defensive: a component added to one record and not the other is a
     * compile error on this line, which is the only enforcement the parallel records get.
     */
    static WireThemeTokens themeTokens(ThemeTokens tokens) {
        ThemeTokens t = tokens == null ? ThemeTokens.DEFAULT : tokens;
        return new WireThemeTokens(
                t.dark(), t.background(), t.text(), t.accent(), t.hover(),
                t.error(), t.warning(), t.success(), t.fontFamily(), t.monoFamily(), t.fontSize());
    }

    /** A chosen rectangle. A {@code null} region is a cancel — see {@link WireRegion#cancelled()}. */
    static WireRegion region(Region region) {
        return region == null
                ? WireRegion.CANCELLED
                : WireRegion.of(region.x(), region.y(), region.width(), region.height());
    }

    /**
     * A sampled colour, converted from JavaFX's 0–1 doubles to the eight-bit channels a pixel actually has.
     *
     * <p>Rounded rather than truncated: {@code 0.999} is 255, and truncation would make a white pixel
     * sampled off a real screen report 254, which is the kind of error that survives a code review and
     * fails a colour match.
     */
    static WireColor color(Color color) {
        if (color == null) return WireColor.CANCELLED;
        return WireColor.of(channel(color.getRed()), channel(color.getGreen()), channel(color.getBlue()));
    }

    /**
     * One frame as encoded PNG bytes, or an empty array when there is nothing to send.
     *
     * <p>PNG rather than a raw buffer with a width and a height beside it: the bytes have to be framed
     * either way, and a self-describing format is one every language on the other end already decodes
     * without being told the pixel order this JVM happened to use.
     *
     * <p>Lossless rather than JPEG, because the caller may be matching colours against it. This is a
     * single frame on request, not the pilot's video stream — that has its own encoder and its own reasons.
     */
    static byte[] png(Image image) {
        if (image == null) return new byte[0];
        PixelReader pixels = image.getPixelReader();
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        if (pixels == null || width <= 0 || height <= 0) return new byte[0];

        BufferedImage buffer = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                buffer.setRGB(x, y, pixels.getArgb(x, y));
            }
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(buffer, "png", out);
            return out.toByteArray();
        } catch (IOException | RuntimeException notEncodable) {
            // A frame the host cannot encode is a frame the plugin does not get. It is not a reason to fail
            // the request, because an empty answer is already the honest one for a host that cannot grab.
            System.err.println("Warning: could not encode a captured frame as PNG: " + notEncodable);
            return new byte[0];
        }
    }

    /**
     * A wire group name as a {@link ToolbarGroup}, or {@code null} when the host should drop the item.
     *
     * <p>Two ways to get {@code null} and they are the same decision from both ends: a name this build does
     * not know (a plugin built against a newer protocol), and {@code STUDIO}, which the contract already
     * refuses because the host owns its own section of the bar.
     */
    static ToolbarGroup group(String name) {
        ToolbarGroup group;
        try {
            group = ToolbarGroup.valueOf(name == null ? "" : name.trim());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
        return group == ToolbarGroup.STUDIO ? null : group;
    }

    /**
     * A wire name as an {@link EnabledWhen}, defaulting to {@link EnabledWhen#ALWAYS}.
     *
     * <p>Unlike {@link #group(String)} an unknown value is <b>not</b> fatal to the item: a button whose
     * enablement rule this build cannot read is still a button, and offering it always is the answer that
     * loses the user the least. Being wrong here costs a press that does nothing; being wrong about the
     * group costs a button that is nowhere.
     */
    static EnabledWhen enabledWhen(String name) {
        try {
            return EnabledWhen.valueOf(name == null ? "" : name.trim());
        } catch (IllegalArgumentException unknown) {
            return EnabledWhen.ALWAYS;
        }
    }

    /** Whether the host can draw {@code item} at all — the wire's own rule, plus a group it recognises. */
    static boolean isDrawable(WireToolbarItem item) {
        return item != null && item.isDrawable() && group(item.group()) != null;
    }

    private static int channel(double value) {
        return (int) Math.round(Math.max(0, Math.min(1, value)) * 255);
    }

    private Wire() {}
}
