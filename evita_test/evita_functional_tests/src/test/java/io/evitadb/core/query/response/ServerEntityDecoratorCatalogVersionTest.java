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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies how {@link ServerEntityDecorator} carries the catalog version its data were materialised at.
 *
 * This is not bookkeeping. `EntityCollection#enrichEntityInternal` skips the storage round trip of an enrichment that
 * widens nothing **only** when the decorator's catalog version equals the one the collection is pinned to, and the
 * soundness of that shortcut rests entirely on the rules pinned here: a decorator materialised from storage carries
 * the version it was read at, one that merely wraps another carries the wrapped decorator's version because wrapping
 * performs no read, and a decorator of unknown provenance carries a value that can never compare equal to a real
 * version. A refactor that let a wrapping constructor take a *fresh* version instead of inheriting would make stale
 * data look current, and only the end-to-end scenarios in `EntityEnrichmentVersionGuardFunctionalTest` would have
 * a chance of noticing - at the cost of an embedded instance per scenario.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ServerEntityDecorator catalog version provenance")
@Tag(ENGINE)
@Tag(QUERY)
class ServerEntityDecoratorCatalogVersionTest {
	private static final OffsetDateTime ALIGNED_NOW = OffsetDateTime.now();
	/**
	 * Version the fixtures pretend the entity data were materialised at.
	 */
	private static final long CATALOG_VERSION = 42L;
	/**
	 * Identity of the catalog the fixtures pretend the snapshot belongs to.
	 */
	private static final UUID CATALOG_ID = UUID.randomUUID();

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

	@Nested
	@DisplayName("Provenance rules")
	class ProvenanceRules {

		@DisplayName("Decorator materialised from storage carries the version it was read at")
		@Test
		void shouldCarryTheVersionItWasMaterialisedAt() {
			assertEquals(CATALOG_VERSION, materialised(CATALOG_ID, CATALOG_VERSION).getCatalogVersion());
			assertEquals(CATALOG_ID, materialised(CATALOG_ID, CATALOG_VERSION).getCatalogId());
		}

		@DisplayName("Wrapping a decorator inherits its version instead of taking a fresh one")
		@Test
		void shouldInheritTheVersionOfTheWrappedDecorator() {
			final ServerEntityDecorator wrapped = ServerEntityDecorator.decorate(
				materialised(CATALOG_ID, CATALOG_VERSION),
				null,
				LocaleSerializablePredicate.DEFAULT_INSTANCE,
				HierarchySerializablePredicate.DEFAULT_INSTANCE,
				AttributeValueSerializablePredicate.DEFAULT_INSTANCE,
				AssociatedDataValueSerializablePredicate.DEFAULT_INSTANCE,
				ReferenceContractSerializablePredicate.DEFAULT_INSTANCE,
				PriceContractSerializablePredicate.DEFAULT_INSTANCE,
				ALIGNED_NOW,
				0, 0
			);

			// wrapping reads nothing, so the data are exactly as current as what was wrapped - a fresh version
			// here would silently promote stale data to "current" and let the enrichment shortcut return it
			// untouched
			assertEquals(CATALOG_VERSION, wrapped.getCatalogVersion());
			assertEquals(CATALOG_ID, wrapped.getCatalogId());
		}
	}

	@Nested
	@DisplayName("Unknown provenance")
	class UnknownProvenance {

		@DisplayName("Wrapping a decorator of unknown provenance keeps it unknown")
		@Test
		void shouldNotLaunderUnknownProvenanceThroughWrapping() {
			final ServerEntityDecorator wrapped = ServerEntityDecorator.decorate(
				materialised(null, ServerEntityDecorator.UNKNOWN_CATALOG_VERSION),
				null,
				LocaleSerializablePredicate.DEFAULT_INSTANCE,
				HierarchySerializablePredicate.DEFAULT_INSTANCE,
				AttributeValueSerializablePredicate.DEFAULT_INSTANCE,
				AssociatedDataValueSerializablePredicate.DEFAULT_INSTANCE,
				ReferenceContractSerializablePredicate.DEFAULT_INSTANCE,
				PriceContractSerializablePredicate.DEFAULT_INSTANCE,
				ALIGNED_NOW,
				0, 0
			);

			assertEquals(ServerEntityDecorator.UNKNOWN_CATALOG_VERSION, wrapped.getCatalogVersion());
			assertNull(wrapped.getCatalogId());
		}

		@DisplayName("Unknown provenance never compares equal to a real catalog version")
		@Test
		void shouldNeverCollideWithARealCatalogVersion() {
			// catalog versions come from a monotonically increasing, never-negative sequence, so what holds the
			// guard up is that the sentinel is *negative* - not that it happens to be -1. Asserting the two
			// constants differ would compare two compile-time literals and touch no production code at all, so
			// the second assertion deliberately routes through the field a decorator actually carries
			assertTrue(ServerEntityDecorator.UNKNOWN_CATALOG_VERSION < 0);
			assertTrue(
				materialised(null, ServerEntityDecorator.UNKNOWN_CATALOG_VERSION).getCatalogVersion() < 0
			);
			assertFalse(
				materialised(null, ServerEntityDecorator.UNKNOWN_CATALOG_VERSION)
					.isMaterialisedFrom(CATALOG_ID, CATALOG_VERSION)
			);
		}
	}

	@Nested
	@DisplayName("Snapshot identity")
	class SnapshotIdentity {

		@DisplayName("Data belong to the snapshot they were read from")
		@Test
		void shouldRecogniseTheSnapshotItWasMaterialisedFrom() {
			assertTrue(
				materialised(CATALOG_ID, CATALOG_VERSION).isMaterialisedFrom(CATALOG_ID, CATALOG_VERSION)
			);
		}

		@DisplayName("A newer version of the same catalog is a different snapshot")
		@Test
		void shouldRejectAnotherVersionOfTheSameCatalog() {
			assertFalse(
				materialised(CATALOG_ID, CATALOG_VERSION).isMaterialisedFrom(CATALOG_ID, CATALOG_VERSION + 1)
			);
		}

		@DisplayName("The same version number in another catalog is a different snapshot")
		@Test
		void shouldRejectTheSameVersionOfAnotherCatalog() {
			// versions are numbered per catalog, so two unrelated catalogs routinely sit at the same number - the
			// version alone would let one catalog's entity pass for current in a session for the other
			assertFalse(
				materialised(CATALOG_ID, CATALOG_VERSION).isMaterialisedFrom(UUID.randomUUID(), CATALOG_VERSION)
			);
		}
	}

	/**
	 * Builds a decorator standing for an entity materialised from storage at the passed catalog version.
	 *
	 * @param catalogId      catalog the data are claimed to have come from, NULL for unknown provenance
	 * @param catalogVersion version of the snapshot the data are claimed to have come from
	 * @return the assembled decorator
	 */
	@Nonnull
	private ServerEntityDecorator materialised(@Nullable UUID catalogId, long catalogVersion) {
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
			catalogId,
			catalogVersion,
			0, 0
		);
	}

}
