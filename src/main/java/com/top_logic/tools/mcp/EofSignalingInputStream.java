/*
 * SPDX-FileCopyrightText: 2026 (c) Business Operation Systems GmbH <info@top-logic.com>
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-BOS-TopLogic-1.0
 */
package com.top_logic.tools.mcp;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;

/**
 * {@link InputStream} that lets a thread wait until the wrapped stream has ended.
 *
 * <p>
 * The stdio transport of the MCP SDK reads requests on its own thread and does not end the
 * server when stdin closes. Wrapping stdin with this stream lets the serve goal notice that the
 * client is gone. End of stream and a read error both count as the end.
 * </p>
 */
final class EofSignalingInputStream extends FilterInputStream {

	private final CountDownLatch _eof = new CountDownLatch(1);

	EofSignalingInputStream(InputStream in) {
		super(in);
	}

	@Override
	public int read() throws IOException {
		return check(() -> super.read());
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		return check(() -> super.read(b, off, len));
	}

	/** Blocks until the wrapped stream has reached its end or failed. */
	void awaitEof() throws InterruptedException {
		_eof.await();
	}

	private int check(Read read) throws IOException {
		int result;
		try {
			result = read.read();
		} catch (IOException ex) {
			_eof.countDown();
			throw ex;
		}
		if (result < 0) {
			_eof.countDown();
		}
		return result;
	}

	@FunctionalInterface
	private interface Read {
		int read() throws IOException;
	}

}
