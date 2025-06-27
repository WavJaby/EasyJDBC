package com.wavjaby.jdbc.processor;

import com.wavjaby.persistence.Where;

import javax.lang.model.element.Element;
import java.util.List;

public class MethodParamInfo {
    public final List<ColumnInfo> columns;
    public final String paramType;
    public final String paramName;
    public final boolean dataClass;
    public final Element parameter;
    public final boolean ignoreCase;

    public final boolean where;
    // Default = "="
    public final String whereOperation;

    public MethodParamInfo(Element parameter, List<ColumnInfo> columns, String paramType, String paramName, boolean dataClass, Where where) {
        this.columns = columns;
        this.paramType = paramType;
        this.paramName = paramName;
        this.parameter = parameter;
        this.dataClass = dataClass;
        this.where = where != null;
        this.whereOperation = this.where ? where.operation() : "=";

        this.ignoreCase = this.where && where.ignoreCase();
    }
}
