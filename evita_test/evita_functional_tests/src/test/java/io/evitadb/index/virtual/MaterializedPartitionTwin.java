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

package io.evitadb.index.virtual;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.api.statistics.CollectionIndexCardinality;
import io.evitadb.api.statistics.CollectionIndexCardinality.IndexCardinality;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.dataType.ChainableType;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.Functions;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.entityFetchAll;
import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.referenceContentWithAttributes;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.scope;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The materialized twin of a catalog: the read parity of single-owner partitions - their virtual reading as registry
 * rows against the reading of index objects holding the same data - checked in one engine, without a switch.
 *
 * Two catalogs are built from the same fixture:
 *
 * - the **row catalog** as it is. A new catalog keeps every single-owner partition of a reference indexed for
 *   filtering only as a registry row, so its queries read those partitions virtually;
 * - the **object catalog**, which then receives a clone `o + OWNER_OFFSET` of every owner `o` of one collection,
 *   carrying identical attributes, associated data and references. Every partition of a reference that collection
 *   owns then has at least two owners and is an index object, reached through the production promotion - the object
 *   catalog reads the same rows through index objects.
 *
 * A query runs against both. The answer of the object catalog, mapped back by subtracting the offset from the primary
 * key of a clone and de-duplicated in first-occurrence order, is the expected answer of the row catalog. The mapping
 * keeps an ordering: a clone carries the values of its original, so the two meet as equal values, which are ordered by
 * primary key - the original and the clone of every owner follow in the same relative order as the originals alone,
 * in both directions. A query of the owner collection must answer every clone exactly as its original - the clone and
 * the original agree, or the object catalog is no ground truth at all (see {@link #expectedAnswer(String, List)}).
 *
 * What a clone does not carry:
 *
 * - a value of a unique attribute - a string gets a suffix, any other type is refused, so a query filtering by such
 *   an attribute answers the original alone;
 * - a predecessor of the owner chain - a {@link Predecessor} names another owner, so the clone of an owner follows the
 *   clone of its predecessor, and the clones form a chain of their own next to the originals; a
 *   `ReferencedEntityPredecessor` names a referenced entity and is carried as it is;
 * - a row of a reflected reference as a write of the clone - the target receives a copy of its own row of the
 *   original reference instead, pointing at the clone: writing the reflected row would create that row without the
 *   attributes the original side may require. On the original side the roles of the chains swap, so there a
 *   `ReferencedEntityPredecessor` names an entity of the cloned collection and moves to its clone;
 * - prices, which no fixture of the twin holds - they are refused.
 *
 * Only the references of the cloned collection are materialized. Cloning products, for example, makes every partition
 * of `categories` an index object, but the reflection of `categories` on the category side gains a single-owner
 * partition per clone - so a case reading a reference of another collection needs a twin cloning that collection.
 *
 * The twin asserts its own non-vacuity when it is created: the public index statistics of every reference it is told
 * the cases read must report single-owner partitions held as rows in the row catalog and none in the object catalog.
 * Both catalogs are also held to the structural invariants of {@link VirtualPartitionInvariants} and to the entity
 * bodies through {@link VirtualReferencePartitionRawRowOracle}, so the index objects the expected answers are read
 * through hold what the bodies say.
 *
 * Which partitions are single-owner in the row catalog is derived from the entity bodies of the row catalog, read
 * through the public API (see {@link #selectsSingleOwnerRow(Scope[], String, FilterConstraint, Locale)} and
 * {@link #singleOwnerTargets(String, Scope)}). A case shows its selection reaches at least one of them - the aggregate
 * count of rows the statistics report does not show that the case's own query read one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class MaterializedPartitionTwin {
	/**
	 * The offset of the primary key of a clone from the primary key of its original - above every primary key a
	 * fixture of the twin writes or references.
	 */
	public static final int OWNER_OFFSET = 1_000_000;
	/**
	 * The suffix a clone appends to the value of a unique string attribute.
	 */
	private static final String UNIQUE_VALUE_SUFFIX = "-clone";
	/**
	 * How many owners one transaction of the cloning clones.
	 */
	private static final int CLONE_BATCH_SIZE = 500;
	/**
	 * How many entities one query reading the bodies or primary keys of a collection reads.
	 */
	private static final int PAGE_SIZE = 10_000;

	/**
	 * The engine holding both catalogs.
	 */
	@Nonnull private final Evita evita;
	/**
	 * The catalog as the fixture built it - its single-owner partitions are registry rows.
	 */
	@Nonnull private final String rowCatalog;
	/**
	 * The catalog whose owners of {@link #ownerType} are cloned - every partition of their references is an object.
	 */
	@Nonnull private final String objectCatalog;
	/**
	 * The collection whose owners the object catalog clones.
	 */
	@Nonnull private final String ownerType;
	/**
	 * How many owners each partition of a reference of {@link #ownerType} has in the row catalog, by the name of the
	 * reference, the scope and the encoding of the partition key - derived from the bodies on first use.
	 */
	@Nonnull private final Map<String, Map<Scope, Map<String, Integer>>> ownerCounts = new HashMap<>(8);

	/**
	 * Creates the twin of two catalogs already built.
	 *
	 * @param evita         the engine holding both catalogs
	 * @param rowCatalog    the catalog as the fixture built it
	 * @param objectCatalog the catalog whose owners are cloned
	 * @param ownerType     the collection whose owners are cloned
	 */
	private MaterializedPartitionTwin(
		@Nonnull Evita evita,
		@Nonnull String rowCatalog,
		@Nonnull String objectCatalog,
		@Nonnull String ownerType
	) {
		this.evita = evita;
		this.rowCatalog = rowCatalog;
		this.objectCatalog = objectCatalog;
		this.ownerType = ownerType;
	}

	/**
	 * Creates a new catalog - which keeps its single-owner partitions as registry rows - fills it in warm-up, takes it
	 * live and applies the changes of the live catalog.
	 *
	 * @param evita   the engine
	 * @param catalog the catalog
	 * @param warmUp  fills the catalog in warm-up
	 * @param live    changes the live catalog
	 */
	public static void createRowCatalog(
		@Nonnull Evita evita,
		@Nonnull String catalog,
		@Nonnull Consumer<EvitaSessionContract> warmUp,
		@Nonnull Consumer<EvitaSessionContract> live
	) {
		createRowCatalog(evita, catalog, warmUp, List.of(live));
	}

	/**
	 * Creates a new catalog - which keeps its single-owner partitions as registry rows - fills it in warm-up, takes it
	 * live and applies the transactions of the live catalog, each committed before the next one starts.
	 *
	 * @param evita            the engine
	 * @param catalog          the catalog
	 * @param warmUp           fills the catalog in warm-up
	 * @param liveTransactions change the live catalog, one transaction each, in this order
	 */
	public static void createRowCatalog(
		@Nonnull Evita evita,
		@Nonnull String catalog,
		@Nonnull Consumer<EvitaSessionContract> warmUp,
		@Nonnull List<Consumer<EvitaSessionContract>> liveTransactions
	) {
		evita.defineCatalog(catalog).updateViaNewSession(evita);
		evita.updateCatalog(catalog, warmUp);
		evita.updateCatalog(catalog, EvitaSessionContract::goLiveAndClose);
		for (final Consumer<EvitaSessionContract> live : liveTransactions) {
			evita.updateCatalog(catalog, live);
		}
	}

	/**
	 * Creates the object catalog of a row catalog built by {@link #createRowCatalog(Evita, String, Consumer, Consumer)}
	 * from the same fixture: builds it the same way, clones every owner of the collection, and asserts the
	 * non-vacuity of the twin for the references the cases read and the consistency of both catalogs - see the class
	 * documentation.
	 *
	 * @param evita          the engine holding the row catalog
	 * @param rowCatalog     the row catalog
	 * @param warmUp         fills a catalog in warm-up - the fixture the row catalog was filled by
	 * @param live           changes a live catalog - the changes the row catalog went through
	 * @param ownerType      the collection whose owners are cloned
	 * @param readReferences the references of the collection the cases read - each must hold rows in the row catalog
	 *                       and none in the object catalog
	 * @return the twin
	 */
	@Nonnull
	public static MaterializedPartitionTwin create(
		@Nonnull Evita evita,
		@Nonnull String rowCatalog,
		@Nonnull Consumer<EvitaSessionContract> warmUp,
		@Nonnull Consumer<EvitaSessionContract> live,
		@Nonnull String ownerType,
		@Nonnull String... readReferences
	) {
		return create(evita, rowCatalog, warmUp, List.of(live), ownerType, readReferences);
	}

	/**
	 * Creates the object catalog of a row catalog built by {@link #createRowCatalog(Evita, String, Consumer, List)}
	 * from the same fixture: builds it the same way, clones every owner of the collection, and asserts the
	 * non-vacuity of the twin for the references the cases read and the consistency of both catalogs - see the class
	 * documentation.
	 *
	 * @param evita            the engine holding the row catalog
	 * @param rowCatalog       the row catalog
	 * @param warmUp           fills a catalog in warm-up - the fixture the row catalog was filled by
	 * @param liveTransactions change a live catalog, one transaction each - the transactions the row catalog went
	 *                         through, in the same order
	 * @param ownerType        the collection whose owners are cloned
	 * @param readReferences   the references of the collection the cases read - each must hold rows in the row catalog
	 *                         and none in the object catalog
	 * @return the twin
	 */
	@Nonnull
	public static MaterializedPartitionTwin create(
		@Nonnull Evita evita,
		@Nonnull String rowCatalog,
		@Nonnull Consumer<EvitaSessionContract> warmUp,
		@Nonnull List<Consumer<EvitaSessionContract>> liveTransactions,
		@Nonnull String ownerType,
		@Nonnull String... readReferences
	) {
		final String objectCatalog = rowCatalog + "ObjectsOf" + StringUtils.capitalize(ownerType);
		createRowCatalog(evita, objectCatalog, warmUp, liveTransactions);
		cloneOwners(evita, objectCatalog, ownerType);
		final MaterializedPartitionTwin twin = new MaterializedPartitionTwin(evita, rowCatalog, objectCatalog, ownerType);
		for (final String referenceName : readReferences) {
			twin.assertMaterializes(referenceName);
		}
		assertConsistent(evita, rowCatalog);
		assertConsistent(evita, objectCatalog);
		return twin;
	}

	/**
	 * Returns the primary key of the clone of an owner.
	 *
	 * @param ownerPrimaryKey the primary key of the original
	 * @return the primary key of its clone
	 */
	public static int cloneOf(int ownerPrimaryKey) {
		return ownerPrimaryKey + OWNER_OFFSET;
	}

	/**
	 * Returns owners together with their clones - the primary keys a selection naming the owners names in the object
	 * catalog, so the object catalog selects every clone with its original.
	 *
	 * @param owners the primary keys of the originals
	 * @return the originals followed by their clones
	 */
	@Nonnull
	public static Integer[] withClones(@Nonnull Integer... owners) {
		final Integer[] result = Arrays.copyOf(owners, owners.length * 2);
		for (int i = 0; i < owners.length; i++) {
			result[owners.length + i] = cloneOf(owners[i]);
		}
		return result;
	}

	/**
	 * Returns the page of an expected order a page request answers: the slice of the page, or the first page when the
	 * requested one lies past the last, as the engine answers such a request.
	 *
	 * @param expectedOrder the expected full order
	 * @param pageNumber    the page, counted from one
	 * @param pageSize      the size of a page
	 * @return the expected page
	 */
	@Nonnull
	public static List<Integer> pageOf(@Nonnull List<Integer> expectedOrder, int pageNumber, int pageSize) {
		final int from = (pageNumber - 1) * pageSize;
		return from < expectedOrder.size() ?
			expectedOrder.subList(from, Math.min(from + pageSize, expectedOrder.size())) :
			expectedOrder.subList(0, Math.min(pageSize, expectedOrder.size()));
	}

	/**
	 * Asserts that real page requests against the row catalog answer the matching slices of an expected order - so a
	 * defect that only a page offset or a page limit of a route triggers is not hidden by a comparison of whole
	 * answers. The order must span at least two pages, or no request has an offset.
	 *
	 * @param label         names the case in failure messages
	 * @param expectedOrder the expected full order, obtained independently of the pages
	 * @param pageSize      the size of a page
	 * @param pageOfRows    answers the page of the given number, counted from one, from the row catalog
	 */
	public static void assertPages(
		@Nonnull String label,
		@Nonnull List<Integer> expectedOrder,
		int pageSize,
		@Nonnull IntFunction<List<Integer>> pageOfRows
	) {
		assertTrue(
			expectedOrder.size() > pageSize,
			label + ": the expected order must span at least two pages of " + pageSize + ", or no page has an " +
				"offset - it holds " + expectedOrder.size()
		);
		final int pageCount = (expectedOrder.size() + pageSize - 1) / pageSize;
		for (int pageNumber = 1; pageNumber <= pageCount; pageNumber++) {
			final int from = (pageNumber - 1) * pageSize;
			assertEquals(
				expectedOrder.subList(from, Math.min(from + pageSize, expectedOrder.size())),
				pageOfRows.apply(pageNumber),
				label + ": page " + pageNumber + " of " + pageCount + " (size " + pageSize + ") must be the slice of " +
					"the expected order"
			);
		}
	}

	/**
	 * Returns the catalog as the fixture built it - its single-owner partitions are registry rows.
	 *
	 * @return the name of the row catalog
	 */
	@Nonnull
	public String rowCatalog() {
		return this.rowCatalog;
	}

	/**
	 * Returns the catalog whose owners are cloned - every partition of the references of the owner collection is an
	 * index object.
	 *
	 * @return the name of the object catalog
	 */
	@Nonnull
	public String objectCatalog() {
		return this.objectCatalog;
	}

	/**
	 * Returns the collection whose owners the object catalog clones.
	 *
	 * @return the entity type
	 */
	@Nonnull
	public String ownerType() {
		return this.ownerType;
	}

	/**
	 * Maps the answer of the object catalog to the expected answer of the row catalog: the primary key of a clone to
	 * that of its original, de-duplicated in first-occurrence order. When the query reads the owner collection, every
	 * clone must answer as its original does - the answer must hold the original of a clone and the clone of an
	 * original, or the object catalog answers its clones differently from its originals and is no ground truth.
	 *
	 * @param entityType   the collection the query reads
	 * @param objectAnswer the primary keys the object catalog answered, in the order it answered them
	 * @return the expected primary keys in the expected order
	 */
	@Nonnull
	public List<Integer> expectedAnswer(@Nonnull String entityType, @Nonnull List<Integer> objectAnswer) {
		final LinkedHashSet<Integer> expected = CollectionUtils.createLinkedHashSet(objectAnswer.size());
		final Set<Integer> originals = new TreeSet<>();
		final Set<Integer> clonedOriginals = new TreeSet<>();
		for (final Integer primaryKey : objectAnswer) {
			if (primaryKey >= OWNER_OFFSET) {
				clonedOriginals.add(primaryKey - OWNER_OFFSET);
				expected.add(primaryKey - OWNER_OFFSET);
			} else {
				originals.add(primaryKey);
				expected.add(primaryKey);
			}
		}
		if (this.ownerType.equals(entityType)) {
			assertEquals(
				originals, clonedOriginals,
				"Every clone of the object catalog `" + this.objectCatalog + "` must answer as its original does"
			);
		} else {
			assertTrue(
				clonedOriginals.isEmpty(),
				"A query of `" + entityType + "` cannot answer a clone of `" + this.ownerType + "`: " + clonedOriginals
			);
		}
		return List.copyOf(expected);
	}

	/**
	 * Maps an answer per owner of the object catalog to the expected answer per owner of the row catalog: the answer
	 * of every original, after asserting that its clone answered the same - see {@link #expectedAnswer(String, List)}.
	 *
	 * @param objectAnswer the answer of every owner of the object catalog, by its primary key
	 * @param comparable   turns the answer of an owner into the form the answers of an original and its clone are
	 *                     compared in - an answer that may list the same items in another order sorts them
	 * @param <T>          the type of the answer of an owner
	 * @return the answer of every original, by its primary key, in ascending primary key order
	 */
	@Nonnull
	public <T> Map<Integer, T> expectedAnswerPerOwner(
		@Nonnull Map<Integer, T> objectAnswer,
		@Nonnull Function<T, ?> comparable
	) {
		final Map<Integer, T> expected = new TreeMap<>();
		final List<String> asymmetries = new ArrayList<>(4);
		for (final Map.Entry<Integer, T> entry : objectAnswer.entrySet()) {
			final int primaryKey = entry.getKey();
			if (primaryKey >= OWNER_OFFSET) {
				final T original = objectAnswer.get(primaryKey - OWNER_OFFSET);
				if (original == null || !comparable.apply(original).equals(comparable.apply(entry.getValue()))) {
					asymmetries.add(
						"clone " + primaryKey + " answered " + entry.getValue() + ", its original " + original
					);
				}
			} else {
				expected.put(primaryKey, entry.getValue());
				if (!objectAnswer.containsKey(cloneOf(primaryKey))) {
					asymmetries.add("original " + primaryKey + " answered " + entry.getValue() + ", its clone nothing");
				}
			}
		}
		assertTrue(
			asymmetries.isEmpty(),
			"Every clone of the object catalog `" + this.objectCatalog + "` must answer as its original does: " +
				asymmetries
		);
		return expected;
	}

	/**
	 * Tells whether a reference filter selects a single-owner partition of the row catalog - whether some original
	 * owner in the scopes keeps a row the filter matches whose partition has that owner alone. The rows the filter
	 * matches are read from the object catalog, through its index objects, so the selection is independent of the
	 * virtual reading it is the evidence for; which partitions are single-owner is derived from the bodies of the row
	 * catalog. A partition with a single owner is a registry row of the row catalog (see
	 * {@link VirtualPartitionInvariants}), so a query selecting it reads a row there.
	 *
	 * @param scopes        the scopes the query reads
	 * @param referenceName the reference of the owner collection the filter applies to
	 * @param rowFilter     the filter of the rows - the body of a `referenceHaving`, `null` for every row
	 * @param locale        the locale localized attributes of the filter are read in, `null` when it reads none
	 * @return TRUE when a matched row of an original owner is a single-owner partition of the row catalog
	 */
	public boolean selectsSingleOwnerRow(
		@Nonnull Scope[] scopes,
		@Nonnull String referenceName,
		@Nullable FilterConstraint rowFilter,
		@Nullable Locale locale
	) {
		final EntityFetch rows = entityFetch(
			rowFilter == null ?
				referenceContentWithAttributes(referenceName) :
				referenceContentWithAttributes(referenceName, filterBy(rowFilter))
		);
		final List<SealedEntity> owners = this.evita.queryCatalog(
			this.objectCatalog,
			session -> {
				return session.querySealedEntity(
					query(
						collection(this.ownerType),
						filterBy(scope(scopes), locale == null ? null : entityLocaleEquals(locale)),
						require(page(1, 2 * PAGE_SIZE), rows)
					)
				).getRecordData();
			}
		);
		for (final SealedEntity owner : owners) {
			if (owner.getPrimaryKeyOrThrowException() < OWNER_OFFSET) {
				for (final ReferenceContract row : owner.getReferences(referenceName)) {
					if (ownerCountOf(referenceName, owner.getScope(), row) == 1) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Returns the referenced entities a reference of the owner collection holds a single-owner partition of in a scope
	 * of the row catalog, derived from the bodies - the partitions a query reaching the target reads as a registry row.
	 *
	 * @param referenceName the reference of the owner collection
	 * @param scope         the scope
	 * @return the primary keys of the referenced entities, ascending
	 */
	@Nonnull
	public Set<Integer> singleOwnerTargets(@Nonnull String referenceName, @Nonnull Scope scope) {
		final Set<Integer> targets = new TreeSet<>();
		for (final Map.Entry<String, Integer> entry : ownerCountsOf(referenceName, scope).entrySet()) {
			if (entry.getValue() == 1) {
				targets.add(Integer.parseInt(entry.getKey().substring(0, entry.getKey().indexOf('/'))));
			}
		}
		return targets;
	}

	/**
	 * Asserts the twin materializes a reference of the owner collection: the row catalog holds single-owner partitions
	 * of it as rows in some scope, and the object catalog holds none in any scope - every partition there is an index
	 * object. Read through the public index statistics.
	 *
	 * @param referenceName the reference
	 */
	private void assertMaterializes(@Nonnull String referenceName) {
		int rows = 0;
		for (final IndexCardinality index : typeIndexesOf(this.rowCatalog, referenceName)) {
			rows += index.virtualPartitionCountIfKnown().orElseThrow();
		}
		assertTrue(
			rows > 0,
			"The row catalog `" + this.rowCatalog + "` must hold single-owner partitions of `" + this.ownerType + "." +
				referenceName + "` as rows"
		);
		final List<IndexCardinality> objectTypeIndexes = typeIndexesOf(this.objectCatalog, referenceName);
		assertFalse(
			objectTypeIndexes.isEmpty(),
			"The object catalog `" + this.objectCatalog + "` must index `" + this.ownerType + "." + referenceName + "`"
		);
		for (final IndexCardinality index : objectTypeIndexes) {
			assertEquals(
				0, index.virtualPartitionCountIfKnown().orElseThrow(),
				"The object catalog `" + this.objectCatalog + "` must hold no single-owner partition of `" +
					this.ownerType + "." + referenceName + "` as a row in " + index.scope() + " - every partition " +
					"has an original and its clone"
			);
		}
	}

	/**
	 * Returns the readings of the referenced-type entity indexes of a reference of the owner collection in every scope
	 * that indexes it.
	 *
	 * @param catalog       the catalog
	 * @param referenceName the reference
	 * @return the readings, one per scope indexing the reference
	 */
	@Nonnull
	private List<IndexCardinality> typeIndexesOf(@Nonnull String catalog, @Nonnull String referenceName) {
		final CollectionIndexCardinality cardinality = this.evita.management()
			.getEntityCollectionStatistics(
				catalog, this.ownerType, EnumSet.of(CatalogStatisticsComponent.INDEX_CARDINALITY)
			)
			.indexCardinalityIfPresent()
			.orElseThrow();
		final List<IndexCardinality> result = new ArrayList<>(2);
		for (final IndexCardinality index : cardinality.indexes()) {
			if (
				index.indexType() == EntityIndexType.REFERENCED_ENTITY_TYPE &&
					referenceName.equals(index.discriminator())
			) {
				result.add(index);
			}
		}
		return result;
	}

	/**
	 * Returns how many owners the partition of a row has in the row catalog.
	 *
	 * @param referenceName the reference of the row
	 * @param scope         the scope of the owner of the row
	 * @param row           the row
	 * @return the number of owners of its partition, `0` when the bodies of the row catalog produce no such partition
	 */
	private int ownerCountOf(@Nonnull String referenceName, @Nonnull Scope scope, @Nonnull ReferenceContract row) {
		final ReferenceSchemaContract referenceSchema = this.evita.getCatalogInstance(this.rowCatalog)
			.orElseThrow()
			.getEntitySchema(this.ownerType)
			.orElseThrow()
			.getReferenceOrThrowException(referenceName);
		return ownerCountsOf(referenceName, scope).getOrDefault(encodingOf(referenceSchema, row), 0);
	}

	/**
	 * Returns how many owners every partition of a reference of the owner collection has in a scope of the row
	 * catalog, derived from the bodies of the owners on first use: the rows grouped by referenced entity and - for a
	 * reference allowing duplicates - by the values of its representative attributes, as the oracle groups them.
	 *
	 * @param referenceName the reference
	 * @param scope         the scope
	 * @return the number of owners by the encoding of the partition key
	 */
	@Nonnull
	private Map<String, Integer> ownerCountsOf(@Nonnull String referenceName, @Nonnull Scope scope) {
		return this.ownerCounts
			.computeIfAbsent(referenceName, __ -> new HashMap<>(4))
			.computeIfAbsent(
				scope,
				__ -> this.evita.queryCatalog(
					this.rowCatalog,
					session -> {
						final ReferenceSchemaContract referenceSchema = session
							.getEntitySchemaOrThrowException(this.ownerType)
							.getReferenceOrThrowException(referenceName);
						final Map<String, Integer> counts = new HashMap<>(256);
						for (final SealedEntity owner : readAll(
							session, this.ownerType, scope, entityFetch(referenceContentWithAttributes(referenceName))
						)) {
							for (final ReferenceContract row : owner.getReferences(referenceName)) {
								counts.merge(encodingOf(referenceSchema, row), 1, Integer::sum);
							}
						}
						return counts;
					}
				)
			);
	}

	/**
	 * Encodes the key of the partition a row falls into: its referenced entity and - for a reference allowing
	 * duplicates - the values of its representative attributes in the order of their names, a missing one taking the
	 * attribute's default.
	 *
	 * @param referenceSchema the reference
	 * @param row             the row
	 * @return the encoding
	 */
	@Nonnull
	private static String encodingOf(@Nonnull ReferenceSchemaContract referenceSchema, @Nonnull ReferenceContract row) {
		final StringBuilder encoding = new StringBuilder(32).append(row.getReferencedPrimaryKey()).append('/');
		if (referenceSchema.getCardinality().allowsDuplicates()) {
			final List<AttributeSchemaContract> representatives = referenceSchema.getAttributes()
				.values()
				.stream()
				.filter(AttributeSchemaContract::isRepresentative)
				.sorted(Comparator.comparing(AttributeSchemaContract::getName))
				.toList();
			for (final AttributeSchemaContract representative : representatives) {
				final Serializable value = row.getAttributeValue(representative.getName())
					.filter(AttributeValue::exists)
					.map(AttributeValue::value)
					.orElseGet(representative::getDefaultValue);
				encoding.append(CanonicalValue.encode(new Serializable[]{value})).append('/');
			}
		}
		return encoding.toString();
	}

	/**
	 * Reads every entity of a collection in a scope, page by page until the total the query reports.
	 *
	 * @param session    the session
	 * @param entityType the collection
	 * @param scope      the scope
	 * @param fetch      what to fetch of every entity
	 * @return the entities
	 */
	@Nonnull
	private static List<SealedEntity> readAll(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull EntityFetch fetch
	) {
		final List<SealedEntity> result = new ArrayList<>(64);
		int pageNumber = 1;
		int total;
		do {
			final EvitaResponse<SealedEntity> response = session.querySealedEntity(
				query(collection(entityType), filterBy(scope(scope)), require(page(pageNumber++, PAGE_SIZE), fetch))
			);
			total = response.getTotalRecordCount();
			result.addAll(response.getRecordData());
			if (response.getRecordData().isEmpty()) {
				break;
			}
		} while (result.size() < total);
		assertEquals(total, result.size(), "Every `" + entityType + "` of " + scope + " must have been read");
		return result;
	}

	/**
	 * Clones every owner of a collection of a live catalog, in both scopes - see the class documentation for what a
	 * clone carries. A clone of an archived owner is created live and archived in the same transaction.
	 *
	 * @param evita     the engine
	 * @param catalog   the live catalog
	 * @param ownerType the collection
	 */
	private static void cloneOwners(@Nonnull Evita evita, @Nonnull String catalog, @Nonnull String ownerType) {
		for (final Scope scope : Scope.values()) {
			final List<Integer> originals = evita.queryCatalog(
				catalog,
				session -> {
					final List<Integer> primaryKeys = new ArrayList<>(64);
					for (final SealedEntity owner : readAll(session, ownerType, scope, entityFetch())) {
						primaryKeys.add(owner.getPrimaryKeyOrThrowException());
					}
					return primaryKeys;
				}
			);
			for (int from = 0; from < originals.size(); from += CLONE_BATCH_SIZE) {
				final Integer[] batch = originals.subList(from, Math.min(from + CLONE_BATCH_SIZE, originals.size()))
					.toArray(Integer[]::new);
				evita.updateCatalog(
					catalog,
					session -> {
						final EntitySchemaContract schema = session.getEntitySchemaOrThrowException(ownerType);
						final List<SealedEntity> bodies = session.querySealedEntity(
							query(
								collection(ownerType),
								filterBy(scope(scope), entityPrimaryKeyInSet(batch)),
								require(page(1, batch.length), entityFetchAll())
							)
						).getRecordData();
						assertEquals(batch.length, bodies.size(), "Every `" + ownerType + "` of the batch must be read");
						for (final SealedEntity body : bodies) {
							session.upsertEntity(cloneOf(session, schema, body));
						}
						// a reflected row of a clone is written on the original side, where its attributes belong
						final Map<String, Map<Integer, EntityBuilder>> counterparts = new TreeMap<>();
						for (final SealedEntity body : bodies) {
							addCounterpartsOfReflectedRows(session, schema, body, counterparts);
						}
						for (final Map<Integer, EntityBuilder> targets : counterparts.values()) {
							for (final EntityBuilder target : targets.values()) {
								session.upsertEntity(target);
							}
						}
						if (scope == Scope.ARCHIVED) {
							for (final Integer original : batch) {
								session.archiveEntity(ownerType, cloneOf(original));
							}
						}
					}
				);
			}
		}
	}

	/**
	 * Builds the clone of an owner - see the class documentation for what it carries.
	 *
	 * @param session the session to create the clone in
	 * @param schema  the schema of the owner collection
	 * @param body    the body of the original
	 * @return the builder of the clone
	 */
	@Nonnull
	private static EntityBuilder cloneOf(
		@Nonnull EvitaSessionContract session,
		@Nonnull EntitySchemaContract schema,
		@Nonnull SealedEntity body
	) {
		final int original = body.getPrimaryKeyOrThrowException();
		assertTrue(original < OWNER_OFFSET, "The original `" + schema.getName() + "` " + original + " is above the offset");
		if (!body.getPrices().isEmpty()) {
			fail("The twin does not clone prices, but `" + schema.getName() + "` " + original + " has some");
		}
		final EntityBuilder clone = session.createNewEntity(schema.getName(), cloneOf(original));
		if (schema.isWithHierarchy()) {
			body.getParentEntity().ifPresent(parent -> clone.setParent(parent.getPrimaryKeyOrThrowException()));
		}
		for (final AttributeValue value : body.getAttributeValues()) {
			if (!value.dropped()) {
				final AttributeSchemaContract attributeSchema = schema.getAttribute(value.key().attributeName())
					.orElseThrow();
				final Serializable cloned = clonedValue(
					value.valueOrThrowException(), isUnique(attributeSchema), Predecessor.class,
					schema.getName() + "." + value.key()
				);
				final Locale locale = value.key().locale();
				if (cloned instanceof Serializable[] array) {
					if (locale == null) {
						clone.setAttribute(value.key().attributeName(), array);
					} else {
						clone.setAttribute(value.key().attributeName(), locale, array);
					}
				} else if (locale == null) {
					clone.setAttribute(value.key().attributeName(), cloned);
				} else {
					clone.setAttribute(value.key().attributeName(), locale, cloned);
				}
			}
		}
		for (final AssociatedDataValue value : body.getAssociatedDataValues()) {
			if (!value.dropped()) {
				final Locale locale = value.key().locale();
				if (locale == null) {
					clone.setAssociatedData(value.key().associatedDataName(), value.valueOrThrowException());
				} else {
					clone.setAssociatedData(value.key().associatedDataName(), locale, value.valueOrThrowException());
				}
			}
		}
		for (final ReferenceContract reference : body.getReferences()) {
			final ReferenceSchemaContract referenceSchema = schema.getReferenceOrThrowException(
				reference.getReferenceName()
			);
			if (referenceSchema instanceof ReflectedReferenceSchemaContract) {
				// written on the original side - see addCounterpartsOfReflectedRows
				continue;
			}
			// the clone references the very targets its original does
			copyRow(
				clone, referenceSchema, reference, reference.getReferencedPrimaryKey(), Predecessor.class,
				schema.getName()
			);
		}
		return clone;
	}

	/**
	 * Adds the counterpart of every reflected row of an original to the clone of the original: the row of the
	 * original reference the target holds to the original, copied to the clone with its group and attributes. Writing
	 * the reflected row on the clone would create the counterpart without the attributes the original side may
	 * require.
	 *
	 * @param session      the session to write in
	 * @param schema       the schema of the cloned collection
	 * @param body         the body of the original
	 * @param counterparts the builders of the targets, by entity type and primary key - opened on first use
	 */
	private static void addCounterpartsOfReflectedRows(
		@Nonnull EvitaSessionContract session,
		@Nonnull EntitySchemaContract schema,
		@Nonnull SealedEntity body,
		@Nonnull Map<String, Map<Integer, EntityBuilder>> counterparts
	) {
		final int original = body.getPrimaryKeyOrThrowException();
		// the reflected rows of one target - duplicates told apart by their representative attributes - mirror all the
		// rows the target holds to the original, which are copied once
		final Set<String> copiedTargets = new TreeSet<>();
		for (final ReferenceContract reference : body.getReferences()) {
			if (
				schema.getReferenceOrThrowException(reference.getReferenceName()) instanceof
					ReflectedReferenceSchemaContract reflected &&
					copiedTargets.add(reference.getReferenceName() + "/" + reference.getReferencedPrimaryKey())
			) {
				final String targetType = reflected.getReferencedEntityType();
				final int targetPrimaryKey = reference.getReferencedPrimaryKey();
				final EntityBuilder target = counterparts.computeIfAbsent(targetType, __ -> new TreeMap<>())
					.computeIfAbsent(targetPrimaryKey, __ -> openForWrite(session, targetType, targetPrimaryKey));
				final ReferenceSchemaContract originalSchema = session.getEntitySchemaOrThrowException(targetType)
					.getReferenceOrThrowException(reflected.getReflectedReferenceName());
				final List<ReferenceContract> rows = target.getReferences(originalSchema.getName())
					.stream()
					.filter(it -> it.getReferencedPrimaryKey() == original)
					.toList();
				assertFalse(
					rows.isEmpty(),
					"`" + targetType + "` " + targetPrimaryKey + " must hold the row reflected by `" + schema.getName() +
						"` " + original
				);
				for (final ReferenceContract row : rows) {
					// on the original side the chain naming the cloned collection is the referenced entity one
					copyRow(
						target, originalSchema, row, cloneOf(original), ReferencedEntityPredecessor.class,
						targetType
					);
				}
			}
		}
	}

	/**
	 * Opens an entity of either scope for write.
	 *
	 * @param session    the session
	 * @param entityType the collection
	 * @param primaryKey the entity
	 * @return the builder
	 */
	@Nonnull
	private static EntityBuilder openForWrite(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		int primaryKey
	) {
		final List<SealedEntity> entities = session.querySealedEntity(
			query(
				collection(entityType),
				filterBy(scope(Scope.values()), entityPrimaryKeyInSet(primaryKey)),
				require(page(1, 1), entityFetchAll())
			)
		).getRecordData();
		assertEquals(1, entities.size(), "`" + entityType + "` " + primaryKey + " must exist");
		return entities.getFirst().openForWrite();
	}

	/**
	 * Adds a copy of a reference row, with its group and attributes, to an entity under another referenced entity.
	 *
	 * @param entity          the entity receiving the copy
	 * @param referenceSchema the reference of the row
	 * @param row             the row copied
	 * @param target          the referenced entity of the copy
	 * @param clonedChain     the chain type whose values name an entity of the cloned collection - moved to its clone
	 * @param description     names the entity in a failure message
	 */
	private static void copyRow(
		@Nonnull EntityBuilder entity,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReferenceContract row,
		int target,
		@Nonnull Class<? extends ChainableType> clonedChain,
		@Nonnull String description
	) {
		final Consumer<ReferenceBuilder> copy = builder -> {
			row.getGroup()
				.filter(group -> !group.dropped())
				.ifPresent(group -> builder.setGroup(group.referencedEntity(), group.primaryKey()));
			for (final AttributeValue value : row.getAttributeValues()) {
				if (!value.dropped()) {
					final Serializable copied = clonedValue(
						value.valueOrThrowException(), false, clonedChain,
						description + "." + row.getReferenceName() + "." + value.key()
					);
					final Locale locale = value.key().locale();
					if (copied instanceof Serializable[] array) {
						if (locale == null) {
							builder.setAttribute(value.key().attributeName(), array);
						} else {
							builder.setAttribute(value.key().attributeName(), locale, array);
						}
					} else if (locale == null) {
						builder.setAttribute(value.key().attributeName(), copied);
					} else {
						builder.setAttribute(value.key().attributeName(), locale, copied);
					}
				}
			}
		};
		if (referenceSchema.getCardinality().allowsDuplicates()) {
			// every row of a reference allowing duplicates is a reference of its own - none is ever updated
			entity.setOrUpdateReference(referenceSchema.getName(), target, Functions.alwaysFalse(), copy);
		} else {
			entity.setReference(referenceSchema.getName(), target, copy);
		}
	}

	/**
	 * Returns the value a clone carries in place of a value of its original: a chain value naming an entity of the
	 * cloned collection moved to the clone of that entity, a unique string with a suffix, anything else as it is.
	 *
	 * @param value       the value of the original
	 * @param unique      TRUE when the value belongs to a unique attribute
	 * @param clonedChain the chain type whose values name an entity of the cloned collection
	 * @param description names the value in a failure message
	 * @return the value of the clone
	 */
	@Nonnull
	private static Serializable clonedValue(
		@Nonnull Serializable value,
		boolean unique,
		@Nonnull Class<? extends ChainableType> clonedChain,
		@Nonnull String description
	) {
		if (clonedChain.isInstance(value)) {
			// the predecessor names another entity of the cloned collection - the clone follows the clone of that one
			final ChainableType chainable = (ChainableType) value;
			if (chainable.isHead()) {
				return value;
			}
			return value instanceof Predecessor ?
				new Predecessor(cloneOf(chainable.predecessorPk())) :
				new ReferencedEntityPredecessor(cloneOf(chainable.predecessorPk()));
		} else if (unique) {
			if (value instanceof String string) {
				return string + UNIQUE_VALUE_SUFFIX;
			}
			return fail("The twin clones only unique strings, but " + description + " holds " + value);
		} else {
			return value;
		}
	}

	/**
	 * Tells whether an attribute of the owner collection is unique in any scope or locale, locally or globally.
	 *
	 * @param attributeSchema the attribute
	 * @return TRUE for a unique attribute
	 */
	private static boolean isUnique(@Nonnull AttributeSchemaContract attributeSchema) {
		return attributeSchema.isUniqueInAnyScope() || attributeSchema.isUniqueWithinLocaleInAnyScope() ||
			(
				attributeSchema instanceof GlobalAttributeSchemaContract global &&
					(global.isUniqueGloballyInAnyScope() || global.isUniqueGloballyWithinLocaleInAnyScope())
			);
	}

	/**
	 * Asserts the structural invariants of every collection of a catalog and that the partitions of every collection
	 * agree with the bodies of its entities.
	 *
	 * @param evita   the engine
	 * @param catalog the catalog
	 */
	private static void assertConsistent(@Nonnull Evita evita, @Nonnull String catalog) {
		final Catalog theCatalog = VirtualPartitionInvariants.catalogOf(evita, catalog);
		for (final String entityType : new TreeSet<>(theCatalog.getEntityTypes())) {
			VirtualPartitionInvariants.assertInvariants(
				catalog, VirtualPartitionInvariants.collectionOf(evita, catalog, entityType), theCatalog.getVersion()
			);
			evita.queryCatalog(
				catalog,
				session -> {
					VirtualReferencePartitionRawRowOracle.assertConsistent(
						catalog, session, VirtualPartitionInvariants.collectionOf(evita, catalog, entityType)
					);
				}
			);
		}
	}

}
