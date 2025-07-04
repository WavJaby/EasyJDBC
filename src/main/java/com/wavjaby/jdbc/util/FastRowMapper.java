package com.wavjaby.jdbc.util;

import org.springframework.beans.BeanInstantiationException;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.convert.ConversionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.jdbc.support.rowset.SqlRowSet;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Parameter;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import static com.wavjaby.jdbc.util.StringConverter.convertPropertyNameToUnderscoreName;
import static java.lang.reflect.Modifier.isStatic;

public class FastRowMapper<T> implements RowMapper<T> {
    private static final ConversionService conversionService = ApplicationConversionService.getSharedInstance();
    private final Constructor<T> mappedClassConstructor;
    private final int resultFieldCount;
    private final Integer[] staticFieldMap;
    private final Class<?>[] staticFieldType;

    public FastRowMapper(Class<T> mappedClass, int columnCount) {
        Constructor<T> constructor = getConstructor(mappedClass);
        mappedClassConstructor = constructor;
        resultFieldCount = columnCount;
        staticFieldMap = null;
        staticFieldType = new Class[columnCount];

        int index = 0;
        Parameter[] parameters = constructor.getParameters();
        for (Parameter parameter : parameters) {
            checkParam(mappedClass, parameter);
            staticFieldType[index++] = parameter.getType();
        }
    }

    public FastRowMapper(Class<T> mappedClass, String tableName, JdbcTemplate jdbc) {
        Constructor<T> constructor = getConstructor(mappedClass);
        mappedClassConstructor = constructor;

        Parameter[] parameters = constructor.getParameters();

        SqlRowSet rowSet = jdbc.queryForRowSet("select COLUMN_NAME,ORDINAL_POSITION,DATA_TYPE from INFORMATION_SCHEMA.COLUMNS where TABLE_SCHEMA!='INFORMATION_SCHEMA' and TABLE_NAME=?", tableName);
        Map<String, Integer> collumnMap = new HashMap<>();
        while (rowSet.next()) {
            String field = rowSet.getString(1);
            int index = rowSet.getInt(2) - 1;
            collumnMap.put(field, index);
        }
        staticFieldMap = new Integer[collumnMap.size()];
        staticFieldType = new Class[collumnMap.size()];

        int dataObjIndex = 0;
        for (Parameter parameter : parameters) {
            checkParam(mappedClass, parameter);
            String columnName = convertPropertyNameToUnderscoreName(parameter.getName());
            Integer index = collumnMap.get(columnName);
            if (index == null)
                throw new BeanInstantiationException(mappedClass, "Key '" + columnName + "' not exist in table '" + tableName + "'");
            staticFieldMap[index] = dataObjIndex;
            staticFieldType[index] = parameter.getType();
            dataObjIndex++;
        }
        resultFieldCount = dataObjIndex;
    }

    private static <T> void checkParam(Class<T> mappedClass, Parameter parameter) {
        if (!parameter.isNamePresent())
            throw new BeanInstantiationException(mappedClass, "Parameter name is not present");
        if (parameter.getName().startsWith("this$"))
            throw new BeanInstantiationException(mappedClass, "Class should be static");
    }

    @SuppressWarnings("unchecked")
    private static <T> Constructor<T> getConstructor(Class<T> mappedClass) {
        Map<String, Field> fields = new HashMap<>();
        extractClassFields(mappedClass, fields);

        Constructor<T>[] constructors = (Constructor<T>[]) mappedClass.getConstructors();
        for (Constructor<T> constructor : constructors) {
            if (constructor.getParameterCount() == fields.size())
                return constructor;
        }
        throw new BeanInstantiationException(mappedClass, "No constructor found with " + fields.size() + " param");
    }

    public static void extractClassFields(Class<?> mappedClass, Map<String, Field> fields) {
        Class<?> superclass = mappedClass.getSuperclass();
        if (!superclass.getName().startsWith("java.lang.") &&
                !superclass.getName().startsWith("java.sql."))
            extractClassFields(superclass, fields);
        for (Field field : mappedClass.getDeclaredFields()) {
            if (isStatic(field.getModifiers()))
                continue;

            String fieldName = field.getName();
            if (!fields.containsKey(fieldName))
                fields.put(fieldName, field);
        }
    }

    @Override
    public T mapRow(ResultSet rs, int rowNum) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();

        Object[] values = new Object[resultFieldCount];

        for (int i = 0; i < columnCount; i++) {
            // Get column index
            Integer paramIndex;
            if (staticFieldMap == null)
                paramIndex = i;
            else {
                paramIndex = staticFieldMap[i];
                if (paramIndex == null)
                    continue;
            }

            Class<?> targetType = staticFieldType[i];
            // Convert type
            Object rawVal = JdbcUtils.getResultSetValue(rs, i + 1, targetType);
            if (rawVal != null && needConvert(rawVal.getClass(), targetType)) {
                if (!conversionService.canConvert(rawVal.getClass(), targetType))
                    throw new RuntimeException("Cannot convert " + rawVal.getClass() + " to " + targetType);
                rawVal = conversionService.convert(rawVal, targetType);
            }

            values[paramIndex] = rawVal;
        }

        try {
            return mappedClassConstructor.newInstance(values);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean needConvert(Class<?> sourceType, Class<?> targetType) {
        return !ClassUtils.isAssignable(targetType, sourceType);
    }
}
