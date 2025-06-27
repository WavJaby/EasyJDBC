package com.wavjaby.jdbc.util;

public class StringConverter {

    public static String convertPropertyNameToUnderscoreName(String name) {
        StringBuilder result = new StringBuilder(name.length());
        result.append(Character.toUpperCase(name.charAt(0)));

        for (int i = 1; i < name.length(); ++i) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                result.append('_').append(c);
            } else {
                result.append(Character.toUpperCase(c));
            }
        }

        return result.toString();
    }
}
