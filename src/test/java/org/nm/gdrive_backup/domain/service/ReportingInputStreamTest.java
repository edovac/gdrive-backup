package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class ReportingInputStreamTest {

	private static final long INTERVAL = 100;

	private final List<Long> reported = new ArrayList<>();
	private final AtomicLong now = new AtomicLong(1_000);

	private ReportingInputStream stream(byte[] content) {
		return new ReportingInputStream(new ByteArrayInputStream(content), reported::add, now::get, INTERVAL);
	}

	@Test
	void countsEveryByteWhicheverWayItIsRead() throws IOException {
		try (ReportingInputStream in = stream(new byte[100])) {
			in.read();
			in.read(new byte[10]);
			in.read(new byte[20], 5, 7);
			in.skip(3);
			// 1 + 10 + 7 + 3 read so far; the rest is drained to the end.
			in.readAllBytes();
		}

		assertEquals(List.of(100L), reported, "no interval has passed, so only the end of the stream reports");
	}

	@Test
	void reportsTheRunningTotalOncePerInterval() throws IOException {
		try (ReportingInputStream in = stream(new byte[1_000])) {
			in.read(new byte[10]);
			assertEquals(List.of(), reported, "too soon after the start");

			now.addAndGet(INTERVAL);
			in.read(new byte[10]);
			assertEquals(List.of(20L), reported);

			now.addAndGet(INTERVAL - 1);
			in.read(new byte[10]);
			assertEquals(List.of(20L), reported, "still inside the interval");

			now.addAndGet(1);
			in.read(new byte[10]);
			assertEquals(List.of(20L, 40L), reported);
		}
	}

	@Test
	void reportsTheFinalTotalAtTheEndOfTheStreamEvenInsideAnInterval() throws IOException {
		try (ReportingInputStream in = stream(new byte[25])) {
			in.read(new byte[10]);
			in.readAllBytes();
		}

		assertEquals(List.of(25L), reported);
	}

	@Test
	void reportsTheEndOnlyOnceHoweverOftenItIsReadAgain() throws IOException {
		try (ReportingInputStream in = stream(new byte[5])) {
			in.readAllBytes();
			in.read();
			in.read(new byte[4]);
		}

		assertEquals(List.of(5L), reported);
	}

	@Test
	void anEmptyStreamReportsZeroAtTheEnd() throws IOException {
		try (ReportingInputStream in = stream(new byte[0])) {
			assertEquals(-1, in.read());
		}

		assertEquals(List.of(0L), reported);
	}

	@Test
	void aReadOfNothingDoesNotReport() throws IOException {
		try (ReportingInputStream in = stream(new byte[10])) {
			now.addAndGet(INTERVAL * 5);
			assertEquals(0, in.read(new byte[4], 0, 0));
		}

		assertEquals(List.of(), reported);
	}

	@Test
	void countsTheBytesATransferToCopies() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ReportingInputStream in = stream(new byte[50_000])) {
			assertEquals(50_000, in.transferTo(out));
		}

		assertEquals(50_000, out.size());
		assertEquals(50_000L, reported.getLast());
	}

	@Test
	void theTotalsNeverGoDown() throws IOException {
		try (ReportingInputStream in = stream(new byte[10_000])) {
			byte[] buffer = new byte[100];
			while (in.read(buffer) >= 0) {
				now.addAndGet(INTERVAL);
			}
		}

		for (int i = 1; i < reported.size(); i++) {
			assertTrue(reported.get(i) >= reported.get(i - 1));
		}
		assertEquals(10_000L, reported.getLast());
	}

	@Test
	void doesNotOfferMarkAndClosesTheStreamItWraps() throws IOException {
		AtomicBoolean closed = new AtomicBoolean();
		InputStream wrapped = new ByteArrayInputStream(new byte[1]) {
			@Override
			public void close() throws IOException {
				closed.set(true);
				super.close();
			}
		};

		try (ReportingInputStream in = new ReportingInputStream(wrapped, reported::add)) {
			assertFalse(in.markSupported());
		}

		assertTrue(closed.get());
	}
}
