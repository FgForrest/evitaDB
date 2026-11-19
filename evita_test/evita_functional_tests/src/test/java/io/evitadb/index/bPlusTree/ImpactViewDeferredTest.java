/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.index.bPlusTree;

import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Query-time impact views whose bytes a supplier computes only when a reader first needs them. */
@Tag(INDEXING)
@Tag(FULLTEXT)
@DisplayName("Deferred impact view")
class ImpactViewDeferredTest {

	/** A view without postings needs no impacts, so its supplier is never consulted. */
	@Test
	@DisplayName("Returns the shared empty view for no postings without computing impacts")
	void shouldReturnEmptyViewWithoutComputingWhenNoPostings() {
		final AtomicInteger calls = new AtomicInteger();

		final ImpactView view = ImpactView.deferred(0, () -> {
			calls.incrementAndGet();
			return new byte[0];
		});

		assertSame(ImpactView.EMPTY, view);
		assertEquals(0, view.toArray().length);
		assertEquals(0, calls.get());
	}

	/** Cardinality is known up front; the first read computes the bytes and every later read shares them. */
	@Test
	@DisplayName("Computes impacts once, on the first read, and serves every later reader from them")
	void shouldComputeImpactsOnceOnFirstRead() {
		final AtomicInteger calls = new AtomicInteger();
		final byte[] impacts = {3, (byte) 200, 7};
		final ImpactView view = deferred(calls, impacts);

		assertEquals(3, view.size());
		assertEquals(0, calls.get(), "Cardinality is known without computing impacts");

		final ImpactView.Reader first = view.reader();
		final ImpactView.Reader second = view.reader();

		assertEquals(1, calls.get(), "A second reader shares the published impacts");
		assertEquals((byte) 200, first.impactAt(1));
		assertEquals(7, second.impactAt(2));
		assertEquals(3, first.impactAt(0));
		assertArrayEquals(impacts, view.toArray());
		assertEquals(Arrays.toString(impacts), view.toString());
		assertEquals(1, calls.get());
	}

	/** Callers may write the array they receive; the view keeps serving the computed bytes. */
	@Test
	@DisplayName("Hands out a copy of the computed impacts")
	void shouldReturnCopyOfComputedImpacts() {
		final ImpactView view = deferred(new AtomicInteger(), new byte[]{1, 2});

		final byte[] copy = view.toArray();
		copy[0] = 99;

		assertArrayEquals(new byte[]{1, 2}, view.toArray());
		assertEquals(1, view.reader().impactAt(0));
	}

	/** Impacts that do not align with the postings are a programming error, surfaced on the first read. */
	@Test
	@DisplayName("Refuses computed impacts that do not align with the postings")
	void shouldRefuseMisalignedImpacts() {
		final ImpactView view = ImpactView.deferred(2, () -> new byte[]{1});

		final GenericEvitaInternalError error = assertThrows(GenericEvitaInternalError.class, view::toArray);

		assertTrue(error.getMessage().contains("align"), error.getMessage());
		assertThrows(GenericEvitaInternalError.class, view::reader);
	}

	/** Creates a deferred view over a copy of the bytes, counting how often its supplier runs. */
	private static ImpactView deferred(AtomicInteger calls, byte[] impacts) {
		return ImpactView.deferred(impacts.length, () -> {
			calls.incrementAndGet();
			return impacts.clone();
		});
	}
}
