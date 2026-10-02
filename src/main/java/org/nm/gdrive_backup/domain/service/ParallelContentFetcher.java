package org.nm.gdrive_backup.domain.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Overlaps the downloads of an ordered list of items while handing the results back one at a time, in list order,
 * on the calling thread. Downloads are latency-bound, so running a few at once is much faster than one by one;
 * everything that is not thread-safe (the ZIP being written, progress reporting) stays on the caller.
 *
 * <p>At most {@code concurrency} fetches run at once and at most {@code 2 * concurrency} results are held
 * unconsumed, which bounds the staged content waiting to be written. With a concurrency of 1 this degrades to
 * fetch, hand over, fetch, hand over, so nothing is fetched before the previous result was consumed.
 */
final class ParallelContentFetcher {

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
	 * Fetches the items that {@code needsFetch} selects and passes every item, in order, to {@code sink}.
	 * Returns {@code false} when {@code stopRequested} ended the run early; results fetched but not yet handed
	 * over are released. A failure in a fetch or in the sink aborts the run the same way and is rethrown.
	 */
	<T, R extends AutoCloseable> boolean process(List<T> items, Predicate<T> needsFetch, Fetch<T, R> fetch,
			BooleanSupplier stopRequested, Sink<T, R> sink) {
		int window = concurrency == 1 ? 1 : 2 * concurrency;
		Semaphore running = new Semaphore(concurrency);
		Deque<Pending<T, R>> pending = new ArrayDeque<>();
		// Closing the executor waits for tasks still running, so no download outlives the call.
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			try {
				int next = 0;
				while (next < items.size() || !pending.isEmpty()) {
					if (stopRequested.getAsBoolean()) {
						return false;
					}
					while (next < items.size() && pending.size() < window) {
						T item = items.get(next++);
						pending.addLast(new Pending<>(item,
								needsFetch.test(item) ? executor.submit(() -> fetchWhenFree(running, fetch, item)) : null));
					}
					Pending<T, R> head = pending.removeFirst();
					sink.accept(head.item, head.future == null ? null : await(head.future));
				}
				return true;
			} catch (RuntimeException | Error exception) {
				throw exception;
			} catch (Exception exception) {
				throw new IllegalStateException("Unable to process fetched content", exception);
			} finally {
				pending.forEach(entry -> {
					if (entry.future != null) {
						entry.future.cancel(true);
					}
				});
			}
		} finally {
			// The executor is closed by now, so each future is settled: release what finished but was never consumed.
			pending.forEach(Pending::release);
		}
	}

	private static <T, R extends AutoCloseable> R fetchWhenFree(Semaphore running, Fetch<T, R> fetch, T item)
			throws Exception {
		running.acquire();
		try {
			return fetch.fetch(item);
		} finally {
			running.release();
		}
	}

	private static <R> R await(Future<R> future) {
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

	private record Pending<T, R extends AutoCloseable>(T item, Future<R> future) {

		void release() {
			if (future == null || future.isCancelled() || !future.isDone()) {
				return;
			}
			try {
				R fetched = future.get();
				if (fetched != null) {
					fetched.close();
				}
			} catch (ExecutionException | CancellationException ignored) {
				// The fetch failed or was cancelled, so there is nothing to release.
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			} catch (Exception ignored) {
				// Best effort: the session also deletes anything it still holds when it is discarded.
			}
		}
	}
}
