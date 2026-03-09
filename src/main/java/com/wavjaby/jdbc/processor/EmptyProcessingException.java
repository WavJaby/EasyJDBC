package com.wavjaby.jdbc.processor;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;

public class EmptyProcessingException extends ProcessingException {
    public EmptyProcessingException() {
        super(null, null);
    }

    public void printTo(Messager console) {
    }
}