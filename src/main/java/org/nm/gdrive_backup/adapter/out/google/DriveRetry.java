package org.nm.gdrive_backup.adapter.out.google;

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpResponseException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Retries a Drive call that failed for a transient reason, with exponential backoff. Parallel downloads make
 * rate limiting likely, and Drive reports it in several ways: HTTP 429, 5xx, and a 403 whose error reason is a rate
 * limit. The reason lives in the parsed error body, so this works on the exception rather than on the raw response,
 * and other 403s (such as an export that is too large) are never retried.
 */
final class DriveRetry {

	private static final Set<String> RATE_LIMIT_REASONS = Set.of("rateLimitExceeded", "userRateLimitExceeded");

	@FunctionalInterface
	interface Call<T> {
		T call() throws IOException;
	}

	@FunctionalInterface
	interface Sleeper {
		void sleep(long millis) throws InterruptedException;
	}

	private final int maxAttempts;
	private final long initialDelayMillis;
	private final long maxDelayMillis;
	private final Sleeper sleeper;

	DriveRetry(int maxAttempts, long initialDelayMillis, long maxDelayMillis, Sleeper sleeper) {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("maxAttempts must be at least 1");
		}
		this.maxAttempts = maxAttempts;
		this.initialDelayMillis = initialDelayMillis;
		this.maxDelayMillis = maxDelayMillis;
		this.sleeper = sleeper;
	}

	static DriveRetry standard() {
		return new DriveRetry(5, 500, 30_000, Thread::sleep);
	}

	<T> T call(Call<T> call) throws IOException {
		for (int attempt = 1;; attempt++) {
			try {
				return call.call();
			} catch (IOException exception) {
				if (attempt >= maxAttempts || !isRetryable(exception)) {
					throw exception;
				}
				pause(delayMillis(attempt));
			}
		}
	}

	/** Backoff after the given failed attempt (1-based): doubles each time up to the cap, with half of it jittered. */
	long delayMillis(int failedAttempt) {
		long ceiling = initialDelayMillis;
		for (int i = 1; i < failedAttempt && ceiling < maxDelayMillis; i++) {
			ceiling *= 2;
		}
		ceiling = Math.min(ceiling, maxDelayMillis);
		long half = ceiling / 2;
		return half + ThreadLocalRandom.current().nextLong(ceiling - half + 1);
	}

	static boolean isRetryable(IOException exception) {
		if (exception instanceof GoogleJsonResponseException jsonException) {
			return isRetryableStatus(jsonException.getStatusCode()) || isRateLimited(jsonException);
		}
		if (exception instanceof HttpResponseException httpException) {
			return isRetryableStatus(httpException.getStatusCode());
		}
		// Failures before a response arrived: a reset or refused connection, or a timeout.
		return exception instanceof SocketException || exception instanceof SocketTimeoutException;
	}

	private static boolean isRetryableStatus(int status) {
		return status == 429 || status >= 500;
	}

	private static boolean isRateLimited(GoogleJsonResponseException exception) {
		if (exception.getStatusCode() != 403) {
			return false;
		}
		GoogleJsonError details = exception.getDetails();
		List<GoogleJsonError.ErrorInfo> errors = details == null ? null : details.getErrors();
		return errors != null && errors.stream().anyMatch(error -> RATE_LIMIT_REASONS.contains(error.getReason()));
	}

	private void pause(long millis) throws InterruptedIOException {
		try {
			sleeper.sleep(millis);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			InterruptedIOException interrupted = new InterruptedIOException("Interrupted while waiting to retry");
			interrupted.initCause(exception);
			throw interrupted;
		}
	}
}
