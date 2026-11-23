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
 * Releases a latch when the client closes the input stream of an MCP session. The SDK's stream
 * transport only ends its session then, so without this the server would outlive its client:
 * the MCP client (or a launcher killed without cleanup) closing the pipe or socket must be
 * enough to end the session.
 */
final class EofSignal extends FilterInputStream {

	private final CountDownLatch _eof;

	EofSignal(InputStream in, CountDownLatch eof) {
		super(in);
		_eof = eof;
	}

	@Override
	public int read() throws IOException {
		try {
			return signal(super.read());
		} catch (IOException ex) {
			_eof.countDown();
			throw ex;
		}
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		try {
			return signal(super.read(b, off, len));
		} catch (IOException ex) {
			_eof.countDown();
			throw ex;
		}
	}

	private int signal(int result) {
		if (result < 0) {
			_eof.countDown();
		}
		return result;
	}

	@Override
	public void close() throws IOException {
		_eof.countDown();
		super.close();
	}
}
