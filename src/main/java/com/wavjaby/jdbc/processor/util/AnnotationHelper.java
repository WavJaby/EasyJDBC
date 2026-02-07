package com.wavjaby.jdbc.processor.util;

import com.wavjaby.jdbc.processor.TableProcessor;

import javax.annotation.processing.Messager;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.SimpleAnnotationValueVisitor8;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static javax.tools.Diagnostic.Kind.ERROR;

public class AnnotationHelper {

    public static void printError(Messager console, Element element, Class<? extends Annotation> clazz, String key,
                                  String message) {

        AnnotationMirror mirror = getAnnotationMirror(element, clazz);
        AnnotationValue value = mirror == null ? null : AnnotationHelper.getAnnotationValue(mirror, key);
        console.printMessage(ERROR, "\n" + message, element, mirror, value);
    }

    public static String getSimpleNameOrDefault(Element element, String defaultValue) {
        return element.getSimpleName().isEmpty() ? defaultValue : element.toString();
    }

    public static AnnotationMirror getAnnotationMirror(Element field, Class<?> clazz) {
        String clazzName = clazz.getName();
        for (AnnotationMirror m : field.getAnnotationMirrors()) {
            if (m.getAnnotationType().toString().equals(clazzName)) {
                return m;
            }
        }
        return null;
    }

    public static Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> getAnnotationEntry(AnnotationMirror annotationMirror, String key) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : annotationMirror.getElementValues().entrySet()) {
            if (entry.getKey().getSimpleName().toString().equals(key))
                return entry;
        }
        return null;
    }

    public static AnnotationValue getAnnotationValue(AnnotationMirror annotationMirror, String key) {
        if (annotationMirror == null) return null;
        Map<? extends ExecutableElement, ? extends AnnotationValue> values =
                TableProcessor.getElementUtils().getElementValuesWithDefaults(annotationMirror);
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : values.entrySet()) {
            if (entry.getKey().getSimpleName().toString().equals(key))
                return entry.getValue();
        }
        return null;
    }

    public static List<? extends AnnotationValue> getAnnotationValueList(AnnotationMirror annotationMirror, String key) {
        AnnotationValue value = getAnnotationValue(annotationMirror, key);
        if (value == null) return null;
        AtomicReference<List<? extends AnnotationValue>> list = new AtomicReference<>();

        value.accept(new SimpleAnnotationValueVisitor8<Void, Void>() {
            @Override
            public Void visitArray(List<? extends AnnotationValue> values, Void p) {
                list.set(values);
                return null;
            }

            @Override
            public Void visitAnnotation(AnnotationMirror a, Void p) {
                return null;
            }
        }, null);
        return list.get();
    }

    public static AnnotationMirror getAnnotationMirrorFromValue(AnnotationValue value) {
        if (value == null) return null;
        final AnnotationMirror[] result = {null};
        value.accept(new SimpleAnnotationValueVisitor8<Void, Void>() {
            @Override
            public Void visitArray(List<? extends AnnotationValue> values, Void p) {
                return null;
            }

            @Override
            public Void visitAnnotation(AnnotationMirror a, Void p) {
                result[0] = a;
                return null;
            }
        }, null);
        return result[0];
    }

    public static DeclaredType getAnnotationValueClass(AnnotationMirror annotationMirror, String key) {
        AnnotationValue value = getAnnotationValue(annotationMirror, key);
        if (value == null ||
                value.getValue() instanceof TypeMirror typeMirror && typeMirror.getKind() == TypeKind.VOID)
            return null;
        return ((DeclaredType) value.getValue());
    }

    public static Element getAnnotationValueClassElement(AnnotationMirror annotationMirror, String key) {
        DeclaredType value = getAnnotationValueClass(annotationMirror, key);
        if (value == null) return null;
        return value.asElement();
    }

    public static boolean checkInstanceof(TypeElement classToCheck, TypeElement targetClass) {
        if (classToCheck.toString().equals(targetClass.toString()))
            return true;

        for (TypeMirror anInterface : classToCheck.getInterfaces()) {
            TypeElement interfaceClass = (TypeElement) ((DeclaredType) anInterface).asElement();
            if (checkInstanceof(interfaceClass, targetClass))
                return true;
        }
        TypeMirror superclassMirror = classToCheck.getSuperclass();
        if (superclassMirror != null && !(superclassMirror instanceof DeclaredType))
            return false;
        TypeElement superclass = (TypeElement) ((DeclaredType) classToCheck.getSuperclass()).asElement();
        return checkInstanceof(superclass, targetClass);
    }

    public static void extractClassFields(TypeElement element, Map<String, VariableElement> fields) {
        if (element.getSuperclass().getKind() != TypeKind.NONE) {
            DeclaredType superclass = (DeclaredType) element.getSuperclass();
            if (!superclass.toString().startsWith("java.lang.") &&
                    !superclass.toString().startsWith("java.sql."))
                extractClassFields((TypeElement) superclass.asElement(), fields);
        }
        for (Element i : element.getEnclosedElements()) {
            if (i.getKind() != ElementKind.FIELD) continue;
            VariableElement field = (VariableElement) i;

            // Skip static or private fields
            Set<Modifier> modifiers = field.getModifiers();
            if (modifiers.contains(Modifier.STATIC))
                continue;

            String fieldName = field.getSimpleName().toString();
            if (!fields.containsKey(fieldName))
                fields.put(fieldName, field);
        }
    }

    public static void extractClassMethods(TypeElement element, Map<String, ExecutableElement> methods) {
        if (element.getSuperclass().getKind() != TypeKind.NONE) {
            DeclaredType superclass = (DeclaredType) element.getSuperclass();
            if (!superclass.toString().startsWith("java.lang.") &&
                    !superclass.toString().startsWith("java.sql."))
                extractClassMethods((TypeElement) superclass.asElement(), methods);
        }
        for (TypeMirror parentClass : element.getInterfaces()) {
            extractClassMethods((TypeElement) ((DeclaredType) parentClass).asElement(), methods);
        }
        for (Element i : element.getEnclosedElements()) {
            if (i.getKind() != ElementKind.METHOD) continue;
            ExecutableElement method = (ExecutableElement) i;

            // Get method key for checking Override
            StringBuilder builder = new StringBuilder(i.getSimpleName().toString());
            for (VariableElement parameter : method.getParameters()) {
                builder.append('_').append(parameter.asType().toString());
            }
            String methodKeyStr = builder.toString();
            methods.put(methodKeyStr, method);
        }
    }
}
