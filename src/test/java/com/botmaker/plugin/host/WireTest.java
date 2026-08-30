package com.botmaker.plugin.host;

import com.botmaker.plugin.api.EnabledWhen;
import com.botmaker.plugin.api.Region;
import com.botmaker.plugin.api.ThemeTokens;
import com.botmaker.plugin.api.ToolbarGroup;
import com.botmaker.plugin.protocol.WireColor;
import com.botmaker.plugin.protocol.WireRegion;
import com.botmaker.plugin.protocol.WireThemeTokens;
import com.botmaker.plugin.protocol.WireToolbarItem;

import javafx.scene.paint.Color;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract-to-wire mapping, which is the cost of the protocol module naming no BotMaker type.
 *
 * <p>Nothing makes the two record sets agree at compile time except the positional constructor calls in
 * {@link Wire}, so this is where the agreement is actually checked — a component added to one side and not
 * the other shows up here as a wrong value rather than as a plugin quietly styling itself with the wrong
 * colour.
 */
class WireTest {

    @Test
    void the_theme_crosses_component_for_component() {
        ThemeTokens dark = new ThemeTokens(true, "#101010", "#EEEEEE", "#4488FF", "#3377EE",
                "#FF4444", "#FFAA00", "#44CC66", "'Inter', sans-serif", "'Fira Code', monospace", 12.5);

        WireThemeTokens crossed = Wire.themeTokens(dark);

        assertTrue(crossed.dark());
        assertEquals("#101010", crossed.background());
        assertEquals("#EEEEEE", crossed.text());
        assertEquals("#4488FF", crossed.accent());
        assertEquals("#3377EE", crossed.hover());
        assertEquals("#FF4444", crossed.error());
        assertEquals("#FFAA00", crossed.warning());
        assertEquals("#44CC66", crossed.success());
        assertEquals("'Inter', sans-serif", crossed.fontFamily());
        assertEquals("'Fira Code', monospace", crossed.monoFamily());
        assertEquals(12.5, crossed.fontSize());
    }

    @Test
    void a_host_with_no_theme_still_sends_a_legible_one() {
        assertEquals(WireThemeTokens.DEFAULT, Wire.themeTokens(null));
    }

    @Test
    void a_region_crosses_and_a_missing_one_is_a_cancel() {
        assertEquals(WireRegion.of(10, 20, 640, 480), Wire.region(new Region(10, 20, 640, 480)));
        assertTrue(Wire.region(null).cancelled());
    }

    @Test
    void a_colour_becomes_the_eight_bit_channels_the_pixel_had() {
        assertEquals(WireColor.of(255, 255, 255), Wire.color(Color.WHITE));
        assertEquals(WireColor.of(0, 0, 0), Wire.color(Color.BLACK));
        // Rounded, not truncated: 0.999 came off a screen as 255, and reporting 254 fails a colour match
        // for a reason nobody would find.
        assertEquals(255, Wire.color(Color.color(0.999, 0, 0)).red());
        assertEquals(128, Wire.color(Color.color(0.5, 0, 0)).red());
        assertTrue(Wire.color(null).cancelled());
    }

    @Test
    void a_group_the_host_owns_or_has_never_heard_of_is_not_drawn() {
        assertEquals(ToolbarGroup.RUN, Wire.group("RUN"));
        assertEquals(ToolbarGroup.TOOLS, Wire.group(" TOOLS "));
        assertNull(Wire.group("STUDIO"), "the host owns its own section of the bar");
        assertNull(Wire.group("HOLOGRAM"), "a plugin built against a newer protocol");
        assertNull(Wire.group(null));
    }

    @Test
    void an_enablement_rule_the_host_cannot_read_still_leaves_a_usable_button() {
        assertEquals(EnabledWhen.BOT_STOPPED, Wire.enabledWhen("BOT_STOPPED"));
        // Unlike the group: being wrong here costs a press that does nothing, and being wrong about the
        // group costs a button that is nowhere.
        assertEquals(EnabledWhen.ALWAYS, Wire.enabledWhen("WHEN_THE_MOON_IS_FULL"));
        assertEquals(EnabledWhen.ALWAYS, Wire.enabledWhen(null));
    }

    @Test
    void an_item_is_drawable_only_when_both_ends_agree_it_is() {
        assertTrue(Wire.isDrawable(new WireToolbarItem("go", "Go", "", "", "RUN", 0, "ALWAYS")));
        assertFalse(Wire.isDrawable(new WireToolbarItem("", "Go", "", "", "RUN", 0, "ALWAYS")));
        assertFalse(Wire.isDrawable(new WireToolbarItem("go", "", "", "", "RUN", 0, "ALWAYS")));
        assertFalse(Wire.isDrawable(new WireToolbarItem("go", "Go", "", "", "STUDIO", 0, "ALWAYS")));
        assertFalse(Wire.isDrawable(null));
    }

    @Test
    void a_frame_the_host_cannot_grab_is_an_empty_answer_rather_than_a_failure() {
        assertEquals(0, Wire.png(null).length);
    }
}
