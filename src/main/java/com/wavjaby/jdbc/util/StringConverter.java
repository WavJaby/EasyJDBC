package com.wavjaby.jdbc.util;

public class StringConverter {

    /** Java name -> lower snake case identifier. Dots collapse to '_', so a qualified enum class name becomes one identifier. */
    public static String convertPropertyNameToUnderscoreName(String name) {
        StringBuilder result = new StringBuilder(name.length());

        char lastChar = name.charAt(0);
        result.append(Character.toLowerCase(lastChar));

        for (int i = 1; i < name.length(); ++i) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c) && Character.isLowerCase(lastChar)) {
                result.append('_').append(Character.toLowerCase(c));
            } else if (c == '.') {
                result.append('_');
            } else {
                result.append(Character.toLowerCase(c));
            }
            lastChar = c;
        }

        return result.toString();
    }
}
