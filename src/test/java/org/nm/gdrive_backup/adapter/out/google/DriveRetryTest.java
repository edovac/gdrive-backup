package org.nm.gdrive_backup.adapter.out.google;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class DriveRetryTest {

	private final List<Long> sleeps = new ArrayList<>();
	private final DriveRetry retry = new DriveRetry(5, 100, 1_000, sleeps::add);

	@Test
	void returnsTheResultOfASuccessfulCallWithoutWaiting() throws Exception {
		assertEquals("ok", retry.call(() -> "ok"));

		assertTrue(sleeps.isEmpty());
	}

	@Test
	void retriesTransientFailuresUntilTheCallSucceeds() throws Exception {
		List<IOException> failures = List.of(jsonError(429, "rateLimitExceeded"),
				jsonError(403, "userRateLimitExceeded"), jsonError(503, "backendError"));
		AtomicInteger attempts = new AtomicInteger();

		String result = retry.call(() -> {
			int attempt = attempts.getAndIncrement();
			if (attempt < failures.size()) {
				throw failures.get(attempt);
			}
			return "ok";
		});

		assertEquals("ok", result);
		assertEquals(4, attempts.get());
		assertEquals(3, sleeps.size());
	}

	@Test
	void retriesEveryServerErrorStatus() throws Exception {
		for (int status : new int[] { 500, 502, 503, 504 }) {
			assertTrue(DriveRetry.isRetryable(jsonError(status, null)), "status " + status);
		}
		assertTrue(DriveRetry.isRetryable(jsonError(429, null)));
	}

	@Test
	void doesNotRetryAnExportThatIsTooLargeEvenThoughItIsAForbiddenResponse() {
		GoogleJsonResponseException exportLimit = jsonError(403, "exportSizeLimitExceeded");
		AtomicInteger attempts = new AtomicInteger();

		GoogleJsonResponseException thrown = assertThrows(GoogleJsonResponseException.class, () -> retry.call(() -> {
			attempts.incrementAndGet();
			throw exportLimit;
		}));

		assertSame(exportLimit, thrown, "the original exception reaches the caller so its details stay readable");
		assertEquals(1, attempts.get());
		assertTrue(sleeps.isEmpty());
	}

	@Test
	void doesNotRetryClientErrorsThatAreNotRateLimits() {
		assertFalse(DriveRetry.isRetryable(jsonError(400, "badRequest")));
		assertFalse(DriveRetry.isRetryable(jsonError(401, "authError")));
		assertFalse(DriveRetry.isRetryable(jsonError(403, "forbidden")));
		assertFalse(DriveRetry.isRetryable(jsonError(404, "notFound")));
		assertFalse(DriveRetry.isRetryable(jsonError(403, null)), "a 403 without a reason is not known to be a rate limit");
		assertFalse(DriveRetry.isRetryable(new GoogleJsonResponseException(
				new HttpResponseException.Builder(403, "Forbidden", new HttpHeaders()), null)));
	}

	@Test
	void retriesPlainHttpFailuresByStatus() {
		assertTrue(DriveRetry.isRetryable(httpError(429)));
		assertTrue(DriveRetry.isRetryable(httpError(503)));
		assertFalse(DriveRetry.isRetryable(httpError(404)));
		assertFalse(DriveRetry.isRetryable(httpError(403)));
	}

	@Test
	void retriesFailuresThatHappenBeforeAResponseArrives() {
		assertTrue(DriveRetry.isRetryable(new SocketException("Connection reset")));
		assertTrue(DriveRetry.isRetryable(new ConnectException("Connection refused")));
		assertTrue(DriveRetry.isRetryable(new SocketTimeoutException("Read timed out")));
		assertFalse(DriveRetry.isRetryable(new UnknownHostException("www.googleapis.com")));
		assertFalse(DriveRetry.isRetryable(new FileNotFoundException("missing")));
		assertFalse(DriveRetry.isRetryable(new IOException("something else")));
	}

	@Test
	void givesUpAfterTheMaximumAttemptsAndRethrowsTheLastFailure() {
		DriveRetry threeAttempts = new DriveRetry(3, 100, 1_000, sleeps::add);
		AtomicInteger attempts = new AtomicInteger();

		GoogleJsonResponseException thrown = assertThrows(GoogleJsonResponseException.class,
				() -> threeAttempts.call(() -> {
					throw jsonError(503, "backendError" + attempts.incrementAndGet());
				}));

		assertEquals(3, attempts.get());
		assertEquals(2, sleeps.size(), "no wait after the final attempt");
		assertEquals("backendError3", thrown.getDetails().getErrors().getFirst().getReason());
	}

	@Test
	void aSingleAttemptNeverRetries() {
		DriveRetry once = new DriveRetry(1, 100, 1_000, sleeps::add);
		AtomicInteger attempts = new AtomicInteger();

		assertThrows(GoogleJsonResponseException.class, () -> once.call(() -> {
			attempts.incrementAndGet();
			throw jsonError(503, "backendError");
		}));

		assertEquals(1, attempts.get());
		assertTrue(sleeps.isEmpty());
	}

	@Test
	void waitsLongerAfterEachFailureUpToTheCap() throws Exception {
		AtomicInteger attempts = new AtomicInteger();

		retry.call(() -> {
			if (attempts.incrementAndGet() < 5) {
				throw jsonError(503, "backendError");
			}
			return "ok";
		});

		assertEquals(4, sleeps.size());
		long[] ceilings = { 100, 200, 400, 800 };
		for (int i = 0; i < ceilings.length; i++) {
			assertTrue(sleeps.get(i) >= ceilings[i] / 2 && sleeps.get(i) <= ceilings[i],
					"wait " + (i + 1) + " was " + sleeps.get(i) + ", expected " + ceilings[i] / 2 + ".." + ceilings[i]);
		}
	}

	@Test
	void theBackoffDoublesWithHalfJitterAndNeverExceedsTheCap() {
		for (int sample = 0; sample < 200; sample++) {
			assertBetween(50, 100, retry.delayMillis(1));
			assertBetween(100, 200, retry.delayMillis(2));
			assertBetween(200, 400, retry.delayMillis(3));
			assertBetween(400, 800, retry.delayMillis(4));
			assertBetween(500, 1_000, retry.delayMillis(5));
			assertBetween(500, 1_000, retry.delayMillis(50));
		}
	}

	@Test
	void beingInterruptedWhileWaitingStopsRetryingAndKeepsTheInterruptFlag() {
		DriveRetry interrupted = new DriveRetry(5, 100, 1_000, millis -> {
			throw new InterruptedException("stop");
		});
		AtomicInteger attempts = new AtomicInteger();

		try {
			IOException thrown = assertThrows(IOException.class, () -> interrupted.call(() -> {
				attempts.incrementAndGet();
				throw jsonError(503, "backendError");
			}));

			assertInstanceOf(InterruptedIOException.class, thrown);
			assertEquals(1, attempts.get());
			assertTrue(Thread.currentThread().isInterrupted());
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	void aNonRetryableFailureIsRethrownImmediately() {
		IOException failure = new FileNotFoundException("gone");

		IOException thrown = assertThrows(IOException.class, () -> retry.call(() -> {
			throw failure;
		}));

		assertSame(failure, thrown);
		assertTrue(sleeps.isEmpty());
	}

	@Test
	void requiresAtLeastOneAttempt() {
		assertThrows(IllegalArgumentException.class, () -> new DriveRetry(0, 100, 1_000, millis -> {
		}));
	}

	private static void assertBetween(long min, long max, long actual) {
		assertTrue(actual >= min && actual <= max, actual + " is not within " + min + ".." + max);
	}

	private static GoogleJsonResponseException jsonError(int status, String reason) {
		GoogleJsonError error = new GoogleJsonError();
		error.setCode(status);
		error.setMessage(reason == null ? "error" : reason);
		if (reason != null) {
			GoogleJsonError.ErrorInfo info = new GoogleJsonError.ErrorInfo();
			info.setReason(reason);
			error.setErrors(List.of(info));
		}
		return new GoogleJsonResponseException(new HttpResponseException.Builder(status, "error", new HttpHeaders()), error);
	}

	private static HttpResponseException httpError(int status) {
		return new HttpResponseException.Builder(status, "error", new HttpHeaders()).build();
	}
}
