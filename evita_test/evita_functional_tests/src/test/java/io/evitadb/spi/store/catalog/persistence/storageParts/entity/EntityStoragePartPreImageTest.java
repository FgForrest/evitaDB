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

package io.evitadb.spi.store.catalog.persistence.storageParts.entity;

import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.ReferencesEditor.ReferencesBuilder;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Price;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceDecodeCoverage;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.Scope;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart.MissingReferenceBehavior;
import io.evitadb.spi.store.catalog.shared.model.PriceWithInternalIds;
import io.evitadb.test.TestTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collection;
import java.util.Currency;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link EntityStoragePart#createPreImage()} — the copy a savepoint puts back into the slot of a trapped part
 * when it rolls back (see `DataStoreChanges#journalTrappedContent`).
 *
 * A pre-image that misses a field, or shares an array its source later writes into, restores a part that is
 * silently wrong. Two halves guard against that:
 *
 * - **completeness** walks every declared instance field of every implementation, so a field added later fails here
 *   until the copy constructor carries it — the claim under test is "every field", and only a walk over the fields
 *   can attack that quantifier;
 * - **independence** drives each type's in-place mutator on the source after the copy and checks the pre-image did
 *   not move — the property the rollback actually relies on.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Pre-image of an entity storage part")
@Tag(TestTags.ENGINE)
@Tag(TestTags.TRANSACTION)
class EntityStoragePartPreImageTest {
	private static final int ENTITY_PK = 7;
	private static final String REFERENCE_NAME = "main";
	private static final Currency CZK = Currency.getInstance("CZK");

	/**
	 * A schema with no declared reference that allows every evolution mode, so the references are built on implicit
	 * reference schemas.
	 */
	private static final EntitySchemaContract ENTITY_SCHEMA = EntitySchema._internalBuild("product");
	/**
	 * A localized attribute schema of type String.
	 */
	private static final AttributeSchemaContract NAME_SCHEMA = AttributeSchema._internalBuild(
		"name", String.class, true, ConflictResolutionOverride.INHERITED
	);
	/**
	 * Fields that a pre-image shares with its source on purpose, keyed by the declaring class. Every other array or
	 * collection field must be a distinct instance.
	 */
	private static final Map<Class<?>, Set<String>> SHARED_BY_DESIGN = Map.of(
		// never written in place - every mutator copies it into `modifiedReferences` first
		ReferencesStoragePart.class, Set.of("references")
	);

