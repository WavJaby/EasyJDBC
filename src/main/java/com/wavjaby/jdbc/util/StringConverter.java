package com.wavjaby.jdbc.util;

public class StringConverter {

    public static String convertPropertyNameToUnderscoreName(String name) {
        StringBuilder result = new StringBuilder(name.length());
//        result.append(Character.toUpperCase(name.charAt(0))); // H2 Database
        
        char lastChar = name.charAt(0);
        result.append(Character.toLowerCase(lastChar));

        for (int i = 1; i < name.length(); ++i) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c) && Character.isLowerCase(lastChar)) {
//                result.append('_').append(c); // H2 Database
                result.append('_').append(Character.toLowerCase(c)); // PostgreSQL
            } else {
//                result.append(Character.toUpperCase(c)); // H2 Database
                result.append(Character.toLowerCase(c)); // PostgreSQL
            }
            lastChar = c;
        }

        return result.toString();
    }
}
