package com.commitgotchi.common.error;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseConstraintTest {

    @Test
    void readsConstraintFromPostgresServerMetadataInsteadOfExceptionText() {
        assertThat(DatabaseConstraint.isViolation(
                new DataIntegrityViolationException("localized error", new FakePostgresException("uq_users_email")),
                "uq_users_email"
        )).isTrue();
        assertThat(DatabaseConstraint.isViolation(
                new DataIntegrityViolationException("constraint uq_users_email"),
                "uq_users_email"
        )).isFalse();
    }

    public static class FakePostgresException extends RuntimeException {

        private final FakeServerErrorMessage serverErrorMessage;

        FakePostgresException(String constraint) {
            this.serverErrorMessage = new FakeServerErrorMessage(constraint);
        }

        public FakeServerErrorMessage getServerErrorMessage() {
            return serverErrorMessage;
        }
    }

    public static class FakeServerErrorMessage {

        private final String constraint;

        FakeServerErrorMessage(String constraint) {
            this.constraint = constraint;
        }

        public String getConstraint() {
            return constraint;
        }
    }
}