	/**
	 * Asserts, for every source, that its pre-image carries every declared instance field with an equal value and that
	 * every array or collection field not listed in {@link #SHARED_BY_DESIGN} is a distinct instance — and that every
	 * field holds a NON-DEFAULT value (not `null`, `false` or zero) in at least one of the sources.
	 *
	 * The last check is what makes the equality one meaningful: a copy constructor that forgets a field leaves it at
	 * its default, which equals a source that holds the default too, so a fixture with a clean, unflagged part would
	 * let a missing `dirty` assignment pass. Several sources are accepted because no single part can carry every
	 * field at once — a references part narrowed to some names cannot be modified, so it never gets a working array.
	 *
	 * @param sources the parts to take pre-images of, all of one type
	 * @throws IllegalAccessException never — the fields are made accessible first
	 */
	private static void assertCompletePreImages(@Nonnull EntityStoragePart... sources) throws IllegalAccessException {
		final Class<?> type = sources[0].getClass();
		final Set<String> shared = SHARED_BY_DESIGN.getOrDefault(type, Set.of());
		final Set<String> neverSet = new HashSet<>(16);
		for (final Field field : type.getDeclaredFields()) {
			if (!Modifier.isStatic(field.getModifiers())) {
				neverSet.add(field.getName());
			}
		}
		assertTrue(!neverSet.isEmpty(), "No instance field was found - the walk itself is broken.");
		for (final EntityStoragePart source : sources) {
			assertSame(type, source.getClass(), "All sources must be of one type.");
			final EntityStoragePart preImage = source.createPreImage();
			assertSame(type, preImage.getClass(), "The pre-image must be of the same type.");
			for (final Field field : type.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers())) {
					continue;
				}
				field.setAccessible(true);
				final Object sourceValue = field.get(source);
				final Object preImageValue = field.get(preImage);
				assertTrue(
					Objects.deepEquals(sourceValue, preImageValue),
					() -> type.getSimpleName() + "." + field.getName() + " is not carried over: `" +
						describe(sourceValue) + "` vs. `" + describe(preImageValue) + "`."
				);
				final boolean mutableContainer = sourceValue != null &&
					(sourceValue.getClass().isArray() || sourceValue instanceof Collection<?>);
				if (mutableContainer && !shared.contains(field.getName())) {
					assertNotSame(
						sourceValue, preImageValue,
						() -> type.getSimpleName() + "." + field.getName() + " is shared with the source, " +
							"so a write into it would reach the pre-image."
					);
				}
				if (!isDefault(sourceValue)) {
					neverSet.remove(field.getName());
				}
			}
		}
		assertTrue(
			neverSet.isEmpty(),
			() -> type.getSimpleName() + " fields " + neverSet + " hold their default value in every fixture, so a " +
				"copy constructor that forgot them would still pass - give one fixture a non-default value."
		);
	}

	/**
	 * Returns whether `value` is what a field holds before anything assigns it: `null`, `false` or numeric zero.
	 *
	 * @param value the field value
	 * @return `true` for a default value
	 */
	private static boolean isDefault(@Nullable Object value) {
		return value == null ||
			Boolean.FALSE.equals(value) ||
			(value instanceof Number number && number.longValue() == 0L);
	}

	/**
	 * Renders a field value for an assertion message, unrolling arrays.
	 *
	 * @param value the value
	 * @return its readable form
	 */
	@Nonnull
	private static String describe(@Nullable Object value) {
		return value instanceof Object[] array ? Arrays.toString(array) : String.valueOf(value);
	}


	/**
	 * Creates a reference to `referencedPk` with a known internal primary key and an optional group.
	 *
	 * @param referencedPk the referenced entity primary key
	 * @param internalPk   the internal primary key of the reference
	 * @param groupPk      the group primary key, or `null` for no group
	 * @return the reference
	 */
	@Nonnull
	private static Reference reference(int referencedPk, int internalPk, @Nullable Integer groupPk) {
		final GroupEntityReference group = groupPk == null ?
			null : new GroupEntityReference("group", groupPk, 1, false);
		return new Reference(
			ENTITY_SCHEMA,
			ReferencesBuilder.createImplicitSchema(
				ENTITY_SCHEMA, REFERENCE_NAME, "category", Cardinality.ZERO_OR_MORE, group
			),
			1,
			new ReferenceKey(REFERENCE_NAME, referencedPk, internalPk),
			group,
			false
		);
	}

	/**
	 * Replaces the reference to `referencedPk` with one in group `groupPk` — a write into the working array at the
	 * reference's position, which is the in-place write the pre-image must not see.
	 *
	 * @param part         the part to write into
	 * @param referencedPk the referenced entity primary key of an existing reference
	 * @param internalPk   its internal primary key
	 * @param groupPk      the new group primary key
	 */
	private static void regroup(@Nonnull ReferencesStoragePart part, int referencedPk, int internalPk, int groupPk) {
		part.replaceOrAddReference(
			new ReferenceKey(REFERENCE_NAME, referencedPk, internalPk),
			existing -> reference(referencedPk, internalPk, groupPk),
			() -> MissingReferenceBehavior.ACCEPT_INTERNAL_KEY
		);
	}

	/**
	 * Creates a references part as the store loads it, already modified once — the shape of a trapped part, whose
	 * working array is populated.
	 *
	 * @return the part
	 */
	@Nonnull
	private static ReferencesStoragePart trappedReferencesPart() {
		final ReferencesStoragePart part = new ReferencesStoragePart(
			ENTITY_PK, 3,
			new Reference[]{reference(1, 1, null), reference(2, 2, null), reference(3, 3, null)},
			-1
		);
		regroup(part, 2, 2, 20);
		return part;
	}

	/**
	 * Returns the groups of the part's references in order, `null` where a reference has none.
	 *
	 * @param part the part
	 * @return the group primary keys
	 */
	@Nonnull
	private static List<Integer> groupsOf(@Nonnull ReferencesStoragePart part) {
		return Arrays.stream(part.getReferences())
			.map(it -> it.getGroup().map(ReferenceContract.GroupEntityReference::getPrimaryKey).orElse(null))
			.toList();
	}

	/**
	 * Creates a body with an English attribute locale, one associated data key, a parent and LIVE scope.
	 *
	 * @return the body
	 */
	@Nonnull
	private static EntityBodyStoragePart populatedBody() {
		return new EntityBodyStoragePart(
			4, ENTITY_PK, Scope.LIVE, 1,
			new LinkedHashSet<>(List.of(Locale.ENGLISH)),
			new LinkedHashSet<>(List.of(Locale.ENGLISH)),
			new LinkedHashSet<>(List.of(new AssociatedDataKey("description", Locale.ENGLISH))),
			128
		);
	}

	/**
	 * Creates a price with the given amount without tax.
	 *
	 * @param priceId the price id
	 * @param amount  the amount without tax
	 * @return the price wrapped with an internal id equal to the price id
	 */
	@Nonnull
	private static PriceWithInternalIds price(int priceId, @Nonnull String amount) {
		final BigDecimal withoutTax = new BigDecimal(amount);
		return new PriceWithInternalIds(
			new Price(
				new PriceKey(priceId, "basic", CZK), null,
				withoutTax, new BigDecimal("21.00"), withoutTax.multiply(new BigDecimal("1.21")),
				null, true
			),
			priceId
		);
	}

	@Nested
	@DisplayName("Completeness - every field is carried over")
	class Completeness {

		@Test
		@DisplayName("Entity body: a loaded, changed body marked for removal, and a new one")
		void shouldCarryEveryFieldOfEntityBody() throws IllegalAccessException {
			final EntityBodyStoragePart loaded = populatedBody();
			// a change makes the body dirty, and the removal mark is a flag of its own
			loaded.addAttributeLocale(Locale.GERMAN);
			loaded.markForRemoval();
			// only a body created in memory is an initial revision
			final EntityBodyStoragePart created = new EntityBodyStoragePart(ENTITY_PK);
			assertCompletePreImages(loaded, created);
		}

		@Test
		@DisplayName("References: a modified part with pending reassignment, and a narrowed one")
		void shouldCarryEveryFieldOfReferences() throws IllegalAccessException {
			final ReferencesStoragePart modified = trappedReferencesPart();
			// a new reference that asks for a new internal key leaves the reassignment set populated
			modified.replaceOrAddReference(
				new ReferenceKey(REFERENCE_NAME, 4, 9),
				existing -> reference(4, 9, null),
				() -> MissingReferenceBehavior.GENERATE_NEW_INTERNAL_KEY
			);
			// a narrowed part cannot be modified, so the decode coverage needs a fixture of its own
			final ReferencesStoragePart narrowed = new ReferencesStoragePart(
				ENTITY_PK, 3, new Reference[]{reference(1, 1, null)}, 48,
				ReferenceDecodeCoverage.ofNames(Set.of(REFERENCE_NAME))
			);
			assertCompletePreImages(modified, narrowed);
		}

		@Test
		@DisplayName("Prices: a changed part")
		void shouldCarryEveryFieldOfPrices() throws IllegalAccessException {
			final PricesStoragePart part = new PricesStoragePart(
				ENTITY_PK, 2, PriceInnerRecordHandling.LOWEST_PRICE,
				new PriceWithInternalIds[]{price(1, "100.00"), price(2, "200.00")}, 64
			);
			part.setPriceInnerRecordHandling(PriceInnerRecordHandling.SUM);
			assertCompletePreImages(part);
		}

		@Test
		@DisplayName("Attributes: a changed part")
		void shouldCarryEveryFieldOfAttributes() throws IllegalAccessException {
			final AttributeKey key = new AttributeKey("name", Locale.ENGLISH);
			final AttributesStoragePart part = new AttributesStoragePart(
				42L, ENTITY_PK, Locale.ENGLISH, new AttributeValue[]{new AttributeValue(key, "one")}, 32
			);
			part.upsertAttribute(key, NAME_SCHEMA, existing -> new AttributeValue(key, "two"));
			assertCompletePreImages(part);
		}

		@Test
		@DisplayName("Associated data: a changed part")
		void shouldCarryEveryFieldOfAssociatedData() throws IllegalAccessException {
			final AssociatedDataKey key = new AssociatedDataKey("description", Locale.ENGLISH);
			final AssociatedDataStoragePart part = new AssociatedDataStoragePart(
				43L, ENTITY_PK, new AssociatedDataValue(key, "text"), 16
			);
			part.replaceAssociatedData(new AssociatedDataValue(key, "changed"));
			assertCompletePreImages(part);
		}
	}

	@Nested
	@DisplayName("Independence - a later in-place write never reaches the pre-image")
	class Independence {

		@Test
		@DisplayName("Entity body: locale, associated data key, parent and scope")
		void shouldKeepBodyPreImageWhenSourceChanges() {
			final EntityBodyStoragePart body = populatedBody();
			final EntityBodyStoragePart preImage = body.createPreImage();

			body.addAttributeLocale(Locale.GERMAN);
			body.addAssociatedDataKey(new AssociatedDataKey("note", Locale.GERMAN));
			body.setParent(2);
			body.setScope(Scope.ARCHIVED);

			assertEquals(Set.of(Locale.ENGLISH), preImage.getAttributeLocales());
			assertEquals(Set.of(Locale.ENGLISH), preImage.getLocales());
			assertEquals(
				Set.of(new AssociatedDataKey("description", Locale.ENGLISH)), preImage.getAssociatedDataKeys()
			);
			assertEquals(1, preImage.getParent());
			assertEquals(Scope.LIVE, preImage.getScope());
			assertEquals(populatedBody(), preImage, "The pre-image must equal the body as it was before the writes.");
		}

		@Test
		@DisplayName("References: a replacement written into the working array at its position")
		void shouldKeepReferencesPreImageWhenSourceReplacesInPlace() {
			final ReferencesStoragePart part = trappedReferencesPart();
			final ReferencesStoragePart preImage = part.createPreImage();
			final Reference[] workingArray = part.getReferences();

			regroup(part, 3, 3, 30);

			assertSame(
				workingArray, part.getReferences(),
				"Self-check: the replacement must land in the same working array, or no in-place write was exercised."
			);
			assertEquals(Arrays.asList(null, 20, 30), groupsOf(part));
			assertEquals(Arrays.asList(null, 20, null), groupsOf(preImage));
		}

		@Test
		@DisplayName("References: the first write into a part that was never modified")
		void shouldKeepReferencesPreImageWhenUntouchedSourceIsWrittenFirstTime() {
			final ReferencesStoragePart part = new ReferencesStoragePart(
				ENTITY_PK, 3,
				new Reference[]{reference(1, 1, null), reference(2, 2, null), reference(3, 3, null)},
				-1
			);
			final ReferencesStoragePart preImage = part.createPreImage();
			assertEquals(groupsOf(part), groupsOf(preImage), "Self-check: the pre-image starts equal to its source.");

			regroup(part, 2, 2, 20);

			assertEquals(Arrays.asList(null, 20, null), groupsOf(part));
			assertEquals(Arrays.asList(null, null, null), groupsOf(preImage));
		}

		@Test
		@DisplayName("Prices: a replacement written into the price array at its position")
		void shouldKeepPricesPreImageWhenSourceReplacesInPlace() {
			final PricesStoragePart part = new PricesStoragePart(
				ENTITY_PK, 2, PriceInnerRecordHandling.NONE,
				new PriceWithInternalIds[]{price(1, "100.00"), price(2, "200.00")}, 64
			);
			final PricesStoragePart preImage = part.createPreImage();
			final PriceWithInternalIds[] priceArray = part.getPrices();

			part.replaceOrAddPrice(
				new PriceKey(2, "basic", CZK),
				existing -> price(2, "250.00").delegate(),
				priceKey -> 2
			);

			assertSame(
				priceArray, part.getPrices(),
				"Self-check: the replacement must land in the same array, or no in-place write was exercised."
			);
			assertEquals(new BigDecimal("250.00"), part.getPrices()[1].priceWithoutTax());
			assertEquals(new BigDecimal("200.00"), preImage.getPrices()[1].priceWithoutTax());
		}

		@Test
		@DisplayName("Attributes: an update of an existing attribute")
		void shouldKeepAttributesPreImageWhenSourceUpdatesAttribute() {
			final AttributeKey key = new AttributeKey("name", Locale.ENGLISH);
			final AttributesStoragePart part = new AttributesStoragePart(
				42L, ENTITY_PK, Locale.ENGLISH, new AttributeValue[]{new AttributeValue(key, "one")}, 32
			);
			final AttributesStoragePart preImage = part.createPreImage();

			part.upsertAttribute(key, NAME_SCHEMA, existing -> new AttributeValue(key, "two"));

			assertEquals("two", part.findAttribute(key).value());
			assertEquals("one", preImage.findAttribute(key).value());
		}

		@Test
		@DisplayName("Associated data: a replaced value")
		void shouldKeepAssociatedDataPreImageWhenSourceReplacesValue() {
			final AssociatedDataKey key = new AssociatedDataKey("description", Locale.ENGLISH);
			final AssociatedDataStoragePart part = new AssociatedDataStoragePart(
				43L, ENTITY_PK, new AssociatedDataValue(key, "text"), 16
			);
			final AssociatedDataStoragePart preImage = part.createPreImage();

			part.replaceAssociatedData(new AssociatedDataValue(key, "changed"));

			assertEquals("changed", part.getValue().value());
			assertEquals("text", preImage.getValue().value());
		}
	}

}
