/*
 * SPDX-FileCopyrightText: 2026 (c) Business Operation Systems GmbH <info@top-logic.com>
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-BOS-TopLogic-1.0
 */
package com.top_logic.tools.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class EofSignalingInputStreamTest {

	@Test
	void signalsOnlyAtEndOfStream() throws Exception {
		EofSignalingInputStream in = new EofSignalingInputStream(
			new ByteArrayInputStream("{}\n".getBytes(StandardCharsets.UTF_8)));
		Thread waiter = startWaiter(in);

		byte[] buffer = new byte[2];
		assertEquals(2, in.read(buffer, 0, 2));
		assertEquals('\n', in.read());
		waiter.join(200);
		assertTrue(waiter.isAlive(), "data left to read must not count as end of stream");

		assertEquals(-1, in.read(buffer, 0, 2));
		waiter.join(TimeUnit.SECONDS.toMillis(5));
		assertFalse(waiter.isAlive(), "end of stream must release the waiter");
	}

	@Test
	void signalsOnReadError() throws Exception {
		EofSignalingInputStream in = new EofSignalingInputStream(new InputStream() {
			@Override
			public int read() throws IOException {
				throw new IOException("broken pipe");
			}
		});
		Thread waiter = startWaiter(in);

		assertThrows(IOException.class, in::read);
		waiter.join(TimeUnit.SECONDS.toMillis(5));
		assertFalse(waiter.isAlive(), "a read error must release the waiter");
	}

	private static Thread startWaiter(EofSignalingInputStream in) {
		Thread waiter = new Thread(() -> {
			try {
				in.awaitEof();
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});
		waiter.setDaemon(true);
		waiter.start();
		return waiter;
	}

}
