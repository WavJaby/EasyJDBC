package com.wavjaby.jdbc.processor;

/** Thrown after the diagnostic is already printed: it carries no message, it only unwinds to where processing of the element stops. */
public class EmptyProcessingException extends Exception {
    public EmptyProcessingException() {
        super(null, null, false, false);
    }
}
