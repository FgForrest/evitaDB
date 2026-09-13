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

package io.evitadb.core.query.response;

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.structure.AssociatedData;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityAttributes;
import io.evitadb.api.requestResponse.data.structure.Prices;
import io.evitadb.api.requestResponse.data.structure.References;
import io.evitadb.api.requestResponse.data.structure.predicate.AssociatedDataValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.AttributeValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.HierarchySerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.LocaleSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.PriceContractSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceContractSerializablePredicate;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.UUID;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies how a {@link ServerEntityDecorator} adds up the I/O its own wrapping performed with the I/O owed by the
 * decorator it wraps.
 *
 * The statistics are resolved lazily and **additively**: a decorator produced by an enrichment step carries the reads
 * that step performed itself, while the reads that produced its input are still owed by the input decorator. Resolving
 * them at the first ask - and only then - is what keeps an entity nobody inspects free of a reference graph walk.
 *
 * The rule that makes the addition safe is that it happens at most once. Both getters resolve, and every consumer of
 * the numbers (the response, the session, traffic recording and the metric events) reads them repeatedly, so a
 * resolution that ran twice would permanently double the entity's reported reads.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(ENGINE)
@Tag(QUERY)
@DisplayName("Deferred I/O statistics of a server entity decorator")
class ServerEntityDecoratorIoStatisticsTest {
	private static final OffsetDateTime ALIGNED_NOW = OffsetDateTime.now();
	/**
	 * Catalog version the fixtures pretend to have been materialised at - arbitrary, since these tests only exercise
	 * the I/O statistics and never compare versions.
	 */
	private static final UUID CATALOG_ID = UUID.randomUUID();
	private static final long CATALOG_VERSION = 1L;

	private EntitySchema productSchema;
	private Entity entity;

	@BeforeEach
	void setUp() {
		this.productSchema = EntitySchema._internalBuild("PRODUCT");
		this.entity = Entity._internalBuild(
			1, 1,
			this.productSchema,
			null,
			new References(this.productSchema),
			new EntityAttributes(this.productSchema),
			new AssociatedData(this.productSchema),
			new Prices(this.productSchema, PriceInnerRecordHandling.NONE),
			Collections.emptySet(),
			Scope.DEFAULT_SCOPE
		);
	}

	/**
	 * Wraps the shared entity in a decorator that performed `ioFetchCount` reads of its own on top of whatever the
	 * passed source still owes.
	 *
	 * @param ioFetchCount               reads this decorator performed itself
	 * @param ioFetchedBytes             Bytes this decorator read itself
	 * @param deferredIoStatisticsSource decorator whose statistics complete this one's, NULL when there is none
	 * @return the assembled decorator
	 */
	@Nonnull
	private ServerEntityDecorator decorator(
		int ioFetchCount,
		int ioFetchedBytes,
		@Nullable ServerEntityDecorator deferredIoStatisticsSource
	) {
		return ServerEntityDecorator.decorate(
			this.entity,
			this.productSchema,
			null,
			LocaleSerializablePredicate.DEFAULT_INSTANCE,
			HierarchySerializablePredicate.DEFAULT_INSTANCE,
			AttributeValueSerializablePredicate.DEFAULT_INSTANCE,
			AssociatedDataValueSerializablePredicate.DEFAULT_INSTANCE,
			ReferenceContractSerializablePredicate.DEFAULT_INSTANCE,
			PriceContractSerializablePredicate.DEFAULT_INSTANCE,
			ALIGNED_NOW,
			CATALOG_ID,
			CATALOG_VERSION,
			ioFetchCount, ioFetchedBytes, deferredIoStatisticsSource,
			// a decorator handed raw counts and no record identities is the fall-back shape this class covers
			null
		);
	}

	@Nested
	@DisplayName("Additive resolution")
	class AdditiveResolutionTest {

