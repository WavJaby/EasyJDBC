package com.wavjaby.jdbc.processor.model;

import com.wavjaby.jdbc.processor.EmptyProcessingException;

import javax.annotation.processing.Messager;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import java.util.List;

import static javax.tools.Diagnostic.Kind.ERROR;

/**
 * The constructor FastRowMapper builds every row through, checked at compile time. Its rule (FastRowMapper.getConstructor): the first public
 * constructor whose arity equals the field count, in getConstructors() order -- which is unspecified -- filled by position from the SELECT columns.
 * So exactly one public constructor may have that arity, and its parameter types must follow the column order. A record's canonical constructor always does.
 */
final class RowConstructor {
    private RowConstructor() {
    }

    /** {@code columnNames} / {@code columnTypes}: parallel, in SELECT order. {@code label} starts each message, e.g. "Projection 'fx.X'". */
    static void check(TypeElement type, List<String> columnNames, List<? extends TypeMirror> columnTypes, String label,
                      Element errorAt, Types types, Messager console) throws EmptyProcessingException {
        int arity = columnTypes.size();
        List<ExecutableElement> candidates = type.getEnclosedElements().stream()
                .filter(e -> e.getKind() == ElementKind.CONSTRUCTOR && e.getModifiers().contains(Modifier.PUBLIC))
                .map(e -> (ExecutableElement) e)
                .filter(c -> c.getParameters().size() == arity)
                .toList();
        String order = String.join(", ", columnNames);

        if (candidates.isEmpty()) {
            console.printMessage(ERROR, label + " has no public constructor taking its " + arity + " columns (" + order +
                    ") in that order; the row mapper builds every row through one", errorAt);
            throw new EmptyProcessingException();
        }
        if (candidates.size() > 1) {
            console.printMessage(ERROR, label + " has " + candidates.size() + " public constructors taking " + arity +
                    " parameters; the row mapper would pick one arbitrarily -- keep only one at that arity", errorAt);
            throw new EmptyProcessingException();
        }

        List<? extends VariableElement> parameters = candidates.getFirst().getParameters();
        for (int i = 0; i < arity; i++) {
            if (!types.isSameType(types.erasure(parameters.get(i).asType()), types.erasure(columnTypes.get(i)))) {
                console.printMessage(ERROR, label + " constructor parameter " + (i + 1) + " is '" + parameters.get(i).asType() + "' but column '" +
                        columnNames.get(i) + "' is '" + columnTypes.get(i) + "'; the row mapper fills parameters in column order (" + order + ")", errorAt);
                throw new EmptyProcessingException();
            }
        }
    }
}
