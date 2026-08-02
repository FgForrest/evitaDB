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

package io.evitadb.api.requestResponse.cdc;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Groups a flat, ordered {@link ChangeCatalogCapture} stream so that a consumer paging over it never splits
 * a single entity/schema mutation from the local-mutation captures it produced. There is no separate
 * "record level" in the CDC model - only three real levels:
 * <ul>
 *     <li><b>transaction level</b> - identified by {@link ChangeCatalogCapture#version()} alone; index 0
 *     within that version is the transaction lead event and carries no local mutations of its own;</li>
 *     <li><b>entity/schema-mutation level</b> - every other index within a version identifies one
 *     top-level {@code EntityMutation} ({@link CaptureArea#DATA}) or {@code LocalCatalogSchemaMutation}
 *     ({@link CaptureArea#SCHEMA}/{@link CaptureArea#INFRASTRUCTURE}); {@code MutationPredicateContext.advance()}
 *     assigns this index and is called exactly once per such mutation;</li>
 *     <li><b>local-mutation level</b> - the individual {@code LocalMutation}s an {@code EntityMutation} fans
 *     out into (attribute/price/associated-data changes, etc.); these are captured under their enclosing
 *     entity mutation's {@code (version, index)} instead of advancing the index themselves, via
 *     {@code MutationPredicateContext.doNotAdvance()}.</li>
 * </ul>
 * A "record", in this class's own paging vocabulary, is simply a maximal run of consecutive captures sharing
 * one {@code (version, index)} pair - i.e. exactly the entity/schema-mutation level: either the lone
 * transaction-lead capture by itself, or one entity/schema-mutation capture plus every local-mutation capture
 * it produced. Records never interleave with each other in the source stream, because the index is only ever
 * advanced once per entity/schema mutation (or once for the transaction lead event) and held fixed while that
 * mutation's own local mutations are emitted.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ChangeCatalogCaptureRecords {

	private ChangeCatalogCaptureRecords() {
	}

	/**
	 * Groups the given stream into its logical records, preserving source order. The returned stream is lazy -
	 * it pulls from {@code captures} only as far as necessary - and closing it also closes {@code captures}.
	 *
	 * @param captures the flat, ordered capture stream to group
	 * @return a stream of records, each a non-empty list of captures sharing one {@code (version, index)} pair
	 */
	@Nonnull
	public static Stream<List<ChangeCatalogCapture>> groupIntoRecords(@Nonnull Stream<ChangeCatalogCapture> captures) {
		final RecordGroupingIterator iterator = new RecordGroupingIterator(captures.iterator());
		return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED), false)
			.onClose(captures::close);
	}

	/**
	 * Lazily collapses consecutive captures sharing one {@code (version, index)} pair into a single record,
	 * pulling from the source only as far as needed to detect where one record ends and the next begins.
	 */
	private static final class RecordGroupingIterator implements Iterator<List<ChangeCatalogCapture>> {

		private final Iterator<ChangeCatalogCapture> source;
		@Nullable private ChangeCatalogCapture lookahead;

		RecordGroupingIterator(@Nonnull Iterator<ChangeCatalogCapture> source) {
			this.source = source;
			this.lookahead = source.hasNext() ? source.next() : null;
		}

		@Override
		public boolean hasNext() {
			return this.lookahead != null;
		}

		@Nonnull
		@Override
		public List<ChangeCatalogCapture> next() {
			if (this.lookahead == null) {
				throw new NoSuchElementException();
			}
			final long version = this.lookahead.version();
			final int index = this.lookahead.index();
			final List<ChangeCatalogCapture> record = new ArrayList<>(4);
			record.add(this.lookahead);
			this.lookahead = null;
			while (this.source.hasNext()) {
				final ChangeCatalogCapture next = this.source.next();
				if (next.version() == version && next.index() == index) {
					record.add(next);
				} else {
					this.lookahead = next;
					break;
				}
			}
			return record;
		}
	}
}