		@Test
		@DisplayName("a decorator adds its source's statistics to its own")
		void shouldAddDeferredSourceStatisticsToItsOwn() {
			final ServerEntityDecorator source = decorator(2, 200, null);
			final ServerEntityDecorator wrapping = decorator(3, 300, source);

			assertEquals(5, wrapping.getIoFetchCount());
			assertEquals(500, wrapping.getIoFetchedBytes());
		}

		@Test
		@DisplayName("a decorator that performed no I/O of its own reports only its source's")
		void shouldReportOnlySourceStatisticsForDecoratorWithoutOwnIo() {
			// the shape a limiting or re-decorating step produces - it reads nothing itself, and must not count
			// the chain it wraps a second time
			final ServerEntityDecorator source = decorator(4, 400, null);
			final ServerEntityDecorator wrapping = decorator(0, 0, source);

			assertEquals(4, wrapping.getIoFetchCount());
			assertEquals(400, wrapping.getIoFetchedBytes());
		}

		@Test
		@DisplayName("a decorator with no source reports its own statistics only")
		void shouldReportZeroForDecoratorThatPerformsNoIoOfItsOwn() {
			final ServerEntityDecorator standalone = decorator(0, 0, null);

			assertEquals(0, standalone.getIoFetchCount());
			assertEquals(0, standalone.getIoFetchedBytes());
		}

		@Test
		@DisplayName("statistics chain through several wrappings")
		void shouldChainDeferredStatisticsThroughSeveralWrappings() {
			// models the fetch, enrich and decorate chain: every step owes its own reads and the outermost is the
			// only one anybody asks
			final ServerEntityDecorator fetched = decorator(1, 100, null);
			final ServerEntityDecorator enriched = decorator(2, 200, fetched);
			final ServerEntityDecorator decorated = decorator(4, 400, enriched);

			assertEquals(7, decorated.getIoFetchCount());
			assertEquals(700, decorated.getIoFetchedBytes());
		}
	}

	@Nested
	@DisplayName("Resolve at most once")
	class ResolveAtMostOnceTest {

		@Test
		@DisplayName("repeated reads never grow the numbers")
		void shouldResolveDeferredStatisticsOnlyOnce() {
			// both getters resolve, and every consumer turned into a supplier that reads them more than once - a
			// resolution that ran per call would double the entity's reported reads on the second read
			final ServerEntityDecorator source = decorator(2, 200, null);
			final ServerEntityDecorator wrapping = decorator(3, 300, source);

			assertEquals(5, wrapping.getIoFetchCount());
			assertEquals(5, wrapping.getIoFetchCount());
			assertEquals(500, wrapping.getIoFetchedBytes());
			assertEquals(500, wrapping.getIoFetchedBytes());
			assertEquals(5, wrapping.getIoFetchCount());
		}

		@Test
		@DisplayName("reading the Bytes first does not change what the count reports")
		void shouldResolveDeferredStatisticsOnlyOnceWhenBytesAreReadFirst() {
			final ServerEntityDecorator source = decorator(2, 200, null);
			final ServerEntityDecorator wrapping = decorator(3, 300, source);

			assertEquals(500, wrapping.getIoFetchedBytes());
			assertEquals(5, wrapping.getIoFetchCount());
			assertEquals(500, wrapping.getIoFetchedBytes());
		}

		@Test
		@DisplayName("a source read on its own is still counted once by its wrapper")
		void shouldNotDoubleCountSourceResolvedBeforeItsWrapper() {
			// the source is a decorator of its own and anything may ask it for its numbers first; that must not
			// change what the chain above it reports
			final ServerEntityDecorator fetched = decorator(1, 100, null);
			final ServerEntityDecorator enriched = decorator(2, 200, fetched);
			final ServerEntityDecorator decorated = decorator(4, 400, enriched);

			assertEquals(3, enriched.getIoFetchCount());
			assertEquals(7, decorated.getIoFetchCount());
			assertEquals(3, enriched.getIoFetchCount());
			assertEquals(700, decorated.getIoFetchedBytes());
		}
	}

}
