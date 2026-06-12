package com.commitgotchi.common.error;

import org.hibernate.exception.ConstraintViolationException;

public final class DatabaseConstraint {

    private DatabaseConstraint() {
    }

    public static boolean isViolation(Throwable throwable, String constraintName) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraintViolation
                    && constraintName.equals(constraintViolation.getConstraintName())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
