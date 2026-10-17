package com.wavjaby.jdbc.processor.emit;

import com.squareup.javapoet.*;
import com.wavjaby.jdbc.processor.EmptyProcessingException;
import com.wavjaby.jdbc.processor.model.ColumnInfo;
import com.wavjaby.jdbc.processor.model.TableData;
import com.wavjaby.jdbc.processor.resolve.ResolvedTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import java.io.IOException;
import java.util.*;

import static com.wavjaby.jdbc.processor.emit.PostgresDialect.createEnumTypeSql;
import static com.wavjaby.jdbc.processor.emit.PostgresDialect.enumTypeName;
import static javax.tools.Diagnostic.Kind.ERROR;

/** Writes RepositoryInit: schemas, enum types, then tables before views, each after what its DDL names. */
public class InitEmitter {
    private final Messager console;
    private final Filer filer;

    public InitEmitter(Messager console, Filer filer) {
        this.console = console;
        this.filer = filer;
    }

    public void emit(Map<String, TableData> tableDataMap, Map<String, ResolvedTable> resolvedTables) throws EmptyProcessingException {
        Map<String, ResolvedTable> tableDependency = new LinkedHashMap<>();
        for (ResolvedTable table : resolvedTables.values())
            processTableDependency(table, resolvedTables, tableDependency);
        generateInitFile(tableDataMap, tableDependency);
    }

    private void generateInitFile(Map<String, TableData> tableDataMap, Map<String, ResolvedTable> tableDependency) throws EmptyProcessingException {
        String classPackage = "com.wavjaby.jdbc.util";
        String className = "RepositoryInit";

        FieldSpec logger = FieldSpec.builder(Logger.class, "logger")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.getLogger($L.class)", LoggerFactory.class, className)
                .build();
        FieldSpec jdbc = FieldSpec.builder(JdbcTemplate.class, "jdbc")
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .build();

        MethodSpec constructor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(JdbcTemplate.class, "jdbc")
                .addStatement("this.jdbc = jdbc")
                .build();

        MethodSpec.Builder initSchemeAndTable = MethodSpec.methodBuilder("initSchemeAndTable")
                .addModifiers(Modifier.PUBLIC);

        Set<String> schemaList = new HashSet<>();
        for (TableData tableData : tableDataMap.values()) {
            if (tableData.tableInfo.schema == null) continue;
            schemaList.add(tableData.tableInfo.schema);
        }

        // Create schema init code
        for (String schema : schemaList) {
            initSchemeAndTable.addStatement("jdbc.execute($S)", "create schema if not exists " + schema + ";");
        }

        // Create enum type init code, keyed by type name: one CREATE TYPE per name however many columns use it
        Map<String, TypeElement> enumTypes = new LinkedHashMap<>();
        for (ResolvedTable table : tableDependency.values()) {
            for (ColumnInfo column : table.table.tableFields.values()) {
                if (column.nativeEnum) {
                    if (column.type instanceof DeclaredType declaredType && declaredType.asElement() instanceof TypeElement typeElement) {
                        enumTypes.putIfAbsent(enumTypeName(column.type), typeElement);
                    }
                }
            }
        }
        for (Map.Entry<String, TypeElement> entry : enumTypes.entrySet()) {
            List<String> enumValues = entry.getValue().getEnclosedElements().stream()
                    .filter(e -> e.getKind() == ElementKind.ENUM_CONSTANT)
                    .map(e -> e.getSimpleName().toString())
                    .toList();
            String sql = createEnumTypeSql(entry.getKey(), enumValues);
            initSchemeAndTable.addStatement("jdbc.execute(\"\"\"\n$L\"\"\")", sql);
        }

        // Create table create SQL
        List<ResolvedTable> tables = new ArrayList<>(tableDependency.values());
        tables.sort((a, b) -> {
            if (a.table.tableInfo.isVirtual && !b.table.tableInfo.isVirtual) return 1;
            if (!a.table.tableInfo.isVirtual && b.table.tableInfo.isVirtual) return -1;
            return 0;
        });
        for (ResolvedTable table : tables) {
            StringBuilder tableCreateSql = new StringBuilder();
            if (table.table.tableInfo.isVirtual)
                SqlGenerator.generateCreateViewSql(table, tableCreateSql);
            else
                SqlGenerator.generateCreateTableSql(table, tableCreateSql, console);
            initSchemeAndTable.addStatement("jdbc.execute(\"\"\"\n$L\"\"\")", tableCreateSql);
        }

        MethodSpec onApplicationEvent = MethodSpec.methodBuilder("onApplicationEvent")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ParameterSpec.builder(
                                ApplicationReadyEvent.class, "event")
                        .addModifiers(Modifier.FINAL)
                        .build())
                .addStatement("logger.debug($S)", "Start init scheme and table")
                .addStatement("initSchemeAndTable()")
                .addStatement("logger.debug($S)", "Init scheme and table success")
                .build();

        TypeSpec typeSpec = TypeSpec.classBuilder(className)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Component.class)
                .addSuperinterface(ParameterizedTypeName.get(
                        ApplicationListener.class,
                        ApplicationReadyEvent.class))
                .addField(logger)
                .addField(jdbc)
                .addMethod(constructor)
                .addMethod(initSchemeAndTable.build())
                .addMethod(onApplicationEvent)
                .build();

        try {
            JavaFile.builder(classPackage, typeSpec)
                    .build()
                    .writeTo(filer);
        } catch (IOException e) {
            console.printMessage(ERROR, "Could not write class: '" + classPackage + "." + className + "'");
            throw new EmptyProcessingException();
        }
    }

    // Post-order: every table or view lands after the ones its DDL names
    private void processTableDependency(ResolvedTable table, Map<String, ResolvedTable> resolvedTables, Map<String, ResolvedTable> dependency) {
        if (table.viewBase != null)
            processTableDependency(resolvedTables.get(table.viewBase.tableInfo.classPath), resolvedTables, dependency);
        for (ColumnInfo info : table.table.tableFields.values()) {
            ColumnInfo referencedColumn = table.referencedColumn(info);
            if (referencedColumn != null && !dependency.containsKey(info.referencedTableClassPath))
                processTableDependency(resolvedTables.get(referencedColumn.tableData.tableInfo.classPath), resolvedTables, dependency);
        }
        dependency.put(table.table.tableInfo.classPath, table);
    }
}
