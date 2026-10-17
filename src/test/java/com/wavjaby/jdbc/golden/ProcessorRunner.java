package com.wavjaby.jdbc.golden;

import com.wavjaby.jdbc.processor.TableProcessor;
import com.wavjaby.jdbc.processor.emit.UtilityClassEmitter;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Runs one scenario through javac + TableProcessor and returns everything the processor produced.
 * No database: the snapshot is the generated source, which is where the DDL and the query strings live.
 */
final class ProcessorRunner {

    // Verbatim copies of resources, identical in every scenario -- snapshotting them would bury the generated code.
    private static final Set<String> COPIED_UTILITIES = Set.copyOf(UtilityClassEmitter.CLASSES);

    record Result(List<String> diagnostics, List<GeneratedFile> generated) {
    }

    record GeneratedFile(String path, String content) {
    }

    static Result run(String scenario, List<Path> sources) throws IOException {
        Path work = Path.of(System.getProperty("golden.workDir")).resolve(scenario);
        Path gen = work.resolve("generated");
        Path classes = work.resolve("classes");
        deleteRecursively(work);
        Files.createDirectories(gen);
        Files.createDirectories(classes);

        String classpath = System.getProperty("golden.classpath") + File.pathSeparator + System.getProperty("golden.sourceDir");

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> collected = new DiagnosticCollector<>();
        List<String> options = List.of(
                "-classpath", classpath,
                "-processorpath", classpath,
                "-processor", TableProcessor.class.getName(),
                "-s", gen.toString(),
                "-d", classes.toString(),
                "-nowarn");

        try (StandardJavaFileManager files = compiler.getStandardFileManager(collected, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = files.getJavaFileObjectsFromPaths(sources);
            compiler.getTask(null, files, collected, options, null, units).call();
        }

        return new Result(diagnostics(collected), generatedFiles(gen));
    }

    private static List<String> diagnostics(DiagnosticCollector<JavaFileObject> collected) {
        List<String> out = new ArrayList<>();
        collected.getDiagnostics().forEach(d -> {
            // File name only, no line/column: a fixture edit must not churn every unrelated snapshot line
            String where = d.getSource() == null ? "-" : Path.of(d.getSource().getName()).getFileName().toString();
            out.add(d.getKind() + " " + where + ": " + d.getMessage(Locale.ROOT).replace("\r\n", "\n"));
        });
        out.sort(Comparator.naturalOrder());
        return out;
    }

    private static List<GeneratedFile> generatedFiles(Path gen) throws IOException {
        try (Stream<Path> walk = Files.walk(gen)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> !COPIED_UTILITIES.contains(stripSuffix(p.getFileName().toString())))
                    .sorted(Comparator.comparing(Path::toString))
                    .map(p -> new GeneratedFile(
                            gen.relativize(p).toString().replace('\\', '/'),
                            read(p)))
                    .toList();
        }
    }

    private static String stripSuffix(String name) {
        return name.substring(0, name.length() - ".java".length());
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8).replace("\r\n", "\n").stripTrailing();
        } catch (IOException e) {
            throw new UncheckedIOExceptionWrapper(p, e);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(p);
        }
    }

    static final class UncheckedIOExceptionWrapper extends RuntimeException {
        UncheckedIOExceptionWrapper(Path p, IOException cause) {
            super("cannot read " + p, cause);
        }
    }

    private ProcessorRunner() {
    }
}
