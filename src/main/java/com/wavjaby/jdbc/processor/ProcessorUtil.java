package com.wavjaby.jdbc.processor;

import javax.lang.model.element.Element;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class ProcessorUtil {

    public static Class<?> getClassFromElement(Element field) throws ClassNotFoundException {
        return Class.forName(field.asType().toString());
    }

    public static String getResourceAsString(InputStream inputStream) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        for (int length; (length = inputStream.read(buffer)) != -1; ) {
            result.write(buffer, 0, length);
        }
        inputStream.close();
        return result.toString(StandardCharsets.UTF_8);
    }
}
