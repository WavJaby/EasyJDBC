package com.wavjaby.jdbc.util;

import org.springframework.beans.BeanInstantiationException;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.convert.ConverterNotFoundException;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.GenericConverter;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.jdbc.support.rowset.SqlRowSet;
import org.springframework.util.ClassUtils;

import java.lang.reflect.*;
import java.lang.reflect.Array;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;

import static com.wavjaby.jdbc.util.StringConverter.convertPropertyNameToUnderscoreName;
import static java.lang.reflect.Modifier.isStatic;


public class FastRowMapper<T> implements RowMapper<T> {
    private static final GenericConversionService conversionService = (GenericConversionService) ApplicationConversionService.getSharedInstance();
    private static final Method getConverter;

    static {
        try {
            getConverter = GenericConversionService.class.getDeclaredMethod("getConverter", TypeDescriptor.class, TypeDescriptor.class);
            getConverter.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    private final Constructor<T> mappedClassConstructor;
    private final int resultFieldCount;
    private final Integer[] staticFieldMap;
    private final Class<?>[] staticFieldType;
    private final GenericConverter[] staticFieldConverter;

    public FastRowMapper(Class<T> mappedClass, int columnCount) {
        Constructor<T> constructor = getConstructor(mappedClass);
        mappedClassConstructor = constructor;
        resultFieldCount = columnCount;
        staticFieldMap = null;
        staticFieldType = new Class[columnCount];
        staticFieldConverter = new GenericConverter[columnCount];

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
        staticFieldConverter = new GenericConverter[collumnMap.size()];

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
                if (isEnumStringArray(rawVal, targetType))
                    rawVal = convertEnumStringArray(rawVal, targetType.getComponentType(), i);
                else if (isArray(rawVal, targetType))
                    rawVal = convertArray(rawVal, targetType.getComponentType());
                else {
                    TypeDescriptor sourceType = TypeDescriptor.valueOf(rawVal.getClass());
                    TypeDescriptor targetTypeInfo = TypeDescriptor.valueOf(targetType);
                    // Get cached converter
                    GenericConverter converter = staticFieldConverter[i];
                    if (converter == null)
                        converter = staticFieldConverter[i] = getConverter(sourceType, targetTypeInfo);

                    rawVal = converter.convert(rawVal, sourceType, targetTypeInfo);
                }
            }

            values[paramIndex] = rawVal;
        }

        try {
            return mappedClassConstructor.newInstance(values);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    private static GenericConverter getConverter(TypeDescriptor sourceType, TypeDescriptor targetType) {
        try {
            GenericConverter converter = (GenericConverter) getConverter.invoke(conversionService, sourceType, targetType);
            if (converter == null)
                throw new ConverterNotFoundException(sourceType, targetType);
            return converter;
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    private static Object convertArray(Object rawArrayVal, Class<?> targetType) {
        Object[] rawValArray;
        try {
            rawValArray = (Object[]) ((java.sql.Array) rawArrayVal).getArray();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        if (rawValArray == null || rawValArray.length == 0) {
            return java.lang.reflect.Array.newInstance(targetType, 0);
        }

        return rawValArray;
    }

    private static boolean isArray(Object sourceType, Class<?> targetType) {
        try {
            return targetType.isArray() &&
                    ClassUtils.isAssignable(java.sql.Array.class, sourceType.getClass());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    private Object convertEnumStringArray(Object rawArrayVal, Class<?> targetType, int index) {
        Object[] rawValArray;
        try {
            rawValArray = (Object[]) ((java.sql.Array) rawArrayVal).getArray();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        if (rawValArray.length == 0)
            return Array.newInstance(targetType, 0);
        
        // Create enum array
        Object enumArray = Array.newInstance(targetType, rawValArray.length);
        TypeDescriptor sourceType = TypeDescriptor.valueOf(String.class);
        TypeDescriptor targetTypeInfo = TypeDescriptor.valueOf(targetType);
        GenericConverter converter = staticFieldConverter[index];
        if (converter == null)
            converter = staticFieldConverter[index] = getConverter(sourceType, targetTypeInfo);

        for (int i = 0; i < rawValArray.length; i++) {
            Object converted = converter.convert(rawValArray[i], sourceType, targetTypeInfo);
            Array.set(enumArray, i, converted);
        }
        return enumArray;
    }

    private static boolean isEnumStringArray(Object sourceType, Class<?> targetType) {
        try {
            return targetType.isArray() &&
                    targetType.getComponentType().isEnum() &&
                    ClassUtils.isAssignable(java.sql.Array.class, sourceType.getClass()) &&
                    ((java.sql.Array) sourceType).getBaseType() == Types.VARCHAR;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean needConvert(Class<?> sourceType, Class<?> targetType) {
        return !ClassUtils.isAssignable(targetType, sourceType);
    }
}
