package org.nm.gdrive_backup.domain.service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Counts the bytes read from a stream and reports the running total to a listener, at most once per interval and
 * once more when the stream ends. A download streams tens of thousands of buffers, and a progress display refreshes
 * a few times a second, so reporting each read would only add cost. Used by one thread, as the stream itself is.
 */
final class ReportingInputStream extends FilterInputStream {

	static final long DEFAULT_INTERVAL_NANOS = 200_000_000L;

	private final LongConsumer listener;
	private final LongSupplier nanoClock;
	private final long intervalNanos;
	private long total;
	private long lastReportNanos;
	private boolean endReported;

	ReportingInputStream(InputStream in, LongConsumer listener) {
		this(in, listener, System::nanoTime, DEFAULT_INTERVAL_NANOS);
	}

	ReportingInputStream(InputStream in, LongConsumer listener, LongSupplier nanoClock, long intervalNanos) {
		super(in);
		this.listener = listener;
		this.nanoClock = nanoClock;
		this.intervalNanos = intervalNanos;
		this.lastReportNanos = nanoClock.getAsLong();
	}

	@Override
	public int read() throws IOException {
		int value = super.read();
		counted(value < 0 ? -1 : 1);
		return value;
	}

	@Override
	public int read(byte[] buffer, int offset, int length) throws IOException {
		int read = super.read(buffer, offset, length);
		counted(read);
		return read;
	}

	@Override
	public long skip(long count) throws IOException {
		long skipped = super.skip(count);
		counted(skipped);
		return skipped;
	}

	/** Mark and reset would make the count meaningless, so they are not offered. */
	@Override
	public boolean markSupported() {
		return false;
	}

	private void counted(long read) {
		if (read < 0) {
			if (!endReported) {
				endReported = true;
				listener.accept(total);
			}
			return;
		}
		total += read;
		long now = nanoClock.getAsLong();
		if (read > 0 && now - lastReportNanos >= intervalNanos) {
			lastReportNanos = now;
			listener.accept(total);
		}
	}
}
