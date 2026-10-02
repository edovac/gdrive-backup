package org.nm.gdrive_backup.domain.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Overlaps the downloads of a list of items and hands each result, as soon as it is ready, to a sink on the calling
 * thread. Downloads are latency-bound, so running a few at once is much faster than one by one; everything that is
 * not thread-safe (the ZIP being written, the order of its entries) stays on the caller.
 *
 * <p>Results are handed over in the order they <em>complete</em>, not list order: a slow download then occupies
 * only its own slot while the others keep flowing, instead of holding back every file queued behind it.
 *
 * <p>At most {@code concurrency} fetches run at once, and at most {@code 2 * concurrency} are submitted but not yet
 * handed over, which bounds the staged content waiting to be written. A concurrency of 1 never fetches ahead: it
 * fetches, hands over, then fetches the next.
 */
final class ParallelContentFetcher {

	/** How often a wait for the next result looks at the stop request. */
	private static final long STOP_POLL_MILLIS = 100;

	@FunctionalInterface
	interface Fetch<T, R extends AutoCloseable> {
		R fetch(T item) throws Exception;
	}

	@FunctionalInterface
	interface Sink<T, R extends AutoCloseable> {
		/** {@code fetched} is {@code null} for an item that needed no fetch. The sink takes ownership of a result. */
		void accept(T item, R fetched) throws Exception;
	}

	private final int concurrency;

	ParallelContentFetcher(int concurrency) {
		if (concurrency < 1) {
			throw new IllegalArgumentException("concurrency must be at least 1");
		}
		this.concurrency = concurrency;
	}

	/**
	 * Fetches the items that {@code needsFetch} selects and passes every item to {@code sink}: an item that needs no
	 * fetch as it is reached, the others as their fetches complete. Returns {@code false} when {@code stopRequested}
	 * ended the run early; results fetched but not yet handed over are released. A failure in a fetch or in the sink
	 * aborts the run the same way and is rethrown.
	 */
	<T, R extends AutoCloseable> boolean process(List<T> items, Predicate<T> needsFetch, Fetch<T, R> fetch,
			BooleanSupplier stopRequested, Sink<T, R> sink) {
		int window = concurrency == 1 ? 1 : 2 * concurrency;
		Semaphore running = new Semaphore(concurrency);
		// Submitted and not yet handed over; the leftovers are cancelled or released when the run ends early.
		Set<Future<Done<T, R>>> outstanding = new LinkedHashSet<>();
		// Closing the executor waits for tasks still running, so no download outlives the call.
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			CompletionService<Done<T, R>> completions = new ExecutorCompletionService<>(executor);
			try {
				int next = 0;
				while (next < items.size() || !outstanding.isEmpty()) {
					if (stopRequested.getAsBoolean()) {
						return false;
					}
					while (next < items.size() && outstanding.size() < window) {
						T item = items.get(next++);
						if (needsFetch.test(item)) {
							outstanding.add(completions.submit(() -> fetchWhenFree(running, fetch, item)));
						} else {
							sink.accept(item, null);
						}
					}
					if (outstanding.isEmpty()) {
						continue;
					}
					Future<Done<T, R>> ready = awaitNext(completions, stopRequested);
					if (ready == null) {
						return false;
					}
					outstanding.remove(ready);
					Done<T, R> done = await(ready);
					sink.accept(done.item(), done.result());
				}
				return true;
			} catch (RuntimeException | Error exception) {
				throw exception;
			} catch (Exception exception) {
				throw new IllegalStateException("Unable to process fetched content", exception);
			} finally {
				outstanding.forEach(future -> future.cancel(true));
			}
		} finally {
			// The executor is closed by now, so each future is settled: release what finished but was never handed over.
			outstanding.forEach(ParallelContentFetcher::release);
		}
	}

	private static <T, R extends AutoCloseable> Done<T, R> fetchWhenFree(Semaphore running, Fetch<T, R> fetch, T item)
			throws Exception {
		running.acquire();
		try {
			return new Done<>(item, fetch.fetch(item));
		} finally {
			running.release();
		}
	}

	/** The next completed fetch, or {@code null} if a stop was requested while waiting for it. */
	private static <V> Future<V> awaitNext(CompletionService<V> completions, BooleanSupplier stopRequested) {
		try {
			while (true) {
				Future<V> ready = completions.poll(STOP_POLL_MILLIS, TimeUnit.MILLISECONDS);
				if (ready != null) {
					return ready;
				}
				if (stopRequested.getAsBoolean()) {
					return null;
				}
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for a download", exception);
		}
	}

	private static <V> V await(Future<V> future) {
		try {
			return future.get();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for a download", exception);
		} catch (ExecutionException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			if (cause instanceof Error error) {
				throw error;
			}
			throw new IllegalStateException("Unable to fetch content", cause);
		}
	}

	private static <T, R extends AutoCloseable> void release(Future<Done<T, R>> future) {
		if (future.isCancelled() || !future.isDone()) {
			return;
		}
		try {
			Done<T, R> done = future.get();
			if (done.result() != null) {
				done.result().close();
			}
		} catch (ExecutionException | CancellationException ignored) {
			// The fetch failed or was cancelled, so there is nothing to release.
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		} catch (Exception ignored) {
			// Best effort: the session also deletes anything it still holds when it is discarded.
		}
	}

	private record Done<T, R extends AutoCloseable>(T item, R result) {
	}
}
