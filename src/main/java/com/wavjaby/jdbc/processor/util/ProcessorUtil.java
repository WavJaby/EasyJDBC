package com.wavjaby.jdbc.processor.util;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

import static javax.tools.Diagnostic.Kind.ERROR;

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

    public static boolean copyUtilityClasses(ProcessingEnvironment processingEnv, Messager console) {
        String[] utilityClasses = {
                "IdentifierGenerator", "Snowflake", "FastRowMapper", "StringConverter", "FastResultSetExtractor"
        };

        for (String className : utilityClasses) {
            if (copyUtilityClass(processingEnv, console, className))
                return true;
        }
        return false;
    }

    public static boolean copyUtilityClass(ProcessingEnvironment processingEnv, Messager console, String className) {
        String classPackage = "com.wavjaby.jdbc.util";
        String path = '/' + classPackage.replace('.', '/') + '/' + className + ".java";
        String classPath = classPackage + '.' + className;

        try (InputStream inputStream = ProcessorUtil.class.getResourceAsStream(path)) {
            if (inputStream == null) {
                console.printMessage(ERROR, "Could not find utility class resource: " + className);
                return true;
            }
            String sourceContent = getResourceAsString(inputStream);

            try (Writer out = processingEnv.getFiler().createSourceFile(classPath).openWriter()) {
                out.write(sourceContent);
            } catch (IOException e) {
                console.printMessage(ERROR, "Could not write utility class: '" + classPath + "'");
                return true;
            }
        } catch (IOException e) {
            console.printMessage(ERROR, "Error copying utility class " + className + ": " + e.getMessage());
            return true;
        }

        return false;
    }
}
