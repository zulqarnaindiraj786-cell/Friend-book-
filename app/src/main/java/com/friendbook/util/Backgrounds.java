package com.friendbook.util;

/** Background gradient pairs (From, To) for text posts — like friendbook's colored posts. Index 0 = none. */
public class Backgrounds {

    /** [ [0]=[from,to] none, then gradient pairs ] */
    public static final int[][] GRADIENTS = {
            {0, 0},
            {0xFF1877F2, 0xFF0E5FC7}, // blue
            {0xFFE41E3F, 0xFFB31237}, // red
            {0xFF31A24C, 0xFF1E7A34}, // green
            {0xFFF7B928, 0xFFE07B00}, // sunset
            {0xFF8B5CF6, 0xFF5B21B6}, // violet
            {0xFF06B6D4, 0xFF0E7490}, // cyan
            {0xFFEC4899, 0xFF9D174D}, // pink
            {0xFF22262B, 0xFF0B0D0F}, // dark
            {0xFFFF8A3C, 0xFFDE3C83}, // orange→magenta
    };

    public static int pickerColor(int i) { return GRADIENTS[Math.min(i, GRADIENTS.length - 1)][0]; }
    public static int from(int i) { return GRADIENTS[Math.min(Math.max(i, 1), GRADIENTS.length - 1)][0]; }
    public static int to(int i) { return GRADIENTS[Math.min(Math.max(i, 1), GRADIENTS.length - 1)][1]; }
}
