/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.index.mutation.local.dataAccess;

import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.dataType.Scope;
import io.evitadb.spi.store.catalog.persistence.accessor.WritableEntityStorageContainerAccessor;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AssociatedDataStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AttributesStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityBodyStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.PricesStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization (regression) tests for
 * {@link ReferenceEntityStoragePartAccessorAttributeValueByRepresentativeReferenceKeySupplier}.
 *
 * The supplier resolves a single {@link io.evitadb.api.requestResponse.data.ReferenceContract} out of the
 * entity's {@link ReferencesStoragePart} — the one whose
 * {@link RepresentativeReferenceKey} equals either the current or the stored key it was constructed with — and
 * exposes that reference's existing (non-dropped) attribute values. For a reference schema whose cardinality
 * {@link Cardinality#allowsDuplicates()} the key is built from the representative attribute values of each
 * candidate reference, so that two references sharing the same {@link ReferenceKey} (same reference name and
 * primary key) are disambiguated by those values; otherwise the plain {@link ReferenceKey} is used.
 *
 * These tests pin the current, observable behavior of the supplier so that a subsequent allocation optimization
 * of its private resolution logic can be verified not to change semantics. All assertions go through the public
 * {@link ExistingAttributeValueSupplier} surface — {@code getAttributeValue}, {@code getAttributeValues},
 * {@code getAttributeValues(Locale)} and {@code getEntityExistingAttributeLocales}.
 *
 * The tests do not boot an evitaDB instance: they hand-build schemas, references and a minimal
 * {@link WritableEntityStorageContainerAccessor} stub whose only meaningful behavior is to return a chosen
 * {@link ReferencesStoragePart} (and an {@link EntityBodyStoragePart} for the locales path).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ReferenceEntityStoragePartAccessorAttributeValueByRepresentativeReferenceKeySupplier — existing attribute resolution")
@Tag(INDEXING)
@Tag(REFERENCE)
@Tag(ATTRIBUTE)
class ReferenceEntityStoragePartAccessorAttributeValueByRepresentativeReferenceKeySupplierTest {
	private static final String ENTITY_TYPE = "product";
	private static final int ENTITY_PK = 1;
	private static final String REFERENCE_NAME = "brand";
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_OLD_CODE = "oldCode";
	private static final String ATTRIBUTE_VARIANT = "variant";
	private static final String ATTRIBUTE_LABEL = "label";
	/**
	 * Shared, immutable, minimal entity schema owning the references. It carries no attributes of its own — the
	 * reference schema alone defines the attributes the supplier ever resolves — and is safe to share read-only
	 * across all tests.
	 */
	private static final EntitySchema ENTITY_SCHEMA = EntitySchema._internalBuild(ENTITY_TYPE);

	@Nested
	@DisplayName("Reference resolution by key")
	class ReferenceResolution {

		@Test
		@DisplayName("should resolve the matching reference's attributes for a non-duplicate schema")
		void shouldResolveReferenceAttributesWhenSingleNonDuplicateMatch() {
			final ReferenceSchema referenceSchema = referenceSchema(
				Cardinality.ZERO_OR_ONE, Map.of(ATTRIBUTE_CODE, attribute(ATTRIBUTE_CODE, false, false))
			);
			final Reference reference = reference(
				referenceSchema, 10, 1, false,
				attributeValues(new AttributeValue(new AttributeKey(ATTRIBUTE_CODE), "acme"))
			);
			final StubContainerAccessor accessor = new StubContainerAccessor(storagePart(reference));
			final ExistingAttributeValueSupplier supplier = supplier(
				accessor, referenceSchema, key(999), key(10)
			);

			final Optional<AttributeValue> resolved = supplier.getAttributeValue(new AttributeKey(ATTRIBUTE_CODE));

			assertTrue(resolved.isPresent(), "The matching reference must be resolved");
			assertEquals("acme", resolved.get().value());
			assertSingleValue(supplier.getAttributeValues(), ATTRIBUTE_CODE, "acme");
		}

		@Test
		@DisplayName("should resolve the reference matching the stored key when the current key is absent")
		void shouldResolveReferenceMatchingStoredKeyWhenCurrentKeyAbsent() {
			final ReferenceSchema referenceSchema = referenceSchema(
				Cardinality.ZERO_OR_ONE, Map.of(ATTRIBUTE_CODE, attribute(ATTRIBUTE_CODE, false, false))
			);
			final Reference reference = reference(
				referenceSchema, 10, 1, false,
				attributeValues(new AttributeValue(new AttributeKey(ATTRIBUTE_CODE), "acme"))
			);
			final StubContainerAccessor accessor = new StubContainerAccessor(storagePart(reference));
			// current key points at a non-existing reference (pk 999), only the stored key (pk 10) matches
			final ExistingAttributeValueSupplier supplier = supplier(
				accessor, referenceSchema, key(10), key(999)
			);

			final Optional<AttributeValue> resolved = supplier.getAttributeValue(new AttributeKey(ATTRIBUTE_CODE));

			assertTrue(resolved.isPresent(), "The reference matching the stored key must be resolved");
			assertEquals("acme", resolved.get().value());
		}

		@Test
		@DisplayName("should return empty results when no reference matches either key")
		void shouldReturnEmptyWhenNoReferenceMatches() {
			final ReferenceSchema referenceSchema = referenceSchema(
				Cardinality.ZERO_OR_ONE, Map.of(ATTRIBUTE_CODE, attribute(ATTRIBUTE_CODE, false, false))
			);
			final Reference reference = reference(
				referenceSchema, 10, 1, false,
				attributeValues(new AttributeValue(new AttributeKey(ATTRIBUTE_CODE), "acme"))
			);
			final StubContainerAccessor accessor = new StubContainerAccessor(storagePart(reference));
			// neither the stored nor the current key matches the only present reference (pk 10)
			final ExistingAttributeValueSupplier supplier = supplier(
				accessor, referenceSchema, key(998), key(999)
			);

			assertTrue(
				supplier.getAttributeValue(new AttributeKey(ATTRIBUTE_CODE)).isEmpty(),
				"No attribute value must be returned when no reference matches"
			);
			assertEquals(
				0, supplier.getAttributeValues().count(),
				"The attribute value stream must be empty when no reference matches"
			);
		}

		@Test
		@DisplayName("should skip a dropped reference and resolve the live reference sharing the same key")
		void shouldResolveLiveReferenceAndSkipDroppedReferenceWithSameKey() {
			final ReferenceSchema referenceSchema = referenceSchema(
				Cardinality.ZERO_OR_ONE, Map.of(ATTRIBUTE_CODE, attribute(ATTRIBUTE_CODE, false, false))
			);
			// a dropped tombstone (listed first) and a live replacement, both with business key (brand, 10)
			final Reference dropped = reference(
				referenceSchema, 10, 1, true,
				attributeValues(new AttributeValue(new AttributeKey(ATTRIBUTE_CODE), "stale"))
			);
			final Reference live = reference(
				referenceSchema, 10, 2, false,
				attributeValues(new AttributeValue(new AttributeKey(ATTRIBUTE_CODE), "fresh"))
			);
			final StubContainerAccessor accessor = new StubContainerAccessor(storagePart(dropped, live));
			final ExistingAttributeValueSupplier supplier = supplier(
				accessor, referenceSchema, key(999), key(10)
			);

			final Optional<AttributeValue> resolved = supplier.getAttributeValue(new AttributeKey(ATTRIBUTE_CODE));

			assertTrue(resolved.isPresent(), "The live reference must be resolved even though a dropped one precedes it");
			assertEquals(
				"fresh", resolved.get().value(),
				"The dropped reference must be skipped and the live reference resolved"
			);
		}
	}

	@Nested
	@DisplayName("Duplicate-cardinality disambiguation by representative values")
	class DuplicateDisambiguation {

		@Test
		@DisplayName("should resolve the second reference when the key targets its representative values")
		void shouldResolveSecondReferenceWhenKeyTargetsItsRepresentativeValues() {
			final ReferenceSchema referenceSchema = duplicateSchema();
			// two references with the SAME business key (brand, 10) but different representative variant values;
			// the "red" reference is listed FIRST on purpose so a naive "match by ReferenceKey only" would pick it
			final Reference red = reference(
				referenceSchema, 10, 1, false,
				attributeValues(new AttributeValue(new AttributeKey(ATTRIBUTE_VARIANT), "red"))
			);
			final Reference blue = reference(
				referenceSchema, 10, 2, false,
				attributeValues(new AttributeValue(new AttributeKey(ATTRIBUTE_VARIANT), "blue"))
			);
			final StubContainerAccessor accessor = new StubContainerAccessor(storagePart(red, blue));
			final ExistingAttributeValueSupplier supplier = supplier(
				accessor, referenceSchema, key(999), key(10, "blue")
			);

			final Optional<AttributeValue> resolved = supplier.getAttributeValue(new AttributeKey(ATTRIBUTE_VARIANT));

			assertTrue(resolved.isPresent(), "The reference matching the representative values must be resolved");
			assertEquals(
				"blue", resolved.get().value(),
				"The key targeting the second reference's representative values must resolve that reference, not the first"
			);
		}

		@Test
		@DisplayName("should resolve the first reference when the key targets its representative values")
		void shouldResolveFirstReferenceWhenKeyTargetsItsRepresentativeValues() {
			final ReferenceSchema referenceSchema = duplicateSchema();
			final Reference red = reference(
				referenceSchema, 10, 1, false,
				attributeValues(new AttributeValue(new AttributeKey(ATTRIBUTE_VARIANT), "red"))
			);
			final Reference blue = reference(
				referenceSchema, 10, 2, false,
				attributeValues(new AttributeValue(new AttributeKey(ATTRIBUTE_VARIANT), "blue"))
			);
			final StubContainerAccessor accessor = new StubContainerAccessor(storagePart(red, blue));
			final ExistingAttributeValueSupplier supplier = supplier(
				accessor, referenceSchema, key(999), key(10, "red")
			);

			final Optional<AttributeValue> resolved = supplier.getAttributeValue(new AttributeKey(ATTRIBUTE_VARIANT));

			assertTrue(resolved.isPresent(), "The reference matching the representative values must be resolved");
			assertEquals(
				"red", resolved.get().value(),
				"The key targeting the first reference's representative values must resolve that reference"
			);
		}
	}

	@Nested
	@DisplayName("Attribute value filtering")
	class AttributeValueFiltering {

		@Test
		@DisplayName("should exclude a dropped attribute value while returning a present one")
		void shouldExcludeDroppedAttributeValueFromGetAttributeValue() {
			final ReferenceSchema referenceSchema = referenceSchema(
				Cardinality.ZERO_OR_ONE,
				Map.of(
					ATTRIBUTE_CODE, attribute(ATTRIBUTE_CODE, false, false),
					ATTRIBUTE_OLD_CODE, attribute(ATTRIBUTE_OLD_CODE, false, false)
				)
			);
			final Reference reference = reference(
				referenceSchema, 10, 1, false,
				attributeValues(
					new AttributeValue(new AttributeKey(ATTRIBUTE_CODE), "present"),
					// dropped attribute (tombstone) — version 1, dropped == true
					new AttributeValue(1, new AttributeKey(ATTRIBUTE_OLD_CODE), "obsolete", true)
				)
			);
			final StubContainerAccessor accessor = new StubContainerAccessor(storagePart(reference));
			final ExistingAttributeValueSupplier supplier = supplier(
				accessor, referenceSchema, key(999), key(10)
			);

			final Optional<AttributeValue> present = supplier.getAttributeValue(new AttributeKey(ATTRIBUTE_CODE));
			assertTrue(present.isPresent(), "The present attribute value must be returned");
			assertEquals("present", present.get().value());

			assertTrue(
				supplier.getAttributeValue(new AttributeKey(ATTRIBUTE_OLD_CODE)).isEmpty(),
				"A dropped attribute value must not be returned"
			);
		}

		@Test
		@DisplayName("should return only the requested locale's attribute values")
		void shouldReturnOnlyRequestedLocaleValuesFromGetAttributeValues() {
			final ReferenceSchema referenceSchema = referenceSchema(
				Cardinality.ZERO_OR_ONE,
				Map.of(
					ATTRIBUTE_LABEL, attribute(ATTRIBUTE_LABEL, true, false),
					ATTRIBUTE_CODE, attribute(ATTRIBUTE_CODE, false, false)
				)
			);
			final Reference reference = reference(
				referenceSchema, 10, 1, false,
				attributeValues(
					new AttributeValue(new AttributeKey(ATTRIBUTE_LABEL, Locale.ENGLISH), "hello"),
					new AttributeValue(new AttributeKey(ATTRIBUTE_LABEL, Locale.GERMAN), "hallo"),
					// a locale-agnostic (global) value that must be excluded by the locale filter
					new AttributeValue(new AttributeKey(ATTRIBUTE_CODE), "global")
				)
			);
			final StubContainerAccessor accessor = new StubContainerAccessor(storagePart(reference));
			final ExistingAttributeValueSupplier supplier = supplier(
				accessor, referenceSchema, key(999), key(10)
			);

			final List<AttributeValue> englishValues = supplier.getAttributeValues(Locale.ENGLISH).toList();

			assertEquals(1, englishValues.size(), "Only the English value must be returned");
			assertEquals(Locale.ENGLISH, englishValues.get(0).key().locale());
			assertEquals("hello", englishValues.get(0).value());
		}
	}

	@Nested
	@DisplayName("Memoization and obsolescence")
	class Memoization {

		@Test
		@DisplayName("should re-resolve the reference after the underlying references are replaced")
		void shouldReResolveReferenceAfterUnderlyingReferencesAreReplaced() {
			final ReferenceSchema referenceSchema = referenceSchema(
				Cardinality.ZERO_OR_ONE, Map.of(ATTRIBUTE_CODE, attribute(ATTRIBUTE_CODE, false, false))
			);
			final Reference original = reference(
				referenceSchema, 10, 1, false,
				attributeValues(new AttributeValue(new AttributeKey(ATTRIBUTE_CODE), "v1"))
			);
			final StubContainerAccessor accessor = new StubContainerAccessor(storagePart(original));
			final ExistingAttributeValueSupplier supplier = supplier(
				accessor, referenceSchema, key(999), key(10)
			);

			final Optional<AttributeValue> firstRead = supplier.getAttributeValue(new AttributeKey(ATTRIBUTE_CODE));
			assertTrue(firstRead.isPresent());
			assertEquals("v1", firstRead.get().value(), "First read must observe the original reference");

			// replace the underlying references with a fresh storage part holding a replaced reference (same key)
			final Reference replacement = reference(
				referenceSchema, 10, 1, false,
				attributeValues(new AttributeValue(new AttributeKey(ATTRIBUTE_CODE), "v2"))
			);
			accessor.setReferencesStoragePart(storagePart(replacement));

			final Optional<AttributeValue> secondRead = supplier.getAttributeValue(new AttributeKey(ATTRIBUTE_CODE));
			assertTrue(secondRead.isPresent());
			assertEquals(
				"v2", secondRead.get().value(),
				"Second read must re-resolve the replaced reference and not return stale memoized data"
			);
		}
	}

	@Nested
	@DisplayName("Entity attribute locales")
	class EntityAttributeLocales {

		@Test
		@DisplayName("should expose the entity's existing attribute locales")
		void shouldExposeEntityExistingAttributeLocales() {
			final ReferenceSchema referenceSchema = referenceSchema(
				Cardinality.ZERO_OR_ONE, Map.of(ATTRIBUTE_CODE, attribute(ATTRIBUTE_CODE, false, false))
			);
			final StubContainerAccessor accessor = new StubContainerAccessor(storagePart());
			// the entity body carries English + German among its attribute locales
			accessor.setEntityStoragePart(
				new EntityBodyStoragePart(
					1, ENTITY_PK, Scope.LIVE, null,
					Set.of(), Set.of(Locale.ENGLISH, Locale.GERMAN), Set.of(), -1
				)
			);
			final ExistingAttributeValueSupplier supplier = supplier(
				accessor, referenceSchema, key(999), key(10)
			);

			assertEquals(
				Set.of(Locale.ENGLISH, Locale.GERMAN),
				supplier.getEntityExistingAttributeLocales(),
				"The supplier must surface the entity body's attribute locales"
			);
		}
	}

	/**
	 * Builds a reference schema whose cardinality allows duplicates and that declares a single representative
	 * attribute ({@link #ATTRIBUTE_VARIANT}). This is the schema flavour under which the supplier disambiguates
	 * references sharing the same {@link ReferenceKey} by their representative attribute values.
	 *
	 * @return a duplicate-cardinality reference schema with one representative attribute
	 */
	@Nonnull
	private static ReferenceSchema duplicateSchema() {
		return referenceSchema(
			Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
			Map.of(ATTRIBUTE_VARIANT, attribute(ATTRIBUTE_VARIANT, false, true))
		);
	}

	/**
	 * Builds a minimal, non-indexed, non-faceted reference schema named {@link #REFERENCE_NAME} that references an
	 * unmanaged entity type of the same name.
	 *
	 * @param cardinality the cardinality of the reference (drives whether duplicates and representative values apply)
	 * @param attributes  the reference attribute schemas keyed by attribute name
	 * @return the constructed reference schema
	 */
	@Nonnull
	private static ReferenceSchema referenceSchema(
		@Nonnull Cardinality cardinality,
		@Nonnull Map<String, AttributeSchemaContract> attributes
	) {
		return ReferenceSchema._internalBuild(
			REFERENCE_NAME, NamingConvention.generate(REFERENCE_NAME), null, null,
			REFERENCE_NAME, NamingConvention.generate(REFERENCE_NAME), false,
			cardinality, null, null, false,
			ScopedReferenceIndexType.EMPTY, null,
			Scope.NO_SCOPE, null, null, null,
			attributes, Map.of(),
			ConflictResolutionOverride.INHERITED
		);
	}

	/**
	 * Builds a String-typed reference attribute schema.
	 *
	 * @param name           the attribute name
	 * @param localized      whether the attribute is localized
	 * @param representative whether the attribute participates in the representative reference key
	 * @return the constructed attribute schema
	 */
	@Nonnull
	private static AttributeSchemaContract attribute(
		@Nonnull String name,
		boolean localized,
		boolean representative
	) {
		return AttributeSchema._internalBuild(
			name, null, null, null,
			localized, false, representative,
			String.class, null, ConflictResolutionOverride.INHERITED
		);
	}

	/**
	 * Builds a {@link Reference} owned by {@link #ENTITY_SCHEMA} and described by the given reference schema.
	 *
	 * @param referenceSchema    the schema describing the reference
	 * @param primaryKey         the referenced entity primary key (business key part)
	 * @param internalPrimaryKey the internal primary key distinguishing references sharing the same business key
	 * @param dropped            whether the reference is a dropped tombstone
	 * @param attributes         the reference attribute values keyed by attribute key
	 * @return the constructed reference
	 */
	@Nonnull
	private static Reference reference(
		@Nonnull ReferenceSchema referenceSchema,
		int primaryKey,
		int internalPrimaryKey,
		boolean dropped,
		@Nonnull Map<AttributeKey, AttributeValue> attributes
	) {
		return new Reference(
			ENTITY_SCHEMA, referenceSchema, 1,
			new ReferenceKey(REFERENCE_NAME, primaryKey, internalPrimaryKey),
			null, attributes, dropped
		);
	}

	/**
	 * Wraps the given references into a {@link ReferencesStoragePart} for {@link #ENTITY_PK}. The supplier reads
	 * references from this container verbatim (no sorting or validation is performed).
	 *
	 * @param references the references to expose, in iteration order
	 * @return the references storage part
	 */
	@Nonnull
	private static ReferencesStoragePart storagePart(@Nonnull Reference... references) {
		return new ReferencesStoragePart(ENTITY_PK, references.length, references, -1);
	}

	/**
	 * Indexes the given attribute values by their key, preserving insertion order.
	 *
	 * @param values the attribute values to index
	 * @return a mutable map of attribute key to attribute value
	 */
	@Nonnull
	private static Map<AttributeKey, AttributeValue> attributeValues(@Nonnull AttributeValue... values) {
		final Map<AttributeKey, AttributeValue> result = new LinkedHashMap<>(values.length);
		for (final AttributeValue value : values) {
			result.put(value.key(), value);
		}
		return result;
	}

	/**
	 * Builds a {@link RepresentativeReferenceKey} for {@link #REFERENCE_NAME} with the given primary key and
	 * representative attribute values.
	 *
	 * @param primaryKey          the referenced entity primary key
	 * @param representativeValues the representative attribute values (empty for a non-duplicate schema)
	 * @return the representative reference key
	 */
	@Nonnull
	private static RepresentativeReferenceKey key(int primaryKey, @Nonnull Serializable... representativeValues) {
		return new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_NAME, primaryKey), representativeValues);
	}

	/**
	 * Constructs the supplier under test bound to the given accessor, schema and keys, targeting
	 * {@link #ENTITY_TYPE} / {@link #ENTITY_PK}.
	 *
	 * @param accessor        the storage container accessor stub
	 * @param referenceSchema the reference schema
	 * @param storedKey       the stored representative reference key
	 * @param currentKey      the current representative reference key
	 * @return the supplier under test
	 */
	@Nonnull
	private static ReferenceEntityStoragePartAccessorAttributeValueByRepresentativeReferenceKeySupplier supplier(
		@Nonnull StubContainerAccessor accessor,
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull RepresentativeReferenceKey storedKey,
		@Nonnull RepresentativeReferenceKey currentKey
	) {
		return new ReferenceEntityStoragePartAccessorAttributeValueByRepresentativeReferenceKeySupplier(
			accessor, referenceSchema, storedKey, currentKey, ENTITY_TYPE, ENTITY_PK
		);
	}

	/**
	 * Asserts the given stream contains exactly one attribute value with the expected name and value.
	 *
	 * @param stream        the stream of attribute values to inspect
	 * @param attributeName the expected attribute name
	 * @param expectedValue the expected attribute value
	 */
	private static void assertSingleValue(
		@Nonnull Stream<AttributeValue> stream,
		@Nonnull String attributeName,
		@Nonnull Serializable expectedValue
	) {
		final List<AttributeValue> values = stream.toList();
		assertEquals(1, values.size(), "Expected exactly one attribute value");
		assertEquals(attributeName, values.get(0).key().attributeName());
		assertEquals(expectedValue, values.get(0).value());
	}

	/**
	 * Minimal {@link WritableEntityStorageContainerAccessor} stub for the supplier under test. It returns a chosen
	 * {@link ReferencesStoragePart} (swappable, to exercise memoization/obsolescence) and an optional
	 * {@link EntityBodyStoragePart} (needed by the attribute-locales path). Locale-change tracking reports no
	 * changes. All other operations are unused by the supplier and therefore throw
	 * {@link UnsupportedOperationException}.
	 */
	private static final class StubContainerAccessor implements WritableEntityStorageContainerAccessor {
		private ReferencesStoragePart referencesStoragePart;
		@Nullable private EntityBodyStoragePart entityBodyStoragePart;

		StubContainerAccessor(@Nonnull ReferencesStoragePart referencesStoragePart) {
			this.referencesStoragePart = referencesStoragePart;
		}

		/**
		 * Replaces the references container returned to the supplier, simulating an in-session change of the
		 * entity's references.
		 *
		 * @param referencesStoragePart the new references container
		 */
		void setReferencesStoragePart(@Nonnull ReferencesStoragePart referencesStoragePart) {
			this.referencesStoragePart = referencesStoragePart;
		}

		/**
		 * Sets the entity body returned by {@link #getEntityStoragePart(String, int, EntityExistence)}.
		 *
		 * @param entityBodyStoragePart the entity body storage part
		 */
		void setEntityStoragePart(@Nonnull EntityBodyStoragePart entityBodyStoragePart) {
			this.entityBodyStoragePart = entityBodyStoragePart;
		}

		@Nonnull
		@Override
		public ReferencesStoragePart getReferencesStoragePart(@Nonnull String entityType, int entityPrimaryKey) {
			return this.referencesStoragePart;
		}

		@Nonnull
		@Override
		public EntityBodyStoragePart getEntityStoragePart(
			@Nonnull String entityType, int entityPrimaryKey, @Nonnull EntityExistence expects
		) {
			return this.entityBodyStoragePart != null
				? this.entityBodyStoragePart
				: new EntityBodyStoragePart(entityPrimaryKey);
		}

		@Nonnull
		@Override
		public LocaleWithScope[] getAddedLocales() {
			return new LocaleWithScope[0];
		}

		@Nonnull
		@Override
		public LocaleWithScope[] getRemovedLocales() {
			return new LocaleWithScope[0];
		}

		@Override
		public int getLocalesIdentityHash() {
			return 0;
		}

		@Override
		public boolean isEntityRemovedEntirely() {
			throw new UnsupportedOperationException();
		}

		@Override
		public void registerAssignedPriceId(int entityPrimaryKey, @Nonnull PriceKey priceKey, int internalPriceId) {
			throw new UnsupportedOperationException();
		}

		@Nonnull
		@Override
		public OptionalInt findExistingInternalId(
			@Nonnull String entityType, int entityPrimaryKey, @Nonnull PriceKey priceKey
		) {
			throw new UnsupportedOperationException();
		}

		@Nonnull
		@Override
		public AttributesStoragePart getAttributeStoragePart(@Nonnull String entityType, int entityPrimaryKey) {
			throw new UnsupportedOperationException();
		}

		@Nonnull
		@Override
		public AttributesStoragePart getAttributeStoragePart(
			@Nonnull String entityType, int entityPrimaryKey, @Nonnull Locale locale
		) {
			throw new UnsupportedOperationException();
		}

		@Nonnull
		@Override
		public AssociatedDataStoragePart getAssociatedDataStoragePart(
			@Nonnull String entityType, int entityPrimaryKey, @Nonnull AssociatedDataKey key
		) {
			throw new UnsupportedOperationException();
		}

		@Nonnull
		@Override
		public PricesStoragePart getPriceStoragePart(@Nonnull String entityType, int entityPrimaryKey) {
			throw new UnsupportedOperationException();
		}
	}
}
