package com.wavjaby.jdbc.processor;

import com.google.auto.service.AutoService;
import com.wavjaby.jdbc.annotation.Table;
import com.wavjaby.jdbc.processor.emit.InitEmitter;
import com.wavjaby.jdbc.processor.emit.RepositoryEmitter;
import com.wavjaby.jdbc.processor.emit.UtilityClassEmitter;
import com.wavjaby.jdbc.processor.model.TableData;
import com.wavjaby.jdbc.processor.model.TableInfo;
import com.wavjaby.jdbc.processor.resolve.ResolvedTable;
import com.wavjaby.jdbc.processor.resolve.Resolver;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static com.wavjaby.jdbc.processor.util.AnnotationHelper.getAnnotationMirror;
import static com.wavjaby.jdbc.processor.util.AnnotationHelper.getAnnotationValueClassElement;
import static javax.tools.Diagnostic.Kind.ERROR;


/** Runs the stages: parse each @Table (model), link them (resolve), write repositories and RepositoryInit (emit). */
@AutoService(Processor.class)
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedAnnotationTypes("com.wavjaby.jdbc.annotation.Table")
@SuppressWarnings("unused")
public class TableProcessor extends AbstractProcessor {
    private Messager console;
    private Elements elementUtils;
    private Types typeUtils;
    private Filer filer;

    @SuppressWarnings("unused")
    public TableProcessor() {
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.elementUtils = processingEnv.getElementUtils();
        this.typeUtils = processingEnv.getTypeUtils();
        this.console = processingEnv.getMessager();
        this.filer = processingEnv.getFiler();
    }

    /**
     * Errors print a diagnostic, then throw EmptyProcessingException up to where the round decides how far to go on.
     * Parse stops the round at the first bad table; resolve and repository emit go on through every table, so each reports, then stop the round.
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        try {

            if (annotations.isEmpty())
                return false;

            Map<String, TableData> tableDataMap = new HashMap<>();
            for (TypeElement annotation : annotations) {
                for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                    TableData tableData = parseTableData((TypeElement) element);
                    tableDataMap.put(tableData.tableInfo.classPath, tableData);
                }
            }

            Map<String, ResolvedTable> resolvedTables = Resolver.resolve(tableDataMap, console);

            RepositoryEmitter repositoryEmitter = new RepositoryEmitter(console, filer, elementUtils);
            boolean error = false;
            for (TableData tableData : tableDataMap.values()) {
                try {
                    repositoryEmitter.emit(tableData);
                } catch (EmptyProcessingException e) {
                    error = true;
                }
            }
            if (error)
                return false;

            new InitEmitter(console, filer).emit(tableDataMap, resolvedTables);

            UtilityClassEmitter.emit(processingEnv, console);

            System.out.println("RepositoryTemplate process done " + tableDataMap.size());
            return true;
        } catch (EmptyProcessingException e) {
            return false;
        } catch (Exception e) {
            // An ERROR fails the build; returning false alone left the build green with nothing generated.
            // Full trace -> stderr; the diagnostic names the processor method that failed (the top frame is usually JDK code), no line number so it stays stable across edits
            e.printStackTrace();
            String processorPackage = TableProcessor.class.getPackageName();
            String where = Arrays.stream(e.getStackTrace())
                    .filter(frame -> frame.getClassName().startsWith(processorPackage))
                    .findFirst()
                    .map(frame -> " in " + frame.getClassName() + "." + frame.getMethodName())
                    .orElse("");
            console.printMessage(ERROR, "EasyJDBC processor internal error, a processor bug rather than a problem in your code: " + e + where);
            return false;
        }
    }

    private TableData parseTableData(TypeElement element) throws EmptyProcessingException {
        // Get table repository class
        AnnotationMirror tableMirror = getAnnotationMirror(element, Table.class);
        assert tableMirror != null;
        TypeElement repoInterfaceClass = (TypeElement) getAnnotationValueClassElement(tableMirror, "repositoryClass");
        if (repoInterfaceClass == null) {
            console.printMessage(ERROR, "The 'repositoryClass' is not given", element);
            throw new EmptyProcessingException();
        }
        TypeElement virtualBaseClass = (TypeElement) getAnnotationValueClassElement(tableMirror, "virtualBaseClass");

        // Get table definition class name
        TableInfo tableInfo = new TableInfo(element, repoInterfaceClass, virtualBaseClass);
        TableData tableData = new TableData(tableInfo);
        tableData.parseTableField(console);

        // Virtual table check
        if (tableInfo.isVirtual) {
            if (virtualBaseClass == null) {
                console.printMessage(ERROR, "Virtual table 'virtualBaseClass' is not given", element);
                throw new EmptyProcessingException();
            }
            if (tableData.tableFields.isEmpty()) {
                console.printMessage(ERROR, "Virtual table '" + tableInfo.name + "' must have at least one field", element);
            }
        }

        tableData.parseRepoMethods(tableData, typeUtils, console);

        return tableData;
    }
}
