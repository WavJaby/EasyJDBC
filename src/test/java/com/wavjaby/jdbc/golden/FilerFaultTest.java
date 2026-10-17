package com.wavjaby.jdbc.golden;

import com.wavjaby.jdbc.processor.TableProcessor;
import org.junit.jupiter.api.Test;

import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Error paths no source input reaches: the Filer failing. createSourceFile throws what the test supplies; everything else passes through.
 * Fixture: scenario join-by-column-name (tables Item, Owner) -- any valid scenario does, this one is small.
 */
class FilerFaultTest {
    private static final Path FIXTURE = Path.of(System.getProperty("golden.resourceDir"), "scenarios", "join-by-column-name");

    // An IOException is the Filer's declared failure: each repository reports it and the round still goes through every table
    @Test
    void writeFailureIsReportedPerTable() throws IOException {
        List<String> diagnostics = run("filer-io-fault", () -> new IOException("injected"));
        assertEquals(List.of(
                "ERROR -: Could not write class: 'fx.ItemRepositoryImpl'. Error: java.io.IOException: injected",
                "ERROR -: Could not write class: 'fx.OwnerRepositoryImpl'. Error: java.io.IOException: injected"), diagnostics);
    }

    // Anything else is a processor bug: one ERROR (the build must fail, not pass with nothing generated) naming the first processor frame
    @Test
    void unexpectedExceptionFailsTheBuild() throws IOException {
        List<String> diagnostics = run("filer-runtime-fault", () -> new IllegalStateException("injected"));
        assertEquals(List.of(
                "ERROR -: EasyJDBC processor internal error, a processor bug rather than a problem in your code: " +
                        "java.lang.IllegalStateException: injected in com.wavjaby.jdbc.processor.emit.RepositoryEmitter.generateFile"), diagnostics);
    }

    private static List<String> run(String workName, Supplier<Exception> fault) throws IOException {
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(FIXTURE)) {
            sources = walk.filter(p -> p.getFileName().toString().endsWith(".java")).sorted().toList();
        }
        return ProcessorRunner.run(workName, sources, new FaultyFilerProcessor(fault)).diagnostics();
    }

    private static final class FaultyFilerProcessor extends TableProcessor {
        private final Supplier<Exception> fault;

        FaultyFilerProcessor(Supplier<Exception> fault) {
            this.fault = fault;
        }

        // @SupportedAnnotationTypes / @SupportedSourceVersion are not @Inherited: without these the subclass claims nothing and never runs
        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of(TableProcessor.class.getAnnotation(SupportedAnnotationTypes.class).value());
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return TableProcessor.class.getAnnotation(SupportedSourceVersion.class).value();
        }

        @Override
        public synchronized void init(ProcessingEnvironment env) {
            Filer filer = env.getFiler();
            Filer faulty = proxy(Filer.class, (method, args) -> {
                if (method.getName().equals("createSourceFile"))
                    throw fault.get();
                return method.invoke(filer, args);
            });
            super.init(proxy(ProcessingEnvironment.class, (method, args) ->
                    method.getName().equals("getFiler") ? faulty : method.invoke(env, args)));
        }
    }

    private interface Call {
        Object invoke(java.lang.reflect.Method method, Object[] args) throws Exception;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Call call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, method, args) -> {
            try {
                return call.invoke(method, args);
            } catch (InvocationTargetException e) {
                throw e.getCause(); // the real object's own failure, unwrapped
            }
        });
    }
}
