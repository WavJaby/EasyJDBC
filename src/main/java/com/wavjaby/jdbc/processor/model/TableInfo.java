package com.wavjaby.jdbc.processor.model;

import com.wavjaby.jdbc.Table;
import com.wavjaby.jdbc.processor.util.AnnotationHelper;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import java.util.List;

import static com.wavjaby.jdbc.util.StringConverter.convertPropertyNameToUnderscoreName;

public class TableInfo {
    public final TypeElement tableClassEle;
    public final Table tableAnn;
    public final AnnotationMirror tableAnnMirror;
    public final boolean isRecord;
    public final String name;
    public final String tableFullname;
    public final String classPath;
    public final String className;
    public final String classPackagePath;

    public final boolean isVirtual;
    public final TypeElement virtualBaseClass;

    public final TypeElement repoIntClassElement;
    public final String repoIntClassName;
    public final String repoIntPackagePath;

    public final String repoClassName;
    public final String repoClassPath;
    public final String schema;

    public String getUniqueKey(List<ColumnInfo> uniqueKey) {
        StringBuilder sb = new StringBuilder(" " + name);
        for (ColumnInfo info : uniqueKey) {
            sb.append('_').append(info.columnName);
        }
        return sb.append("_UK").toString();
    }

    public TableInfo(TypeElement tableClassEle, TypeElement repoIntClassElement, TypeElement virtualBaseClass) {
        Table table = tableClassEle.getAnnotation(Table.class);
        assert table != null;

        this.tableClassEle = tableClassEle;
        this.tableAnn = table;
        this.tableAnnMirror = AnnotationHelper.getAnnotationMirror(tableClassEle, Table.class);
        this.isRecord = tableClassEle.getKind() == ElementKind.RECORD;
        this.isVirtual = table.virtual();

        this.classPath = tableClassEle.asType().toString();
        this.className = tableClassEle.getSimpleName().toString();
        this.classPackagePath = tableClassEle.getEnclosingElement().toString();
        
        this.name = convertPropertyNameToUnderscoreName(!table.name().isBlank() ? table.name() : className);
        this.schema = table.schema().isBlank() ? null : convertPropertyNameToUnderscoreName(table.schema());
        this.tableFullname = schema == null ? name : schema + '.' + name;

        this.repoIntClassElement = repoIntClassElement;

        String repoIntClassName = repoIntClassElement.getSimpleName().toString();
        String repoIntPackagePath = repoIntClassElement.getEnclosingElement().toString();
        String repoIntClassPath = repoIntClassElement.toString();
        
        for (Element element : tableClassEle.getEnclosedElements()) {
            if (element.getKind() == ElementKind.INTERFACE && element == repoIntClassElement) {
                repoIntClassName = repoIntClassPath.substring(this.classPath.length() - this.className.length());
                repoIntPackagePath = repoIntPackagePath.substring(0, this.classPath.length() - this.className.length() - 1);
                break;
            }
        }

        this.repoIntClassName = repoIntClassName;
        this.repoIntPackagePath = repoIntPackagePath;
        this.repoClassName = repoIntClassName.replace('.', '_') + "Impl";
        this.repoClassPath = classPackagePath.isEmpty() ? repoClassName : classPackagePath + "." + repoClassName;

        this.virtualBaseClass = virtualBaseClass;
    }
}
