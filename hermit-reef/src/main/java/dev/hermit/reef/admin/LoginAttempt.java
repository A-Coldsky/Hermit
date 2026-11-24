package dev.hermit.reef.admin;

import java.util.Optional;

public sealed interface LoginAttempt permits LoginAttempt.Success, LoginAttempt.Failure {

    Optional<LoginResponse> response();

    record Success(LoginResponse login) implements LoginAttempt {

        @Override
        public Optional<LoginResponse> response() {
            return Optional.of(login);
        }
    }

    record Failure(int remainingAttempts, boolean locked) implements LoginAttempt {

        @Override
        public Optional<LoginResponse> response() {
            return Optional.empty();
        }

        public boolean warnsRemaining() {
            return locked || remainingAttempts <= LoginService.WARN_REMAINING_THRESHOLD;
        }

        public String message() {
            if (locked) {
                return LoginService.LOCKED_MESSAGE;
            }
            if (remainingAttempts <= LoginService.WARN_REMAINING_THRESHOLD) {
                return LoginService.remainingMessage(remainingAttempts);
            }
            return LoginService.FAILURE_MESSAGE;
        }

        public LoginFailureDetail data() {
            if (!warnsRemaining()) {
                return null;
            }
            return new LoginFailureDetail(remainingAttempts, locked);
        }
    }
}
