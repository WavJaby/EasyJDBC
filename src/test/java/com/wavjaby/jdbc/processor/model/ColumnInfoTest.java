package com.wavjaby.jdbc.processor.model;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ColumnInfoTest {
    @Test
    void foldColumnNameIgnoresDefaultLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            // The hazard is live under this locale: dotted-less i, so "ID" would not match the column "id"
            assertEquals("ıd", "ID".toLowerCase());
            assertEquals("id", ColumnInfo.foldColumnName("ID"));
        } finally {
            Locale.setDefault(saved);
        }
    }
}
