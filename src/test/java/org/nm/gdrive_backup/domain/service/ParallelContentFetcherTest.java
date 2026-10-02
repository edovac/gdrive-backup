package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class ParallelContentFetcherTest {

	private final List<Result> created = Collections.synchronizedList(new ArrayList<>());

	@Test
	void rejectsAConcurrencyBelowOne() {
		assertThrows(IllegalArgumentException.class, () -> new ParallelContentFetcher(0));
	}

	@Test
	void handsResultsOverInListOrderEvenWhenFetchesFinishOutOfOrder() {
		List<Integer> items = items(8);
		List<Integer> handedOver = new ArrayList<>();

		boolean completed = new ParallelContentFetcher(4).process(items, item -> true,
				item -> {
					// Earlier items take longer, so they finish after the later ones.
					Thread.sleep((8 - item) * 15L);
					return result(item);
				},
				() -> false,
				(item, fetched) -> {
					handedOver.add(fetched.item);
					fetched.close();
				});

		assertTrue(completed);
		assertEquals(items, handedOver);
		assertTrue(created.stream().allMatch(result -> result.closed));
	}

	@Test
	void runsFetchesAtTheSameTime() {
		int concurrency = 3;
		CountDownLatch allStarted = new CountDownLatch(concurrency);

		boolean completed = new ParallelContentFetcher(concurrency).process(items(concurrency), item -> true,
				item -> {
					allStarted.countDown();
					// Only returns once every other fetch has started, which a one-at-a-time run can never do.
					if (!allStarted.await(5, TimeUnit.SECONDS)) {
						throw new IllegalStateException("fetches did not overlap");
					}
					return result(item);
				},
				() -> false,
				(item, fetched) -> fetched.close());

		assertTrue(completed);
	}

	@Test
	void neverRunsMoreFetchesThanTheConcurrency() {
		AtomicInteger running = new AtomicInteger();
		AtomicInteger mostRunning = new AtomicInteger();

		new ParallelContentFetcher(3).process(items(15), item -> true,
				item -> {
					mostRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
					try {
						Thread.sleep(20);
					} finally {
						running.decrementAndGet();
					}
					return result(item);
				},
				() -> false,
				(item, fetched) -> fetched.close());

		assertTrue(mostRunning.get() <= 3, "ran " + mostRunning.get() + " fetches at once");
		assertTrue(mostRunning.get() > 1, "fetches never overlapped");
	}

	@Test
	void holdsAtMostTwiceTheConcurrencyOfUnconsumedResults() {
		int concurrency = 2;
		AtomicInteger started = new AtomicInteger();
		AtomicInteger consumed = new AtomicInteger();
		AtomicInteger mostAhead = new AtomicInteger();

		new ParallelContentFetcher(concurrency).process(items(20), item -> true,
				item -> {
					started.incrementAndGet();
					return result(item);
				},
				() -> false,
				(item, fetched) -> {
					mostAhead.accumulateAndGet(started.get() - consumed.get(), Math::max);
					consumed.incrementAndGet();
					fetched.close();
				});

		assertTrue(mostAhead.get() <= 2 * concurrency, "fetched " + mostAhead.get() + " ahead of the consumer");
	}

	@Test
	void aConcurrencyOfOneNeverFetchesAheadOfTheConsumer() {
		List<String> events = Collections.synchronizedList(new ArrayList<>());

		new ParallelContentFetcher(1).process(items(3), item -> true,
				item -> {
					events.add("fetch " + item);
					return result(item);
				},
				() -> false,
				(item, fetched) -> {
					events.add("sink " + item);
					fetched.close();
				});

		assertEquals(List.of("fetch 0", "sink 0", "fetch 1", "sink 1", "fetch 2", "sink 2"), events);
	}

	@Test
	void skipsTheFetchForItemsThatNeedNoneButStillHandsThemOverInOrder() {
		List<String> handedOver = new ArrayList<>();
		AtomicInteger fetches = new AtomicInteger();

		new ParallelContentFetcher(3).process(items(6), item -> item % 2 == 0,
				item -> {
					fetches.incrementAndGet();
					return result(item);
				},
				() -> false,
				(item, fetched) -> {
					handedOver.add(item + (fetched == null ? ":none" : ":fetched"));
					if (fetched != null) {
						fetched.close();
					}
				});

		assertEquals(3, fetches.get());
		assertEquals(List.of("0:fetched", "1:none", "2:fetched", "3:none", "4:fetched", "5:none"), handedOver);
	}

	@Test
	void anEmptyListCompletesWithoutFetching() {
		assertTrue(new ParallelContentFetcher(4).process(List.<Integer>of(), item -> true, item -> result(item),
				() -> false, (item, fetched) -> {
					throw new AssertionError("nothing to hand over");
				}));
	}

	@Test
	void aStopRequestEndsTheRunAndReleasesResultsFetchedButNotYetHandedOver() {
		int concurrency = 2;
		AtomicInteger finished = new AtomicInteger();
		boolean[] stop = { false };

		boolean completed = new ParallelContentFetcher(concurrency).process(items(10), item -> true,
				item -> {
					Result fetched = result(item);
					finished.incrementAndGet();
					return fetched;
				},
				() -> stop[0],
				(item, fetched) -> {
					fetched.close();
					// Hold the stop until the fetches already submitted (item 0 to 2 * concurrency - 1) are done.
					awaitCount(finished, 2 * concurrency);
					stop[0] = true;
				});

		assertFalse(completed);
		assertEquals(2 * concurrency, created.size(), "nothing past the window was fetched");
		assertTrue(created.stream().allMatch(result -> result.closed), "every fetched result was released");
	}

	@Test
	void aFailedFetchAbortsTheRunRethrowsAndReleasesTheRest() {
		AtomicInteger finished = new AtomicInteger();
		List<Integer> handedOver = new ArrayList<>();

		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> new ParallelContentFetcher(3).process(items(10), item -> true,
						item -> {
							if (item == 2) {
								// Fails once its neighbours in the window have been fetched.
								awaitCount(finished, 5);
								throw new IllegalStateException("connection reset");
							}
							Result fetched = result(item);
							finished.incrementAndGet();
							return fetched;
						},
						() -> false,
						(item, fetched) -> {
							handedOver.add(item);
							fetched.close();
						}));

		assertEquals("connection reset", failure.getMessage());
		assertEquals(List.of(0, 1), handedOver);
		assertTrue(created.stream().allMatch(result -> result.closed), "every fetched result was released");
	}

	@Test
	void aCheckedFetchFailureSurfacesAsAnIllegalStateException() {
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> new ParallelContentFetcher(2).process(items(3), item -> true,
						item -> {
							throw new IOException("disk full");
						},
						() -> false,
						(item, fetched) -> {
						}));

		assertInstanceOf(IOException.class, failure.getCause());
	}

	@Test
	void aFailingConsumerAbortsTheRunAndSurfacesCheckedFailuresAsIllegalState() {
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> new ParallelContentFetcher(1).process(items(3), item -> true,
						item -> result(item),
						() -> false,
						(item, fetched) -> {
							fetched.close();
							throw new IOException("cannot write entry");
						}));

		assertInstanceOf(IOException.class, failure.getCause());
		assertEquals(1, created.size(), "nothing was fetched after the failure");
	}

	@Test
	void aStopRequestedBeforeStartingFetchesNothing() {
		boolean completed = new ParallelContentFetcher(4).process(items(5), item -> true,
				item -> result(item),
				() -> true,
				(item, fetched) -> {
					throw new AssertionError("nothing should be handed over");
				});

		assertFalse(completed);
		assertTrue(created.isEmpty());
	}

	@Test
	void everyItemIsHandedOverExactlyOnceUnderHeavyParallelism() {
		List<Integer> items = items(200);
		List<Integer> handedOver = new ArrayList<>();

		boolean completed = new ParallelContentFetcher(16).process(items, item -> true,
				item -> result(item),
				() -> false,
				(item, fetched) -> {
					handedOver.add(fetched.item);
					fetched.close();
				});

		assertTrue(completed);
		assertEquals(items, handedOver);
		assertNull(created.stream().filter(result -> !result.closed).findFirst().orElse(null));
	}

	private Result result(int item) {
		Result result = new Result(item);
		created.add(result);
		return result;
	}

	private static List<Integer> items(int count) {
		return IntStream.range(0, count).boxed().toList();
	}

	/** Waits for fetches to finish, then a moment longer so their futures have settled before a cancel can race them. */
	private static void awaitCount(AtomicInteger counter, int target) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (counter.get() < target) {
			if (System.nanoTime() > deadline) {
				throw new IllegalStateException("fetches did not finish: " + counter.get() + " of " + target);
			}
			Thread.sleep(5);
		}
		Thread.sleep(50);
	}

	private static final class Result implements AutoCloseable {

		final int item;
		volatile boolean closed;

		Result(int item) {
			this.item = item;
		}

		@Override
		public void close() {
			closed = true;
		}
	}
}
