package com.wavjaby.jdbc.processor.model;

import com.wavjaby.jdbc.annotation.Table;
import com.wavjaby.jdbc.annotation.UniqueConstraint;
import com.wavjaby.jdbc.processor.EmptyProcessingException;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import java.util.*;

import static com.wavjaby.jdbc.processor.util.AnnotationHelper.*;
import static javax.tools.Diagnostic.Kind.ERROR;

/** Stage A: one table as declared -- its own columns, keys and repository methods. Nothing here looks at another table. */
public class TableData {
    public final TableInfo tableInfo;
    public final Map<String, String> classDependency = new HashMap<>();
    public final LinkedHashMap<String, ColumnInfo> tableColumns = new LinkedHashMap<>();
    public final LinkedHashMap<String, ColumnInfo> tableFields = new LinkedHashMap<>();
    public final List<MethodInfo> interfaceMethodInfo = new ArrayList<>();

    public final List<ColumnInfo> primaryKey = new ArrayList<>();
    public final List<List<ColumnInfo>> uniqueKeyList = new ArrayList<>();

    public TableData(TableInfo tableInfo) {
        this.tableInfo = tableInfo;
    }

    /** Stops at the first error: later checks read the maps the earlier fields filled. */
    public void parseTableField(Types types, Messager console) throws EmptyProcessingException {
        Map<String, VariableElement> fields = new LinkedHashMap<>();
        extractClassFields(tableInfo.tableClassEle, fields);

        // Get field with column info
        for (VariableElement field : fields.values()) {
            ColumnInfo info = new ColumnInfo(field, this);
            if (info.idGenerator != null) {
                String providerClassPath = info.idGenerator.toString();
                addDependency(providerClassPath);
            }

            info.parseColumnCheck(console);

            tableFields.put(field.getSimpleName().toString(), info);
            if (tableColumns.containsKey(info.columnName)) {
                console.printMessage(ERROR, "Duplicate column name found: " + info.columnName, field);
                throw new EmptyProcessingException();
            }
            tableColumns.put(info.columnName, info);

            // Add primary key
            if (info.isPrimaryKey) {
                primaryKey.add(info);
            }

            // Add single unique key 
            if (info.isUniqueKey)
                uniqueKeyList.add(Collections.singletonList(info));
        }

        // Add grouped unique constraints
        AnnotationMirror constraintMirror = getAnnotationMirror(tableInfo.tableClassEle, Table.class);
        assert constraintMirror != null;
        List<? extends AnnotationValue> uniqueConstraints = getAnnotationValueList(constraintMirror, "uniqueConstraints");
        assert uniqueConstraints != null;
        int index = 0;
        for (UniqueConstraint uniqueConstraint : tableInfo.tableAnn.uniqueConstraints()) {
            AnnotationMirror uniqueConstraintMirror = getAnnotationMirrorFromValue(uniqueConstraints.get(index));
            if (uniqueConstraint.columnNames().length == 0 && uniqueConstraint.fieldNames().length == 0) {
                console.printMessage(ERROR, "Unique constraint must define at least one column or field", tableInfo.tableClassEle, uniqueConstraintMirror);
                throw new EmptyProcessingException();
            }
            List<ColumnInfo> uniqueKey = new ArrayList<>();
            // Fet unique constraint column by field name
            for (String name : uniqueConstraint.fieldNames()) {
                ColumnInfo columnInfo = this.tableFields.get(name);
                if (columnInfo == null) {
                    AnnotationValue field = getAnnotationValue(uniqueConstraintMirror, "fieldNames");
                    console.printMessage(ERROR, "Field '" + name + "' not exist in table '" + tableInfo.classPath + "'", tableInfo.tableClassEle, uniqueConstraintMirror, field);
                    throw new EmptyProcessingException();
                }
                uniqueKey.add(columnInfo);
            }
            // Fet unique constraint column by column name
            for (String name : uniqueConstraint.columnNames()) {
                if (!this.tableColumns.containsKey(name)) {
                    AnnotationValue column = getAnnotationValue(uniqueConstraintMirror, "columnNames");
                    console.printMessage(ERROR, "Column '" + name + "' not exist in table: " + tableInfo.name, tableInfo.tableClassEle, uniqueConstraintMirror, column);
                    throw new EmptyProcessingException();
                }
                for (ColumnInfo columnInfo : this.tableFields.values()) {
                    if (!columnInfo.columnName.equals(name)) continue;
                    uniqueKey.add(columnInfo);
                    break;
                }
            }
            uniqueKeyList.add(uniqueKey);
            index++;
        }

        // Every row SELECT lists tableColumns in this order, so that is the order the constructor is filled in
        List<String> columnNames = new ArrayList<>();
        List<TypeMirror> columnTypes = new ArrayList<>();
        for (ColumnInfo column : tableColumns.values()) {
            columnNames.add(column.columnName);
            columnTypes.add(column.field.asType());
        }
        RowConstructor.check(tableInfo.tableClassEle, columnNames, columnTypes, "Table '" + tableInfo.classPath + "'", tableInfo.tableClassEle, types, console);
    }

    /** Every method is parsed, so each bad one reports; throws after the last when any failed. */
    public void parseRepoMethods(TableData tableData, Types types, Messager console) throws EmptyProcessingException {
        Map<String, ExecutableElement> methods = new LinkedHashMap<>();
        extractClassMethods(tableInfo.repoIntClassElement, methods);

        // Find all methods in interface
        boolean error = false;
        for (ExecutableElement e : methods.values()) {
            MethodInfo methodInfo;
            try {
                methodInfo = new MethodInfo(e, tableData, types, console);
            } catch (EmptyProcessingException ex) {
                error = true;
                continue;
            }
            interfaceMethodInfo.add(methodInfo);
        }
        if (error)
            throw new EmptyProcessingException();
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

    @Override
    public boolean equals(Object obj) {
        if (super.equals(obj)) return true;
        if (!(obj instanceof TableData)) return false;
        return tableInfo.equals(((TableData) obj).tableInfo);
    }

    @Override
    public int hashCode() {
        return tableInfo.hashCode();
    }
}
