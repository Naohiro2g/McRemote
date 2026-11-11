package club.code2create.mcremote;

import java.util.Locale;

/** Eight horizontal viewing directions, clockwise from Minecraft's positive-Z south. */
enum HudDirection {
    SOUTH("S", "南"), SOUTH_WEST("SW", "南西"), WEST("W", "西"), NORTH_WEST("NW", "北西"),
    NORTH("N", "北"), NORTH_EAST("NE", "北東"), EAST("E", "東"), SOUTH_EAST("SE", "南東");

    private final String english;
    private final String japanese;

    HudDirection(String english, String japanese) {
        this.english = english;
        this.japanese = japanese;
    }

    static HudDirection fromYaw(float yaw) {
        double normalized = ((yaw % 360.0) + 360.0) % 360.0;
        return values()[(int) Math.floor((normalized + 22.5) / 45.0) % 8];
    }

    String label(Locale locale) {
        return PlayerMessage.isJapanese(locale) ? japanese : english;
    }
}
