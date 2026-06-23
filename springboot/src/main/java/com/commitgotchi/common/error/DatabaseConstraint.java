package com.commitgotchi.common.error;

import java.lang.reflect.Method;

public final class DatabaseConstraint {

    private DatabaseConstraint() {
    }

    public static boolean isViolation(Throwable throwable, String constraintName) {
        Throwable current = throwable;
        while (current != null) {
            if (constraintName.equals(readConstraintName(current))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String readConstraintName(Throwable throwable) {
        String direct = invokeStringGetter(throwable, "getConstraintName");
        if (direct != null) {
            return direct;
        }

        Object serverErrorMessage = invokeGetter(throwable, "getServerErrorMessage");
        if (serverErrorMessage == null) {
            return null;
        }
        return invokeStringGetter(serverErrorMessage, "getConstraint");
    }

    private static String invokeStringGetter(Object target, String methodName) {
        Object value = invokeGetter(target, methodName);
        return value instanceof String text ? text : null;
    }

    private static Object invokeGetter(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }
}
