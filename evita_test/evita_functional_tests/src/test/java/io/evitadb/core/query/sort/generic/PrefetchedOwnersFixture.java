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
import io.evitadb.utils.NamingConvention;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
 *
 * Owner 11 has the attribute `tail` 2, 13 has 1 and 14 has 3; the others have none.
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
		.toInstance();
	/**
	 * The schema of the `category` reference.
	 */
	public static final ReferenceSchema REFERENCE_SCHEMA =
		(ReferenceSchema) SCHEMA.getReferenceOrThrowException(REFERENCE_NAME);
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
		final Map<Integer, SealedEntity> owners = createHashMap(16);
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
		return owners;
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
