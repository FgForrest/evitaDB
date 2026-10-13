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

package io.evitadb.core.query.fetch;

import io.evitadb.api.APITestConstants;
import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.data.structure.ReferenceDecorator;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceContractSerializablePredicate;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the validity mapping filled row by row ({@link ValidEntityToReferenceMapping#admit}): which rows it selects for
 * which owner, which referenced keys it hands to the slicer, and when it builds no representative key at all.
 *
 * The rows are real {@link Reference} instances over a real schema. `brand` allows duplicates, told apart by the
 * representative attribute `country`; `store` does not. The only stand-in is a {@link Reference} subclass counting the
 * reads of the representative attribute, which is how a representative key is built.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Validity mapping of the fetched references filled row by row")
@Tag(ENGINE)
@Tag(REFERENCE)
class ValidEntityToReferenceMappingTest {
	private static final String BRAND = "brand";
	private static final String STORE = "store";
	private static final String COUNTRY = "country";
	private static final int OWNER_A = 1;
	private static final int OWNER_B = 2;
	private static final EntitySchemaContract ENTITY_SCHEMA = new InternalEntitySchemaBuilder(
		CatalogSchema._internalBuild(
			APITestConstants.TEST_CATALOG, NamingConvention.generate(APITestConstants.TEST_CATALOG), null,
			EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE
		),
		EntitySchema._internalBuild("product")
	)
		.withReferenceToEntity(STORE, STORE, Cardinality.ZERO_OR_MORE, whichIs -> {})
		.withReferenceToEntity(
			BRAND, BRAND, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
			whichIs -> whichIs.withAttribute(COUNTRY, String.class, AttributeSchemaEditor::representative)
		)
		.toInstance();

	/**
	 * Returns the dto schema of the reference.
	 *
	 * @param referenceName the name of the reference
	 * @return the schema
	 */
	@Nonnull
	private static ReferenceSchema referenceSchema(@Nonnull String referenceName) {
		return (ReferenceSchema) ENTITY_SCHEMA.getReferenceOrThrowException(referenceName);
	}

	/**
	 * Builds a `brand` row.
	 *
	 * @param referencedPk the referenced primary key
	 * @param internalId   the internal id telling duplicates apart
	 * @param country      the representative attribute value
	 * @param dropped      TRUE for a dropped row
	 * @return the row
	 */
	@Nonnull
	private static ReadCountingReference brand(
		int referencedPk, int internalId, @Nonnull String country, boolean dropped
	) {
		return new ReadCountingReference(
			referenceSchema(BRAND), new ReferenceKey(BRAND, referencedPk, internalId),
			Map.of(new AttributeKey(COUNTRY), new AttributeValue(new AttributeKey(COUNTRY), country)),
			dropped
		);
	}

	/**
	 * Builds a `store` row.
	 *
	 * @param referencedPk the referenced primary key
	 * @param dropped      TRUE for a dropped row
	 * @return the row
	 */
	@Nonnull
	private static ReadCountingReference store(int referencedPk, boolean dropped) {
		return new ReadCountingReference(
			referenceSchema(STORE), new ReferenceKey(STORE, referencedPk), Map.of(), dropped
		);
	}

	/**
	 * Returns the referenced keys the mapping hands to the slicer for the owner.
	 *
	 * @param mapping the mapping
	 * @param owner   the owner primary key
	 * @return the keys, ascending
	 */
	@Nonnull
	private static int[] validKeys(@Nonnull ValidEntityToReferenceMapping mapping, int owner) {
		return mapping.getValidReferencedEntitiesFormula(owner).compute().getArray();
	}

	@Nested
	@DisplayName("Rows selected by their referenced primary key")
	class ByPrimaryKey {

		@Test
		@DisplayName("selects the existing rows of the keys admitted for the owner, and nothing for another owner")
		void shouldSelectExistingRowsOfAdmittedKeysOfTheOwner() {
			final ValidEntityToReferenceMapping mapping = new ValidEntityToReferenceMapping(
				2, referenceSchema(STORE), true
			);
			final ReferenceContract store10 = store(10, false);
			final ReferenceContract store20 = store(20, false);
			mapping.admit(OWNER_A, store10);
			mapping.admit(OWNER_B, store20);

			assertTrue(mapping.isReferenceSelected(OWNER_A, store10));
			assertFalse(mapping.isReferenceSelected(OWNER_A, store20), "owner B's verdict must not leak to owner A");
			assertFalse(mapping.isReferenceSelected(OWNER_B, store10), "owner A's verdict must not leak to owner B");
			assertTrue(mapping.isReferenceSelected(OWNER_B, store20));
			assertFalse(mapping.isReferenceSelected(3, store10), "an owner nothing was admitted for selects nothing");
			assertFalse(
				mapping.isReferenceSelected(OWNER_A, store(10, true)), "a dropped row is never selected"
			);
			assertArrayEquals(new int[]{10}, validKeys(mapping, OWNER_A));
			assertArrayEquals(new int[]{20}, validKeys(mapping, OWNER_B));
			assertSame(EmptyFormula.INSTANCE, mapping.getValidReferencedEntitiesFormula(3));
		}

		@Test
		@DisplayName("without a filter selects every existing duplicate of an admitted key and builds no row key")
		void shouldSelectEveryExistingDuplicateOfAdmittedKeyWithoutBuildingRepresentativeKey() {
			// the `ANY` case: no existence check, every existing row is admitted
			final ReadCountingReference brand1Cz = brand(1, 1, "CZ", false);
			final ReadCountingReference brand1De = brand(1, 2, "DE", false);
			final ReadCountingReference brand1Dropped = brand(1, 3, "SK", true);
			final ReadCountingReference brand2Cz = brand(2, 4, "CZ", false);
			final ValidEntityToReferenceMapping any = new ValidEntityToReferenceMapping(
				1, referenceSchema(BRAND), false
			);
			for (ReadCountingReference row : new ReadCountingReference[]{brand1Cz, brand1De, brand2Cz}) {
				any.admit(OWNER_A, row);
			}
			assertTrue(any.isReferenceSelected(OWNER_A, brand1Cz));
			assertTrue(any.isReferenceSelected(OWNER_A, brand1De));
			assertTrue(any.isReferenceSelected(OWNER_A, brand2Cz));
			assertFalse(any.isReferenceSelected(OWNER_A, brand1Dropped), "a dropped duplicate is not selected");

			// the `EXISTING` case: brand 2 does not exist, so its row is never admitted
			final ValidEntityToReferenceMapping existing = new ValidEntityToReferenceMapping(
				1, referenceSchema(BRAND), false
			);
			existing.admit(OWNER_A, brand1Cz);
			existing.admit(OWNER_A, brand1De);
			assertTrue(existing.isReferenceSelected(OWNER_A, brand1Cz));
			assertTrue(existing.isReferenceSelected(OWNER_A, brand1De));
			assertFalse(existing.isReferenceSelected(OWNER_A, brand2Cz), "a row of a key not admitted is not selected");
			assertFalse(existing.isReferenceSelected(OWNER_A, brand1Dropped), "a dropped duplicate is not selected");
			assertArrayEquals(new int[]{1}, validKeys(existing, OWNER_A));

			for (ReadCountingReference row : new ReadCountingReference[]{brand1Cz, brand1De, brand1Dropped, brand2Cz}) {
				assertEquals(
					0, row.representativeReads,
					"no representative key may be built when the verdict depends on the referenced key alone"
				);
			}
		}

		@Test
		@DisplayName("admitting a row twice changes nothing")
		void shouldIgnoreRepeatedAdmission() {
			final ValidEntityToReferenceMapping mapping = new ValidEntityToReferenceMapping(
				1, referenceSchema(STORE), true
			);
			final ReferenceContract store10 = store(10, false);
			mapping.admit(OWNER_A, store10);
			mapping.admit(OWNER_A, store10);
			assertArrayEquals(new int[]{10}, validKeys(mapping, OWNER_A));
			assertTrue(mapping.isReferenceSelected(OWNER_A, store10));
		}

	}

	@Nested
	@DisplayName("Rows selected by their representative key")
	class ByRow {

		@Test
		@DisplayName("selects the admitted duplicate only, not another duplicate of the same key")
		void shouldSelectAdmittedDuplicateOnly() {
			final ValidEntityToReferenceMapping mapping = new ValidEntityToReferenceMapping(
				2, referenceSchema(BRAND), true
			);
			final ReadCountingReference brand1Cz = brand(1, 1, "CZ", false);
			final ReadCountingReference brand1De = brand(1, 2, "DE", false);
			mapping.admit(OWNER_A, brand1Cz);
			mapping.admit(OWNER_B, brand1De);

			assertTrue(mapping.isReferenceSelected(OWNER_A, brand1Cz));
			assertFalse(
				mapping.isReferenceSelected(OWNER_A, brand1De), "a rejected duplicate sharing the key stays hidden"
			);
			assertTrue(mapping.isReferenceSelected(OWNER_B, brand1De));
			assertFalse(
				mapping.isReferenceSelected(OWNER_B, brand1Cz), "owner A's row verdict must not leak to owner B"
			);
			assertFalse(
				mapping.isReferenceSelected(OWNER_A, brand(1, 1, "CZ", true)),
				"a dropped row is not selected even when an admitted row has its representative key"
			);
			// the slicer works with keys: both owners hold key 1, each through its own admitted row
			assertArrayEquals(new int[]{1}, validKeys(mapping, OWNER_A));
			assertArrayEquals(new int[]{1}, validKeys(mapping, OWNER_B));
			assertTrue(brand1Cz.representativeReads > 0, "the verdict per row is kept by the representative key");
		}

		@Test
		@DisplayName("reads the representative key off the raw row when it is handed a decorator hiding the attribute")
		void shouldSelectDecoratedRowByItsRawRepresentativeKey() {
			final ValidEntityToReferenceMapping mapping = new ValidEntityToReferenceMapping(
				1, referenceSchema(BRAND), true
			);
			final ReadCountingReference brand1Cz = brand(1, 1, "CZ", false);
			mapping.admit(OWNER_A, brand1Cz);
			final ReferenceDecorator decorated = new ReferenceDecorator(
				brand1Cz, ReferenceContractSerializablePredicate.DEFAULT_INSTANCE.getAttributePredicate(BRAND)
			);
			// fixture guard: the decorator does not expose the attribute at all
			assertThrows(ContextMissingException.class, () -> decorated.getAttributeValue(COUNTRY));
			assertTrue(mapping.isReferenceSelected(OWNER_A, decorated));
			assertFalse(
				mapping.isReferenceSelected(
					OWNER_A,
					new ReferenceDecorator(
						brand(1, 2, "DE", false),
						ReferenceContractSerializablePredicate.DEFAULT_INSTANCE.getAttributePredicate(BRAND)
					)
				)
			);
		}

		@Test
		@DisplayName("selects another instance of an admitted row of a reference without duplicates")
		void shouldSelectAnotherInstanceOfAdmittedRowWithoutDuplicates() {
			final ValidEntityToReferenceMapping mapping = new ValidEntityToReferenceMapping(
				1, referenceSchema(STORE), true
			);
			mapping.admit(OWNER_A, store(10, false));
			assertTrue(mapping.isReferenceSelected(OWNER_A, store(10, false)));
		}

	}

	@Nested
	@DisplayName("Refusals")
	class Refusals {

		@Test
		@DisplayName("refuses a dropped row")
		void shouldRefuseDroppedRow() {
			final ValidEntityToReferenceMapping mapping = new ValidEntityToReferenceMapping(
				1, referenceSchema(BRAND), true
			);
			assertThrows(GenericEvitaInternalError.class, () -> mapping.admit(OWNER_A, brand(1, 1, "CZ", true)));
		}

		@Test
		@DisplayName("refuses to admit a row into the mapping of the index route")
		void shouldRefuseAdmissionIntoMappingOfIndexRoute() {
			final ValidEntityToReferenceMapping mapping = new ValidEntityToReferenceMapping(1, referenceSchema(STORE));
			mapping.setInitialVisibilityForEntity(OWNER_A, new BaseBitmap(10));
			assertThrows(GenericEvitaInternalError.class, () -> mapping.admit(OWNER_A, store(10, false)));
		}

	}

	/**
	 * A real reference row counting the reads of the representative attribute - the only way a representative key is
	 * built (see `RepresentativeAttributeDefinition#getRepresentativeValues`).
	 */
	private static final class ReadCountingReference extends Reference {
		@Serial private static final long serialVersionUID = -6402722930170127391L;
		/**
		 * The number of reads of the representative attribute.
		 */
		int representativeReads;

		/**
		 * Creates the row.
		 *
		 * @param referenceSchema the schema of the reference
		 * @param referenceKey    the key of the row
		 * @param attributes      the attribute values of the row
		 * @param dropped         TRUE for a dropped row
		 */
		ReadCountingReference(
			@Nonnull ReferenceSchema referenceSchema,
			@Nonnull ReferenceKey referenceKey,
			@Nonnull Map<AttributeKey, AttributeValue> attributes,
			boolean dropped
		) {
			super(ENTITY_SCHEMA, referenceSchema, 1, referenceKey, null, attributes, dropped);
		}

		@Nonnull
		@Override
		public Optional<AttributeValue> getAttributeValue(@Nonnull String attributeName) {
			if (COUNTRY.equals(attributeName)) {
				this.representativeReads++;
			}
			return super.getAttributeValue(attributeName);
		}

	}

}
