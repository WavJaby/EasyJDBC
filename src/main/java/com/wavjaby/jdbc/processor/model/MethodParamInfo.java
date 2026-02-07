package com.wavjaby.jdbc.processor;
package com.wavjaby.jdbc.processor.model;

import com.wavjaby.persistence.Where;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.type.DeclaredType;
import java.util.List;

public class MethodParamInfo {
    public final List<ColumnInfo> columns;
    public final String paramTypeName;
    public final String paramName;
    public final boolean dataClass;
    public final boolean isRecord;
    public final Element parameter;
    public final boolean where;
    public final boolean ignoreCase;
    public final boolean customSqlParam;
    // Default = "="
    public final String whereOperation;

    public MethodParamInfo(Element parameter, List<ColumnInfo> columns, String paramTypeName, String paramName, boolean dataClass, Where where, boolean customSqlParam) {
        this.columns = columns;
        this.paramTypeName = paramTypeName;
        this.paramName = paramName;
        this.parameter = parameter;
        this.dataClass = dataClass;
        this.isRecord = parameter != null && parameter.asType() instanceof DeclaredType declaredType &&
                declaredType.asElement().getKind() == ElementKind.RECORD;
        this.where = where != null;
        this.ignoreCase = this.where && where.ignoreCase();
        this.customSqlParam = customSqlParam;

        this.whereOperation = this.where ? where.operation() : "=";
    }
}
