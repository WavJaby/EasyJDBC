package com.wavjaby.jdbc.processor.util;

import com.wavjaby.jdbc.processor.EmptyProcessingException;

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

    public static void copyUtilityClasses(ProcessingEnvironment processingEnv, Messager console) throws EmptyProcessingException {
        String[] utilityClasses = {
                "IdentifierGenerator", "Snowflake", "FastRowMapper", "StringConverter", "FastResultSetExtractor"
        };

        for (String className : utilityClasses)
            copyUtilityClass(processingEnv, console, className);
    }

    public static void copyUtilityClass(ProcessingEnvironment processingEnv, Messager console, String className) throws EmptyProcessingException {
        String classPackage = "com.wavjaby.jdbc.util";
        String path = '/' + classPackage.replace('.', '/') + '/' + className + ".java";
        String classPath = classPackage + '.' + className;

        try (InputStream inputStream = ProcessorUtil.class.getResourceAsStream(path)) {
            if (inputStream == null) {
                console.printMessage(ERROR, "Could not find utility class resource: " + className);
                throw new EmptyProcessingException();
            }
            String sourceContent = getResourceAsString(inputStream);
            sourceContent = sourceContent.replace("package com.wavjaby.persistence;", "package com.wavjaby.jdbc.annotation;");
            sourceContent = sourceContent.replace("import com.wavjaby.persistence.", "import com.wavjaby.jdbc.annotation.");
            sourceContent = sourceContent.replace("com.wavjaby.jdbc.annotation.Table", "com.wavjaby.jdbc.annotation.Table");

            try (Writer out = processingEnv.getFiler().createSourceFile(classPath).openWriter()) {
                out.write(sourceContent);
            } catch (IOException e) {
                console.printMessage(ERROR, "Could not write utility class: '" + classPath + "'");
                throw new EmptyProcessingException();
            }
        } catch (IOException e) {
            console.printMessage(ERROR, "Error copying utility class " + className + ": " + e.getMessage());
            throw new EmptyProcessingException();
        }
    }
}
