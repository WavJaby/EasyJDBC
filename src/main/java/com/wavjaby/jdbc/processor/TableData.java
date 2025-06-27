package com.wavjaby.jdbc.processor;

import javax.annotation.processing.Messager;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import java.util.*;

import static com.wavjaby.jdbc.processor.AnnotationHelper.extractClassFields;
import static com.wavjaby.jdbc.processor.AnnotationHelper.extractClassMethods;
import static javax.tools.Diagnostic.Kind.ERROR;

public class TableData {
    public final TableInfo tableInfo;
    public final Map<String, String> classDependency = new HashMap<>();
    public final LinkedHashSet<String> tableColumnNames = new LinkedHashSet<>();
    public final LinkedHashMap<String, ColumnInfo> tableFields = new LinkedHashMap<>();
    public final List<MethodInfo> interfaceMethodInfo = new ArrayList<>();

    private TableData virtualBaseTableData;

    public TableData(TableInfo tableInfo) {
        this.tableInfo = tableInfo;
    }

    boolean parseTableField(Messager console) {
        Map<String, VariableElement> fields = new LinkedHashMap<>();
        extractClassFields(tableInfo.tableClassEle, fields);

        // Get field with column info
        for (VariableElement field : fields.values()) {
            ColumnInfo info = new ColumnInfo(field, tableInfo);
            if (info.idGenerator != null) {
                String providerClassPath = info.idGenerator.toString();
                addDependency(providerClassPath);
            }

            if (info.parseColumnCheck(console))
                return true;

            tableFields.put(field.getSimpleName().toString(), info);
            if (tableColumnNames.contains(info.columnName)) {
                console.printMessage(ERROR, "Duplicate column name found: " + info.columnName, field);
                return true;
            }
            tableColumnNames.add(info.columnName);
        }

        return false;
    }

    public boolean parseRepoMethods(TableData tableData, Messager console) {
        Map<String, ExecutableElement> methods = new LinkedHashMap<>();
        extractClassMethods(tableInfo.repoIntClassElement, methods);

        // Find all methods in interface
        for (ExecutableElement e : methods.values()) {
            MethodInfo methodInfo = new MethodInfo(e, tableData);
            if (methodInfo.parseMethod(console)) return true;
            interfaceMethodInfo.add(methodInfo);
        }

        return false;
    }

    /**
     * Process {@code @JoinColumn} annotation class link, column foreign key
     */
    public boolean processTableJoin(TableData tableData, Map<String, TableData> tableDataMap, Messager console) {
        for (Map.Entry<String, ColumnInfo> columnInfoEntry : tableData.tableFields.entrySet()) {
            ColumnInfo columnInfo = columnInfoEntry.getValue();
//            String fieldName = columnInfoEntry.getKey();
            // Column foreign key
            if (columnInfo.processTableJoin(tableDataMap, this, console))
                return true;
        }
        return false;
    }

    public boolean processVirtualTable(TableData tableData, Map<String, TableData> tableDataMap, Messager console) {
        TableInfo tableInfo = tableData.tableInfo;
        String virtualBaseClass = tableInfo.virtualBaseClass.toString();
        TableData virtualBaseTableData = tableDataMap.get(virtualBaseClass);
        if (virtualBaseTableData == null) {
            console.printMessage(ERROR, "Virtual table base '" + virtualBaseClass + "' is not a table", tableInfo.tableClassEle, tableInfo.tableAnnMirror);
            return true;
        }
        this.virtualBaseTableData = virtualBaseTableData;
        return false;
    }

    public void addDependency(String classPath) {
        String className = classPath.substring(classPath.lastIndexOf('.') + 1);
        String fieldName = Character.toLowerCase(className.charAt(0)) + className.substring(1);
        classDependency.put(classPath, fieldName);
    }
    
    public void addDependency(String classPath, String fieldName) {
        classDependency.put(classPath, fieldName);
    }

    public String getDependencyFieldName(String string) {
        return classDependency.get(string);
    }

    public Map<String, String> getDependencies() {
        return classDependency;
    }

    public TableData getVirtualBaseTableData() {
        return virtualBaseTableData;
    }
}
