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

package io.evitadb.core.query.sort.generic;

import io.evitadb.api.APITestConstants;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.InitialEntityBuilder;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.cache.NoCacheSupervisor;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.response.ServerEntityDecorator;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.core.query.sort.Sorter.SortingContext;
import io.evitadb.dataType.Predecessor;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.utils.Functions;
import io.evitadb.utils.NamingConvention;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.utils.CollectionUtils.createHashMap;
import static org.mockito.Mockito.mock;

/**
 * Real owners with real references, and a real execution context whose entity bodies count as prefetched, for unit
 * tests of the prefetch-route sorting of a reference ordering by {@link PrefetchedRecordsSorter}.
 *
 * The owners carry references of `category` (primary key of the category in brackets, `-` for a row without the
 * value):
 *
 * | owner | order                 | second        | chain                         |
 * |-------|-----------------------|---------------|-------------------------------|
 * | 11    | [1] 50                | [1] 1         | [1] after 15                  |
 * | 12    | [1] -, [2] 5          | [1] -         | [1] -                         |
 * | 13    | [1] 10                | [1] 3         | [1] after 11                  |
 * | 14    | [2] 1                 | [2] 1         | [2] head                      |
 * | 15    | [1] 10                | [1] 2         | [1] head                      |
 * | 16    | [1] -                 | [1] -         | [1] head, absent in the chain |
 * | 17    | [3] 7                 | -             | [3] head, no chain at all     |
 * | 18    | [1] -                 | -             | [1] head of the second chain  |
 * | 19    | [1] 10                | [1] 2         | -                             |
 *
 * Owner 11 has the attribute `tail` 2, 13 has 1 and 14 has 3; the others have none. Owner 19 ties owner 15 on `order`
 * and on the compound in category 1.
 *
 * Owners 70-77 carry rows of `variants`, a reference allowing several rows to one brand, told apart by the
 * representative attribute `variant` (rows as `[brand] variant: order, second, chain`, in the order they were added):
 *
 * | owner | rows                                       |
 * |-------|--------------------------------------------|
 * | 70    | [2] z: 1, -, head                          |
 * | 71    | [2] a: 100, -, head                        |
 * | 72    | [2] a: -, -, -; [2] b: 50, -, after 73     |
 * | 73    | [2] b: 60, -, head; [2] c: 5, -, head      |
 * | 74    | [1] m: 60, -, head                         |
 * | 75    | [2] a: 60, -, -                            |
 * | 76    | [2] c: 5, -, head; [2] b: 60, -, head      |
 * | 77    | [2] a: -, 7, -; [2] b: 40, -, -            |
 *
 * Owner 75 ties owner 73 on `order` in brand 2, but by a row of another representative value (`a` against `b`).
 * Owner 76 holds the rows of owner 73 added in the mirrored order, so the representative values - not the order in
 * which the rows were added - must pick its row. Owner 77 is represented by its `a` row for the `orderSecond` compound,
 * which that row carries an element of, but by its `b` row for `order`.
 *
 * Owners 80-85 carry rows of `decimalVariants`, a reference allowing several rows to one brand, told apart by the
 * `BigDecimal` representative attribute `scale` - 1.0 and 1.00 compare equal, yet they are two different rows (rows
 * as `[brand] scale: order, chain`, in the order they were added):
 *
 * | owner | rows                                       |
 * |-------|--------------------------------------------|
 * | 80    | [2] 1.00: 1, -; [2] 1.0: 20, -             |
 * | 81    | [2] 1.0: 2, -; [2] 1.00: 30, -             |
 * | 82    | [2] 1.0: -, head                           |
 * | 83    | [2] 1.0: -, after 82                       |
 * | 84    | [2] 1.00: -, head                          |
 * | 85    | [2] 1.00: -, after 84; [2] 1.0: -, after 83 |
 *
 * Owner 81 holds the rows of owner 80 added in the mirrored order; owners 82-85 sit in the chains of the two keys,
 * owner 85 in both of them.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class PrefetchedOwnersFixture {
	public static final String OWNER_TYPE = "owner";
	public static final String REFERENCE_NAME = "category";
	public static final String ATTRIBUTE_ORDER = "order";
	public static final String ATTRIBUTE_SECOND = "second";
	public static final String ATTRIBUTE_CHAIN = "chain";
	public static final String ATTRIBUTE_TAIL = "tail";
	public static final String COMPOUND = "orderSecond";
	public static final String VARIANTS_REFERENCE_NAME = "variants";
	public static final String ATTRIBUTE_VARIANT = "variant";
	public static final String DECIMAL_VARIANTS_REFERENCE_NAME = "decimalVariants";
	public static final String ATTRIBUTE_SCALE = "scale";
	/**
	 * The key of the block of category 1.
	 */
	public static final RepresentativeReferenceKey BLOCK_1 = blockKey(1);
	/**
	 * The key of the block of category 2.
	 */
	public static final RepresentativeReferenceKey BLOCK_2 = blockKey(2);
	/**
	 * The key of the block of category 3.
	 */
	public static final RepresentativeReferenceKey BLOCK_3 = blockKey(3);
	/**
	 * The catalog schema the owner schema is built in.
	 */
	private static final CatalogSchema CATALOG_SCHEMA = CatalogSchema._internalBuild(
		APITestConstants.TEST_CATALOG, NamingConvention.generate(APITestConstants.TEST_CATALOG), null,
		EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE
	);
	/**
	 * The schema of the owners.
	 */
	public static final EntitySchemaContract SCHEMA = new InternalEntitySchemaBuilder(
		CATALOG_SCHEMA, EntitySchema._internalBuild(OWNER_TYPE)
	)
		.withAttribute(ATTRIBUTE_TAIL, Integer.class, thatIs -> thatIs.sortable().nullable())
		.withReferenceTo(
			REFERENCE_NAME, REFERENCE_NAME, Cardinality.ZERO_OR_MORE,
			thatIs -> thatIs
				.indexedForFilteringAndPartitioning()
				.withAttribute(ATTRIBUTE_ORDER, Integer.class, attribute -> attribute.sortable().nullable())
				.withAttribute(ATTRIBUTE_SECOND, Integer.class, attribute -> attribute.sortable().nullable())
				.withAttribute(ATTRIBUTE_CHAIN, Predecessor.class, attribute -> attribute.sortable().nullable())
				.withSortableAttributeCompound(
					COMPOUND,
					new AttributeElement(ATTRIBUTE_ORDER, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
					new AttributeElement(ATTRIBUTE_SECOND, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
				)
		)
		.withReferenceTo(
			VARIANTS_REFERENCE_NAME, "brand", Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
			thatIs -> thatIs
				.indexedForFilteringAndPartitioning()
				.withAttribute(ATTRIBUTE_VARIANT, String.class, attribute -> attribute.representative())
				.withAttribute(ATTRIBUTE_ORDER, Integer.class, attribute -> attribute.sortable().nullable())
				.withAttribute(ATTRIBUTE_SECOND, Integer.class, attribute -> attribute.sortable().nullable())
				.withAttribute(ATTRIBUTE_CHAIN, Predecessor.class, attribute -> attribute.sortable().nullable())
				.withSortableAttributeCompound(
					COMPOUND,
					new AttributeElement(ATTRIBUTE_ORDER, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
					new AttributeElement(ATTRIBUTE_SECOND, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
				)
		)
		.withReferenceTo(
			DECIMAL_VARIANTS_REFERENCE_NAME, "brand", Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
			thatIs -> thatIs
				.indexedForFilteringAndPartitioning()
				.withAttribute(ATTRIBUTE_SCALE, BigDecimal.class, attribute -> attribute.representative())
				.withAttribute(ATTRIBUTE_ORDER, Integer.class, attribute -> attribute.sortable().nullable())
				.withAttribute(ATTRIBUTE_CHAIN, Predecessor.class, attribute -> attribute.sortable().nullable())
		)
		.toInstance();
	/**
	 * The schema of the `category` reference.
	 */
	public static final ReferenceSchema REFERENCE_SCHEMA =
		(ReferenceSchema) SCHEMA.getReferenceOrThrowException(REFERENCE_NAME);
	/**
	 * The schema of the `variants` reference, which allows several rows to one brand.
	 */
	public static final ReferenceSchema VARIANTS_REFERENCE_SCHEMA =
		(ReferenceSchema) SCHEMA.getReferenceOrThrowException(VARIANTS_REFERENCE_NAME);
	/**
	 * The schema of the `decimalVariants` reference, which allows several rows to one brand told apart by a
	 * `BigDecimal`.
	 */
	public static final ReferenceSchema DECIMAL_VARIANTS_REFERENCE_SCHEMA =
		(ReferenceSchema) SCHEMA.getReferenceOrThrowException(DECIMAL_VARIANTS_REFERENCE_NAME);
	/**
	 * The owners described in the class documentation, by primary key.
	 */
	public static final Map<Integer, SealedEntity> OWNERS = createOwners();

	private PrefetchedOwnersFixture() {
		// constants and helpers only
	}

	/**
	 * Creates the key of the block of the category.
	 *
	 * @param categoryPrimaryKey the primary key of the category
	 * @return the key of its block
	 */
	@Nonnull
	public static RepresentativeReferenceKey blockKey(int categoryPrimaryKey) {
		return new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_NAME, categoryPrimaryKey));
	}

	/**
	 * Creates the key of the block of the brand of the `variants` reference - the brand alone, as a traversal names
	 * its blocks.
	 *
	 * @param brandPrimaryKey the primary key of the brand
	 * @return the key of its block
	 */
	@Nonnull
	public static RepresentativeReferenceKey variantsBlockKey(int brandPrimaryKey) {
		return new RepresentativeReferenceKey(new ReferenceKey(VARIANTS_REFERENCE_NAME, brandPrimaryKey));
	}

	/**
	 * Creates the representative key of one row of the `variants` reference - the key of the reduced index, and so of
	 * the providers, the row belongs to.
	 *
	 * @param brandPrimaryKey the primary key of the brand
	 * @param variant         the representative attribute value of the row
	 * @return the representative key of the row
	 */
	@Nonnull
	public static RepresentativeReferenceKey variantKey(int brandPrimaryKey, @Nonnull String variant) {
		return new RepresentativeReferenceKey(
			new ReferenceKey(VARIANTS_REFERENCE_NAME, brandPrimaryKey), new Serializable[]{variant}
		);
	}

	/**
	 * Creates the key of the block of the brand of the `decimalVariants` reference - the brand alone, as a traversal
	 * names its blocks.
	 *
	 * @param brandPrimaryKey the primary key of the brand
	 * @return the key of its block
	 */
	@Nonnull
	public static RepresentativeReferenceKey decimalVariantsBlockKey(int brandPrimaryKey) {
		return new RepresentativeReferenceKey(new ReferenceKey(DECIMAL_VARIANTS_REFERENCE_NAME, brandPrimaryKey));
	}

	/**
	 * Creates the representative key of one row of the `decimalVariants` reference - the key of the reduced index, and
	 * so of the providers, the row belongs to.
	 *
	 * @param brandPrimaryKey the primary key of the brand
	 * @param scale           the representative attribute value of the row
	 * @return the representative key of the row
	 */
	@Nonnull
	public static RepresentativeReferenceKey decimalVariantKey(int brandPrimaryKey, @Nonnull BigDecimal scale) {
		return new RepresentativeReferenceKey(
			new ReferenceKey(DECIMAL_VARIANTS_REFERENCE_NAME, brandPrimaryKey), new Serializable[]{scale}
		);
	}

	/**
	 * Runs a {@link PrefetchedRecordsSorter} with the comparator over the selection within the block and returns what
	 * it wrote, what it passed on and what it skipped.
	 *
	 * @param comparator the comparator of the sorter
	 * @param blockKey   the key of the sorted block, `null` for a sort outside a traversal
	 * @param startIndex the first requested position
	 * @param endIndex   the position after the last requested one
	 * @param selection  the owners to sort
	 * @return the outcome of the sort
	 */
	@Nonnull
	public static SortResult sort(
		@Nonnull EntityComparator comparator,
		@Nullable RepresentativeReferenceKey blockKey,
		int startIndex,
		int endIndex,
		@Nonnull int... selection
	) {
		final int[] result = new int[endIndex - startIndex];
		final List<Integer> skipped = new ArrayList<>(4);
		final SortingContext output = new PrefetchedRecordsSorter(comparator).sortAndSlice(
			new SortingContext(
				new PrefetchedExecutionContext(), new BaseBitmap(selection), startIndex, endIndex, 0, 0, blockKey
			),
			result,
			skipped::add
		);
		return new SortResult(
			Arrays.copyOf(result, output.peak()),
			output.nonSortedKeys().getArray(),
			skipped.stream().mapToInt(Integer::intValue).toArray()
		);
	}

	/**
	 * Creates the owners described in the class documentation.
	 */
	@Nonnull
	private static Map<Integer, SealedEntity> createOwners() {
		final Map<Integer, SealedEntity> owners = createHashMap(32);
		addOwner(owners, 11, 2, builder -> builder.setReference(
			REFERENCE_NAME, 1, ref -> ref.setAttribute(ATTRIBUTE_ORDER, 50).setAttribute(ATTRIBUTE_SECOND, 1)
				.setAttribute(ATTRIBUTE_CHAIN, new Predecessor(15))
		));
		addOwner(owners, 12, null, builder -> builder
			.setReference(REFERENCE_NAME, 1)
			.setReference(REFERENCE_NAME, 2, ref -> ref.setAttribute(ATTRIBUTE_ORDER, 5))
		);
		addOwner(owners, 13, 1, builder -> builder.setReference(
			REFERENCE_NAME, 1, ref -> ref.setAttribute(ATTRIBUTE_ORDER, 10).setAttribute(ATTRIBUTE_SECOND, 3)
				.setAttribute(ATTRIBUTE_CHAIN, new Predecessor(11))
		));
		addOwner(owners, 14, 3, builder -> builder.setReference(
			REFERENCE_NAME, 2, ref -> ref.setAttribute(ATTRIBUTE_ORDER, 1).setAttribute(ATTRIBUTE_SECOND, 1)
				.setAttribute(ATTRIBUTE_CHAIN, Predecessor.HEAD)
		));
		addOwner(owners, 15, null, builder -> builder.setReference(
			REFERENCE_NAME, 1, ref -> ref.setAttribute(ATTRIBUTE_ORDER, 10).setAttribute(ATTRIBUTE_SECOND, 2)
				.setAttribute(ATTRIBUTE_CHAIN, Predecessor.HEAD)
		));
		addOwner(owners, 16, null, builder -> builder.setReference(
			REFERENCE_NAME, 1, ref -> ref.setAttribute(ATTRIBUTE_CHAIN, Predecessor.HEAD)
		));
		addOwner(owners, 17, null, builder -> builder.setReference(
			REFERENCE_NAME, 3,
			ref -> ref.setAttribute(ATTRIBUTE_ORDER, 7).setAttribute(ATTRIBUTE_CHAIN, Predecessor.HEAD)
		));
		addOwner(owners, 18, null, builder -> builder.setReference(
			REFERENCE_NAME, 1, ref -> ref.setAttribute(ATTRIBUTE_CHAIN, Predecessor.HEAD)
		));
		addOwner(owners, 19, null, builder -> builder.setReference(
			REFERENCE_NAME, 1, ref -> ref.setAttribute(ATTRIBUTE_ORDER, 10).setAttribute(ATTRIBUTE_SECOND, 2)
		));
		addOwner(owners, 70, null, builder -> addVariant(builder, 2, "z", 1, null, Predecessor.HEAD));
		addOwner(owners, 71, null, builder -> addVariant(builder, 2, "a", 100, null, Predecessor.HEAD));
		addOwner(owners, 72, null, builder -> {
			addVariant(builder, 2, "a", null, null, null);
			addVariant(builder, 2, "b", 50, null, new Predecessor(73));
		});
		addOwner(owners, 73, null, builder -> {
			addVariant(builder, 2, "b", 60, null, Predecessor.HEAD);
			addVariant(builder, 2, "c", 5, null, Predecessor.HEAD);
		});
		addOwner(owners, 74, null, builder -> addVariant(builder, 1, "m", 60, null, Predecessor.HEAD));
		addOwner(owners, 75, null, builder -> addVariant(builder, 2, "a", 60, null, null));
		addOwner(owners, 76, null, builder -> {
			addVariant(builder, 2, "c", 5, null, Predecessor.HEAD);
			addVariant(builder, 2, "b", 60, null, Predecessor.HEAD);
		});
		addOwner(owners, 77, null, builder -> {
			addVariant(builder, 2, "a", null, 7, null);
			addVariant(builder, 2, "b", 40, null, null);
		});
		addOwner(owners, 80, null, builder -> {
			addDecimalVariant(builder, 2, new BigDecimal("1.00"), 1, null);
			addDecimalVariant(builder, 2, new BigDecimal("1.0"), 20, null);
		});
		addOwner(owners, 81, null, builder -> {
			addDecimalVariant(builder, 2, new BigDecimal("1.0"), 2, null);
			addDecimalVariant(builder, 2, new BigDecimal("1.00"), 30, null);
		});
		addOwner(
			owners, 82, null, builder -> addDecimalVariant(builder, 2, new BigDecimal("1.0"), null, Predecessor.HEAD)
		);
		addOwner(
			owners, 83, null,
			builder -> addDecimalVariant(builder, 2, new BigDecimal("1.0"), null, new Predecessor(82))
		);
		addOwner(
			owners, 84, null, builder -> addDecimalVariant(builder, 2, new BigDecimal("1.00"), null, Predecessor.HEAD)
		);
		addOwner(owners, 85, null, builder -> {
			addDecimalVariant(builder, 2, new BigDecimal("1.00"), null, new Predecessor(84));
			addDecimalVariant(builder, 2, new BigDecimal("1.0"), null, new Predecessor(83));
		});
		return owners;
	}

	/**
	 * Adds one more row of the `variants` reference to the brand, never replacing a row the owner already has.
	 *
	 * @param builder the builder of the owner
	 * @param brand   the primary key of the brand
	 * @param variant the representative attribute value of the row
	 * @param order   the `order` of the row, `null` for none
	 * @param second  the `second` of the row, `null` for none
	 * @param chain   the `chain` of the row, `null` for none
	 */
	private static void addVariant(
		@Nonnull InitialEntityBuilder builder,
		int brand,
		@Nonnull String variant,
		@Nullable Integer order,
		@Nullable Integer second,
		@Nullable Predecessor chain
	) {
		builder.setOrUpdateReference(
			VARIANTS_REFERENCE_NAME, brand, Functions.alwaysFalse(),
			ref -> {
				ref.setAttribute(ATTRIBUTE_VARIANT, variant);
				if (order != null) {
					ref.setAttribute(ATTRIBUTE_ORDER, order);
				}
				if (second != null) {
					ref.setAttribute(ATTRIBUTE_SECOND, second);
				}
				if (chain != null) {
					ref.setAttribute(ATTRIBUTE_CHAIN, chain);
				}
			}
		);
	}

	/**
	 * Adds one more row of the `decimalVariants` reference to the brand, never replacing a row the owner already has.
	 *
	 * @param builder the builder of the owner
	 * @param brand   the primary key of the brand
	 * @param scale   the representative attribute value of the row
	 * @param order   the `order` of the row, `null` for none
	 * @param chain   the `chain` of the row, `null` for none
	 */
	private static void addDecimalVariant(
		@Nonnull InitialEntityBuilder builder,
		int brand,
		@Nonnull BigDecimal scale,
		@Nullable Integer order,
		@Nullable Predecessor chain
	) {
		builder.setOrUpdateReference(
			DECIMAL_VARIANTS_REFERENCE_NAME, brand, Functions.alwaysFalse(),
			ref -> {
				ref.setAttribute(ATTRIBUTE_SCALE, scale);
				if (order != null) {
					ref.setAttribute(ATTRIBUTE_ORDER, order);
				}
				if (chain != null) {
					ref.setAttribute(ATTRIBUTE_CHAIN, chain);
				}
			}
		);
	}

	/**
	 * Builds one owner with its `tail` attribute and references.
	 */
	private static void addOwner(
		@Nonnull Map<Integer, SealedEntity> owners,
		int primaryKey,
		@Nullable Integer tail,
		@Nonnull Consumer<InitialEntityBuilder> references
	) {
		final InitialEntityBuilder builder = new InitialEntityBuilder(SCHEMA, primaryKey);
		if (tail != null) {
			builder.setAttribute(ATTRIBUTE_TAIL, tail);
		}
		references.accept(builder);
		owners.put(primaryKey, builder.toInstance());
	}

	/**
	 * What one run of the sorter produced.
	 *
	 * @param written  the records the sorter wrote, in order
	 * @param passedOn the records it handed to the next sorter
	 * @param skipped  the records it reported as skipped, in order
	 */
	public record SortResult(
		@Nonnull int[] written,
		@Nonnull int[] passedOn,
		@Nonnull int[] skipped
	) {
	}

	/**
	 * An execution context whose entity bodies were prefetched: it answers the owners of the fixture.
	 */
	private static final class PrefetchedExecutionContext extends QueryExecutionContext {

		PrefetchedExecutionContext() {
			super(
				new QueryPlanningContext(
					mock(Catalog.class), null, null,
					new EvitaRequest(query(collection(OWNER_TYPE)), OffsetDateTime.now(), EntityReference.class, null),
					createHashMap(4), createHashMap(4), NoCacheSupervisor.INSTANCE
				),
				true, null,
				(type, entity) -> {
					throw new UnsupportedOperationException("No entity is converted in this test.");
				}
			);
		}

		@Nonnull
		@Override
		public List<ServerEntityDecorator> getPrefetchedEntities() {
			return List.of();
		}

		@Override
		public SealedEntity translateToEntity(int primaryKey) {
			return OWNERS.get(primaryKey);
		}
	}

}
