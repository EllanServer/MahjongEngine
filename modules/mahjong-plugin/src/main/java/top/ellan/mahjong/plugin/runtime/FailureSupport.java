package top.ellan.mahjong.plugin.runtime;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Shared exception normalization for asynchronous plugin boundaries. */
public final class FailureSupport {
    private FailureSupport() {}

    public static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }
}
