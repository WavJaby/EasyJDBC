package com.wavjaby.jdbc.processor.model;

public class QueryParamInfo {
    public final String sqlPart;
    public final String paramName;
    private MethodParamInfo methodParamInfo;

    public QueryParamInfo(String sqlPart, String paramName) {
        this.sqlPart = sqlPart;
        this.paramName = paramName;
    }

    public boolean isMethodParamExist() {
        return methodParamInfo != null;
    }

    public MethodParamInfo getMethodParamInfo() {
        return methodParamInfo;
    }

    public void setMethodParamInfo(MethodParamInfo methodParamInfo) {
        this.methodParamInfo = methodParamInfo;
    }
}
