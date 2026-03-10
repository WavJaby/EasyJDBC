package com.wavjaby.jdbc.processor;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;

public class ProcessingException extends Exception {
    private final Element element;
    private final AnnotationMirror annotationMirror;
    private final AnnotationValue annotationValue;

    public ProcessingException(Element element,
                               AnnotationMirror a, AnnotationValue v,
                               String message, Object... args) {
        super(message == null ? null : String.format(message, args));
        this.element = element;
        this.annotationMirror = a;
        this.annotationValue = v;
    }

    public ProcessingException(Element e, String msg) {
        this(e, null, null, msg);
    }

    public ProcessingException(Element e, AnnotationMirror a, String msg) {
        this(e, a, null, msg);
    }

    public ProcessingException() {
        super();
        this.element = null;
        this.annotationMirror = null;
        this.annotationValue = null;
    }

    public void printTo(Messager console) {
        if (getMessage() == null) {
            return;
        }
        console.printMessage(Diagnostic.Kind.ERROR, getMessage(), element, annotationMirror, annotationValue);
    }
}
