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

package io.evitadb.index.mutation.local;

import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Set;

import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.utils.CollectionUtils.createHashSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifies that a pending histogram add cannot be redirected to another instance of the same reference target.
 */
@DisplayName("Strict identity of a pending histogram reference")
@Tag(INDEXING)
@Tag(HISTOGRAM)
class HistogramReferenceIdentityTest {

	private static final String REFERENCE_NAME = "brand";
	private static final EntitySchema ENTITY_SCHEMA = EntitySchema._internalBuild("product");
	private static final ReferenceSchema REFERENCE_SCHEMA = ReferenceSchema._internalBuild(
		REFERENCE_NAME, "brand", true, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES, null, false, null, null
	);

	/**
	 * Pending entries distinguish stored, temporary and unknown identities, as well as their business keys.
	 */
	@Test
	@DisplayName("Should keep every component of the pending identity distinct")
	void shouldKeepEveryComponentOfPendingIdentityDistinct() {
		final Set<HistogramReferenceIdentity> pending = createHashSet(6);
		pending.add(new HistogramReferenceIdentity(REFERENCE_NAME, 10, 7));
		pending.add(new HistogramReferenceIdentity(REFERENCE_NAME, 10, -1));
		pending.add(new HistogramReferenceIdentity(REFERENCE_NAME, 10, -2));
		pending.add(new HistogramReferenceIdentity(REFERENCE_NAME, 10, 0));
		pending.add(new HistogramReferenceIdentity(REFERENCE_NAME, 20, 7));
		pending.add(new HistogramReferenceIdentity("category", 10, 7));
		pending.add(new HistogramReferenceIdentity(REFERENCE_NAME, 10, -1));
		assertEquals(6, pending.size());
	}

	/**
	 * The identity taken from a reference key keeps its internal primary key as it is - stored, temporary or
	 * unknown - so a pending entry names the same instance the key does.
	 *
	 * @param internalPrimaryKey the internal primary key of the reference key
	 */
	@ParameterizedTest(name = "internal primary key {0}")
	@ValueSource(ints = {7, -1, 0})
	@DisplayName("Should keep the internal primary key of the reference key exactly")
	void shouldKeepInternalPrimaryKeyOfReferenceKeyExactly(int internalPrimaryKey) {
		final ReferenceKey referenceKey = new ReferenceKey(REFERENCE_NAME, 10, internalPrimaryKey);

		final HistogramReferenceIdentity identity = HistogramReferenceIdentity.of(referenceKey);

		assertEquals(new HistogramReferenceIdentity(REFERENCE_NAME, 10, internalPrimaryKey), identity);
	}

	/**
	 * Removing a stored reference and inserting temporary references to its target keeps their adds independent.
	 */
	@Test
	@DisplayName("Should resolve temporary references beside a dropped stored reference exactly")
	void shouldResolveTemporaryReferencesBesideDroppedStoredReferenceExactly() {
		final Reference secondInserted = reference(-2, false);
		final Reference firstInserted = reference(-1, false);
		final Reference removed = reference(7, true);
		final ReferencesStoragePart storage = new ReferencesStoragePart(
			1, 7, new Reference[]{secondInserted, firstInserted, removed}, -1
		);
		assertSame(firstInserted, new HistogramReferenceIdentity(REFERENCE_NAME, 10, -1).findReference(storage));
		assertSame(secondInserted, new HistogramReferenceIdentity(REFERENCE_NAME, 10, -2).findReference(storage));
		assertSame(removed, new HistogramReferenceIdentity(REFERENCE_NAME, 10, 7).findReference(storage));
		assertNull(new HistogramReferenceIdentity(REFERENCE_NAME, 10, -3).findReference(storage));
	}

	/**
	 * An unknown-key insert may resolve only its own still-unassigned stored instance.
	 */
	@Test
	@DisplayName("Should resolve an unknown insertion without falling back to an older reference")
	void shouldResolveUnknownInsertionWithoutFallingBackToOlderReference() {
		final Reference inserted = reference(0, false);
		assertSame(inserted, new HistogramReferenceIdentity(REFERENCE_NAME, 10, 0).findReference(
			new ReferencesStoragePart(1, 7, new Reference[]{inserted}, -1)
		));
		assertNull(new HistogramReferenceIdentity(REFERENCE_NAME, 10, 0).findReference(
			new ReferencesStoragePart(1, 7, new Reference[]{reference(7, true)}, -1)
		));
	}

	/**
	 * A stored or temporary identity must not resolve to an insert whose internal primary key is still unknown, even
	 * though the storage lookup treats an unknown internal primary key as equal to any other one of the same target.
	 */
	@Test
	@DisplayName("Should not resolve a stored or temporary identity to an unknown insertion")
	void shouldNotResolveStoredOrTemporaryIdentityToUnknownInsertion() {
		final ReferencesStoragePart storage = new ReferencesStoragePart(
			1, 7, new Reference[]{reference(0, false)}, -1
		);

		assertNull(new HistogramReferenceIdentity(REFERENCE_NAME, 10, 7).findReference(storage));
		assertNull(new HistogramReferenceIdentity(REFERENCE_NAME, 10, -1).findReference(storage));
	}

	/**
	 * Builds a reference instance without attributes for exact-identity resolution.
	 */
	@Nonnull
	private static Reference reference(int internalPrimaryKey, boolean dropped) {
		return new Reference(
			ENTITY_SCHEMA, REFERENCE_SCHEMA, 1,
			new ReferenceKey(REFERENCE_NAME, 10, internalPrimaryKey), null, Map.of(), dropped
		);
	}
}
