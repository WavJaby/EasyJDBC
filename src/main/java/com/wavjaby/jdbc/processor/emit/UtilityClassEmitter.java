package com.wavjaby.jdbc.processor.emit;

import com.wavjaby.jdbc.processor.EmptyProcessingException;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static javax.tools.Diagnostic.Kind.ERROR;

/**
 * Writes the runtime classes generated repositories call into the consumer's build, verbatim from this jar's own sources,
 * so the consumer depends on the processor at compile time only.
 */
public final class UtilityClassEmitter {
    public static final String PACKAGE = "com.wavjaby.jdbc.util";
    /** Simple names under {@link #PACKAGE}; each ships as a .java resource beside the processor classes. */
    public static final List<String> CLASSES = List.of("IdentifierGenerator", "Snowflake", "FastRowMapper", "StringConverter", "FastResultSetExtractor");

    private UtilityClassEmitter() {
    }

    public static void emit(ProcessingEnvironment processingEnv, Messager console) throws EmptyProcessingException {
        for (String className : CLASSES)
            copy(processingEnv, console, className);
    }

    private static void copy(ProcessingEnvironment processingEnv, Messager console, String className) throws EmptyProcessingException {
        String path = '/' + PACKAGE.replace('.', '/') + '/' + className + ".java";
        String classPath = PACKAGE + '.' + className;

        try (InputStream inputStream = UtilityClassEmitter.class.getResourceAsStream(path)) {
            if (inputStream == null) {
                console.printMessage(ERROR, "Could not find utility class resource: " + className);
                throw new EmptyProcessingException();
            }
            String sourceContent = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);

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
