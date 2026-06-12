package com.commitgotchi.common.error;

import org.junit.jupiter.api.Test;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseConstraintTest {

    @Test
    void readsConstraintFromPostgresMetadataInsteadOfExceptionText() {
        ConstraintViolationException constraintViolation = new ConstraintViolationException(
                "localized database error",
                new SQLException("localized database error"),
                "uq_users_email"
        );

        assertThat(DatabaseConstraint.isViolation(
                new DataIntegrityViolationException("localized error", constraintViolation),
                "uq_users_email"
        )).isTrue();
        assertThat(DatabaseConstraint.isViolation(
                new DataIntegrityViolationException("constraint uq_users_email"),
                "uq_users_email"
        )).isFalse();
    }
}
