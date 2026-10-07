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

package io.evitadb.core.cache;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.core.cache.payload.FlattenedFormula;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlanner;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.CacheableFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.reference.ReferencedOwnerExistenceFormula;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartitionSlot;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies how the formula cache treats the virtual part of a `referenceHaving` - the owners of the registered
 * single-owner partitions, which the read switch turns into one content-keyed {@link ConstantFormula} built at planning.
 * A constant is not a {@link CacheableFormula}, so it is never cached itself; what is cached is its
 * cacheable ancestor, which inherits the constant's content hash and content staleness token. Every case therefore
 * names that ancestor - the {@link AndFormula} of `and(<owner attribute>, referenceHaving(...))` - warms it, and
 * asserts that the record of **this subtree's hash** is in the {@link CacheEden} and is substituted on the next query,
 * never an aggregate hit count that any other subtree could have earned:
 *
 * - (i) a write in the same filter-value bucket of the referenced-type entity index that changes no answer keeps the
 *   ancestor's record - the constant depends on the owners, not on the bucket;
 * - (ii) a locale added to an owner the query does not match, with no locale filter in the query, keeps the record;
 * - (iii) a write registering a partition next to the matched ones in the registry keeps the record;
 * - (iv) the same body under another scope, another locale or another reference has a record of its own and its own
 *   answer;
 * - (v) a write to a matched row changes the constant and with it the ancestor's key - the cached answer is never
 *   served, the fresh one is;
 * - (vi) the bidirectional rewrite answering from a counterpart read virtually adds no invalidation beyond the one of
 *   the object reading for the same write, and the per-owner virtual constants keep their content tokens under a
 *   counterpart write that changes no answer and change them under one that does;
 *
 * and that a `referenceHaving` whose candidates are all read virtually is never an adept at all - the reuse the object
 * reading had and the virtual reading gives up.
 *
 * The cache is the engine's own {@link CacheAnteroom} and {@link CacheEden} with the minimal thresholds, driven by the
 * {@link FormulaCacheVisitor} over the formula tree the engine plans for a real query on a real catalog - the same
 * wiring {@link HeapMemoryCacheSupervisor} uses, minus its timer, so the test decides when adepts are evaluated.
 *
 * The catalog is built anew for every case - each case writes - and holds:
 *
 * - products `1` - {@link #PRODUCT_COUNT}, product `n` ranked `10 * n`, named in English, every odd one in German too;
 * - `relatedProducts`: owners `1` - `6` share target {@link #SHARED_ACCESSORY_TARGET} as accessories and owners `7` -
 *   `12` target {@link #SHARED_SPARE_TARGET} as spares (two partitions read through their objects), and owner `k` of
 *   `1` - {@link #OWNER_COUNT} holds target `20 + k` alone - an accessory for an odd `k`, a spare for an even one;
 * - `accessories`: owner `k` holds target `40 + k` alone as an accessory - every partition single-owner;
 * - `categories` (live only, filterable `priority`): every product sits in category `1` with priority `n % 5`, products
 *   `1` - `3` in category `2` with priority `4`, and products `4` - `7` alone in categories `3` - `6` with priorities
 *   `1` - `4` - reflected by the categories as `products`, so a `referenceHaving` over the products of a category is
 *   answered from the product side by the bidirectional rewrite;
 * - products {@link #FIRST_ARCHIVED_OWNER} - {@link #OWNER_COUNT} are archived once the catalog is live.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Formula cache over virtual reference partitions")
@Tag(ENGINE)
@Tag(CACHE)
@Tag(REFERENCE)
@TestInstance(Lifecycle.PER_CLASS)
class VirtualPartitionFormulaCacheTest implements EvitaTestSupport {
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_CATEGORY = "category";
	private static final String REFERENCE_RELATED = "relatedProducts";
	private static final String REFERENCE_ACCESSORIES = "accessories";
	private static final String REFERENCE_CATEGORIES = "categories";
	private static final String REFERENCE_PRODUCTS = "products";
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_RANK = "rank";
	private static final String ATTRIBUTE_NAME = "name";
	private static final String ATTRIBUTE_RELATION_TYPE = "relationType";
	private static final String ATTRIBUTE_PRIORITY = "priority";
	private static final String TYPE_ACCESSORY = "accessory";
	private static final String TYPE_SPARE = "spare";
	private static final Locale LOCALE_EN = Locale.ENGLISH;
	private static final Locale LOCALE_DE = Locale.GERMAN;
	private static final Scope[] BOTH_SCOPES = {Scope.LIVE, Scope.ARCHIVED};
	/**
	 * How many products the catalog holds.
	 */
	private static final int PRODUCT_COUNT = 60;
	/**
	 * How many products own `relatedProducts` and `accessories` rows.
	 */
	private static final int OWNER_COUNT = 20;
	/**
	 * The first of the owners archived once the catalog is live.
	 */
	private static final int FIRST_ARCHIVED_OWNER = 18;
	/**
	 * The `relatedProducts` target owners `1` - `6` share as accessories.
	 */
	private static final int SHARED_ACCESSORY_TARGET = 51;
	/**
	 * The `relatedProducts` target owners `7` - `12` share as spares.
	 */
	private static final int SHARED_SPARE_TARGET = 52;
	/**
	 * How many categories the catalog holds - the last one held by no product.
	 */
	private static final int CATEGORY_COUNT = 7;
	/**
	 * The rank bound of the owner attribute every warmed ancestor carries - owners `1` - `15`.
	 */
	private static final int RANK_BOUND = 150;
	/**
	 * Every live product.
	 */
	private static final IntPredicate LIVE_PRODUCT = pk -> pk < FIRST_ARCHIVED_OWNER || pk > OWNER_COUNT;

	private TestPaths paths;
	private Evita evita;
	private Scheduler scheduler;
	/**
	 * The anteroom of the cache of the case - the read-on arm's in a case comparing two arms.
	 */
	private CacheAnteroom anteroom;
	/**
	 * The eden of {@link #anteroom}.
	 */
	private CacheEden eden;
	/**
	 * The catalog of the case.
	 */
	private String catalog;
	/**
	 * Distinguishes the catalogs of the cases.
	 */
	private int catalogSequence;

	/**
	 * A formula tree planned for a query and the answer it computed.
	 *
	 * @param raw      the tree as the planner built it, never analysed by the cache
	 * @param analysed the tree the cache handed back for computation - the raw tree when no cache was consulted
	 * @param answer   the primary keys the analysed tree computed
	 */
	private record Run(@Nonnull Formula raw, @Nonnull Formula analysed, @Nonnull List<Integer> answer) {
	}

	/**
	 * The engine's cache wiring with the minimal thresholds: every cacheable formula is an adept, every adept that
	 * was computed once is promoted on the next evaluation.
	 *
	 * @param anteroom the anteroom
	 * @param eden     the eden behind it
	 */
	private record Cache(@Nonnull CacheAnteroom anteroom, @Nonnull CacheEden eden) {
	}

	@BeforeAll
	void setUpEvita() {
		this.paths = createTestPaths("VirtualPartitionFormulaCacheTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.scheduler = new Scheduler(
			ThreadPoolOptions.requestThreadPoolBuilder()
				.minThreadCount(1)
				.maxThreadCount(1)
				.build()
		);
	}

	@AfterAll
	void tearDownEvita() {
		this.evita.close();
		this.scheduler.shutdown();
		cleanupTestPaths(this.paths);
	}

	@BeforeEach
	void setUpCase() {
		this.catalog = "virtualPartitionCache" + (++this.catalogSequence);
		buildCatalog(this.catalog);
		final Cache cache = newCache();
		this.anteroom = cache.anteroom();
		this.eden = cache.eden();
	}

	@AfterEach
	void tearDownCase() throws Exception {
		readVirtually(false);
		// the anteroom owns the eden and hands its Flight Recorder hook back on close
		this.anteroom.close();
		this.evita.deleteCatalogIfExists(this.catalog);
	}

	@Test
	@DisplayName("the catalog registers the single-owner partitions the cases rely on")
	void shouldRegisterTheSingleOwnerPartitions() {
		// owner `k` holds target `20 + k` alone, owners 1 - 12 also a shared partition read through its object
		assertEquals(
			ownersOf(IntStream.rangeClosed(1, OWNER_COUNT).filter(LIVE_PRODUCT)),
			registeredOwners(ENTITY_PRODUCT, Scope.LIVE, REFERENCE_RELATED)
		);
		assertEquals(
			ownersOf(IntStream.rangeClosed(FIRST_ARCHIVED_OWNER, OWNER_COUNT)),
			registeredOwners(ENTITY_PRODUCT, Scope.ARCHIVED, REFERENCE_RELATED)
		);
		assertEquals(
			ownersOf(IntStream.rangeClosed(1, OWNER_COUNT).filter(LIVE_PRODUCT)),
			registeredOwners(ENTITY_PRODUCT, Scope.LIVE, REFERENCE_ACCESSORIES)
		);
		// products 4 - 7 alone in categories 3 - 6
		assertEquals(ownersOf(IntStream.rangeClosed(4, 7)), registeredOwners(ENTITY_PRODUCT, Scope.LIVE, REFERENCE_CATEGORIES));
	}

	@Test
	@DisplayName("(i) a write in the matched bucket of the type index that changes no answer keeps the cached ancestor")
	void shouldKeepTheAncestorUnderAWriteInTheSameTypeIndexBucket() {
		readVirtually(true);
		final Query query = relatedAccessoriesUpTo(RANK_BOUND, Scope.LIVE);
		final AndFormula warmed = warm(query, accessoryOwnersReadVirtually());

		// owner 1 already matches through two rows; a third accessory row lands in the same bucket of the type index
		final long[] bucketBefore = typeIndexBucketToken(REFERENCE_RELATED, ATTRIBUTE_RELATION_TYPE, TYPE_ACCESSORY);
		write(session -> session.upsertEntity(
			session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent()).orElseThrow().openForWrite()
				.setReference(REFERENCE_RELATED, 45, ref -> ref.setAttribute(ATTRIBUTE_RELATION_TYPE, TYPE_ACCESSORY))
		));
		// premise of the injected-dependency counterfactual: the bucket's token changes under this very write
		assertFalse(
			Arrays.equals(bucketBefore, typeIndexBucketToken(REFERENCE_RELATED, ATTRIBUTE_RELATION_TYPE, TYPE_ACCESSORY)),
			"The write must change the bucket of the type index, or keeping the ancestor proves nothing"
		);

		assertHit(query, warmed, accessoryOwnersReadVirtually(), expectedRelatedAccessories(RANK_BOUND));
	}

	@Test
	@DisplayName("(ii) a locale added to an owner the query does not match keeps the cached ancestor")
	void shouldKeepTheAncestorUnderALocaleChangeOfAnUnmatchedOwner() {
		readVirtually(true);
		final Query query = relatedAccessoriesUpTo(RANK_BOUND, Scope.LIVE);
		final AndFormula warmed = warm(query, accessoryOwnersReadVirtually());

		// owner 8 holds only spares and has no German name yet
		final long[] localeBefore = globalLocaleToken(LOCALE_DE);
		write(session -> session.upsertEntity(
			session.getEntity(ENTITY_PRODUCT, 8, entityFetchAllContent()).orElseThrow().openForWrite()
				.setAttribute(ATTRIBUTE_NAME, LOCALE_DE, "Produkt 8")
		));
		assertFalse(
			Arrays.equals(localeBefore, globalLocaleToken(LOCALE_DE)),
			"The write must change the global locale bitmap of the owners, or keeping the ancestor proves nothing"
		);

		assertHit(query, warmed, accessoryOwnersReadVirtually(), expectedRelatedAccessories(RANK_BOUND));
	}

	@Test
	@DisplayName("(iii) a partition registered next to the matched ones keeps the cached ancestor")
	void shouldKeepTheAncestorUnderAWriteToTheRegistry() {
		readVirtually(true);
		final Query query = relatedAccessoriesUpTo(RANK_BOUND, Scope.LIVE);
		final AndFormula warmed = warm(query, accessoryOwnersReadVirtually());

		// owner 8 takes target 46 alone as a spare - a new single-owner partition, which the body does not match
		final Object registryBefore = typeIndexOf(ENTITY_PRODUCT, Scope.LIVE, REFERENCE_RELATED)
			.getVirtualReferencePartitionIndex();
		final Set<Integer> registeredBefore = registeredPartitions(ENTITY_PRODUCT, Scope.LIVE, REFERENCE_RELATED);
		write(session -> session.upsertEntity(
			session.getEntity(ENTITY_PRODUCT, 8, entityFetchAllContent()).orElseThrow().openForWrite()
				.setReference(REFERENCE_RELATED, 46, ref -> ref.setAttribute(ATTRIBUTE_RELATION_TYPE, TYPE_SPARE))
		));
		final Set<Integer> registeredAfter = registeredPartitions(ENTITY_PRODUCT, Scope.LIVE, REFERENCE_RELATED);
		assertEquals(registeredBefore.size() + 1, registeredAfter.size(), "The write must register one partition");
		assertNotSame(
			registryBefore,
			typeIndexOf(ENTITY_PRODUCT, Scope.LIVE, REFERENCE_RELATED).getVirtualReferencePartitionIndex(),
			"The write must replace the registry instance, or keeping the ancestor proves nothing"
		);

		assertHit(query, warmed, accessoryOwnersReadVirtually(), expectedRelatedAccessories(RANK_BOUND));
	}

	@Test
	@DisplayName("(iv) the same body under another locale, scope or reference has its own entry and answer")
	void shouldKeepDiscriminatedBodiesApart() {
		readVirtually(true);
		// locale: one type index, the locale leaf narrows the virtual rows differently
		final Query german = accessoriesInLocale(LOCALE_DE);
		final Query english = accessoriesInLocale(LOCALE_EN);
		final int[] liveOwners = IntStream.rangeClosed(1, OWNER_COUNT).filter(LIVE_PRODUCT).toArray();
		final int[] germanOwners = IntStream.of(liveOwners).filter(pk -> pk % 2 == 1).toArray();
		assertDiscriminated(german, germanOwners, english, liveOwners);

		// scope: the same body over the live and the archived owners
		final int allRanks = PRODUCT_COUNT * 10;
		assertDiscriminated(
			accessoriesUpTo(allRanks, Scope.LIVE),
			liveOwners,
			accessoriesUpTo(allRanks, Scope.ARCHIVED),
			IntStream.rangeClosed(FIRST_ARCHIVED_OWNER, OWNER_COUNT).toArray()
		);

		// reference: the same body over another reference of the same owners
		assertDiscriminated(
			accessoriesUpTo(RANK_BOUND, Scope.LIVE),
			liveOwners,
			relatedAccessoriesUpTo(RANK_BOUND, Scope.LIVE),
			accessoryOwnersReadVirtually()
		);
	}

	@Test
	@DisplayName("(v) a write to a matched row changes the ancestor's key, so the fresh answer is computed")
	void shouldMissAfterAWriteToAMatchedRow() {
		readVirtually(true);
		final Query query = relatedAccessoriesUpTo(RANK_BOUND, Scope.LIVE);
		final AndFormula warmed = warm(query, accessoryOwnersReadVirtually());
		final long warmedHash = recordHash(warmed);

		// owner 9 matches only through its single-owner accessory row - which becomes a spare
		write(session -> session.upsertEntity(
			session.getEntity(ENTITY_PRODUCT, 9, entityFetchAllContent()).orElseThrow().openForWrite()
				.setReference(REFERENCE_RELATED, 29, ref -> ref.setAttribute(ATTRIBUTE_RELATION_TYPE, TYPE_SPARE))
		));

		// the answer first: a constant remembered from before the write would still hold owner 9
		final Run fresh = run(query, this.anteroom);
		final List<Integer> expected = expectedRelatedAccessories(RANK_BOUND).stream().filter(pk -> pk != 9).toList();
		assertEquals(expected, fresh.answer());
		assertEquals(expected, readOff(() -> run(query, null).answer()));
		final int[] remainingVirtualOwners = IntStream.of(accessoryOwnersReadVirtually())
			.filter(pk -> pk != 9).toArray();
		final AndFormula ancestor = findAncestor(fresh.raw(), remainingVirtualOwners);
		assertNotEquals(warmedHash, recordHash(ancestor), "The changed constant must change the ancestor's key");
		assertFalse(isCached(ancestor), "No record may answer the changed ancestor");
		assertFalse(
			containsFlattened(fresh.analysed(), recordHash(ancestor)) ||
				containsFlattened(fresh.analysed(), warmedHash),
			"Neither the changed nor the warmed ancestor may be substituted: " + fresh.analysed()
		);
	}

	@Test
	@DisplayName("a referenceHaving whose candidates are all read virtually is never a cache adept")
	void shouldNeverAdmitABareAllVirtualReferenceHaving() {
		final Query query = query(
			collection(ENTITY_PRODUCT),
			filterBy(scope(Scope.LIVE), referenceHaving(REFERENCE_ACCESSORIES, attributeEquals(ATTRIBUTE_RELATION_TYPE, TYPE_ACCESSORY)))
		);
		final int[] liveOwners = IntStream.rangeClosed(1, OWNER_COUNT).filter(LIVE_PRODUCT).toArray();

		// the object reading: the body over every partition is a cacheable tree, admitted as an adept
		final Cache objectCache = newCache();
		try {
			final Run objectReading = readOff(() -> run(query, objectCache.anteroom()));
			assertEquals(toList(liveOwners), objectReading.answer());
			final List<Formula> objectAdepts = cacheableNodes(objectReading.raw()).stream()
				.filter(it -> objectCache.anteroom().getCacheAdept(this.catalog, ENTITY_PRODUCT, it) != null)
				.toList();
			assertFalse(objectAdepts.isEmpty(), "The object reading must be cacheable: " + objectReading.raw());
		} finally {
			closeQuietly(objectCache);
		}

		// the virtual reading: one constant over the owners, no cacheable formula anywhere above it
		readVirtually(true);
		final long rowsBefore = maintenance().getVirtualRowsRead();
		final Run virtualReading = run(query, this.anteroom);
		assertTrue(maintenance().getVirtualRowsRead() > rowsBefore, "The query must read virtual rows");
		assertEquals(toList(liveOwners), virtualReading.answer());
		final List<Formula> path = findPath(virtualReading.raw(), isConstantOver(liveOwners));
		assertFalse(path.isEmpty(), "The virtual owners must be one constant: " + virtualReading.raw());
		for (final Formula node : path) {
			assertFalse(
				node instanceof CacheableFormula,
				"No formula holding the constant may be cacheable - the lost reuse this case documents: " + node
			);
			assertNull(this.anteroom.getCacheAdept(this.catalog, ENTITY_PRODUCT, node));
		}
		this.anteroom.evaluateAssociatesSynchronously();
		assertEquals(0, this.eden.getCacheRecordCount(), "Nothing of the virtual reading may reach the eden");
	}

	@Test
	@DisplayName("(vi) the bidirectional rewrite over a virtual counterpart adds no invalidation to the object reading")
	void shouldAddNoInvalidationToTheBidirectionalRewrite() {
		final Query query = categoriesWithPriorityAbove(1);
		// categories 1, 2 and 4 - 6: category 3 holds only product 4 with priority 1, category 7 holds nothing
		final List<Integer> expected = List.of(1, 2, 4, 5, 6);
		final Cache objectCache = newCache();
		try {
			// the read-off arm warms its own cache, the read-on arm this case's
			final Run objectWarm = readOff(() -> warmRun(query, objectCache));
			final Run virtualWarm = readOnProducts(() -> warmRun(query, cache()));
			assertEquals(expected, objectWarm.answer());
			assertEquals(expected, virtualWarm.answer());
			final AndFormula objectAncestor = findExistenceAncestor(objectWarm.raw());
			final AndFormula virtualAncestor = findExistenceAncestor(virtualWarm.raw());
			assertTrue(isCached(objectCache, ENTITY_CATEGORY, objectAncestor), "The object reading must have been warmed");
			assertTrue(isCached(cache(), ENTITY_CATEGORY, virtualAncestor), "The virtual reading must have been warmed");
			final long rowsBefore = maintenance().getVirtualRowsRead();
			final Set<Long> virtualCarrier = readOnProducts(() -> perOwnerIds(run(query, null).raw()));
			assertTrue(maintenance().getVirtualRowsRead() > rowsBefore, "The rewrite must read virtual rows");
			final Set<Long> objectCarrier = perOwnerIds(objectWarm.raw());

			// a write outside the product collection - the code of a category the query excludes: both readings keep
			// their ancestors (any product write misses in both, because the referenced entity formula of the rewrite
			// reads the product primary key bitmap, which every product upsert replaces)
			write(session -> session.upsertEntity(
				session.getEntity(ENTITY_CATEGORY, CATEGORY_COUNT, entityFetchAllContent()).orElseThrow().openForWrite()
					.setAttribute(ATTRIBUTE_CODE, "C" + CATEGORY_COUNT + "x")
			));
			assertSameInvalidation(query, objectCache, true, expected);

			// a counterpart write that changes no answer: product 30 takes category 7, which no product held, with
			// priority 1 - a new single-owner partition the body does not match, in a category the code excludes
			final long[] bucketBefore = counterpartBucketToken();
			write(session -> session.upsertEntity(
				session.getEntity(ENTITY_PRODUCT, 30, entityFetchAllContent()).orElseThrow().openForWrite()
					.setReference(REFERENCE_CATEGORIES, CATEGORY_COUNT, ref -> ref.setAttribute(ATTRIBUTE_PRIORITY, 1))
			));
			assertFalse(
				Arrays.equals(bucketBefore, counterpartBucketToken()),
				"The write must change the bucket of the counterpart type index, or the carrier proves nothing"
			);
			// the baseline misses already - the existence formula hashes the counterpart type index id into its key
			final boolean objectHit = assertSameInvalidation(query, objectCache, null, expected);
			assertFalse(objectHit, "The object reading misses after a counterpart write - the baseline of this gate");
			// the isolated carrier: the staleness tokens of the per-owner formulas of the existence formula - free of the
			// counterpart type index id its key hashes and of the referenced entity formula every product write
			// replaces, so there the baseline can hit, and the virtual reading must hit where it does
			final Run objectAfter = readOff(() -> run(query, null));
			final Run virtualAfter = readOnProducts(() -> run(query, null));
			assertEquals(
				objectCarrier, perOwnerIds(objectAfter.raw()),
				"The object reading keeps its per-owner tokens - the baseline the virtual reading is compared to"
			);
			assertEquals(
				virtualCarrier, perOwnerIds(virtualAfter.raw()),
				"The virtual reading must keep its per-owner tokens where the object reading keeps them"
			);
			// the per-owner virtual constants of products 5, 6 and 7, each alone in its category with a priority
			// above 1, carry their content and nothing else
			assertTrue(
				virtualCarrier.containsAll(contentTokens(5, 6, 7)),
				"The per-owner virtual constants carry their content: " + virtualCarrier
			);

			// a counterpart write that changes the answer: product 5, alone in category 4, drops to priority 0
			write(session -> session.upsertEntity(
				session.getEntity(ENTITY_PRODUCT, 5, entityFetchAllContent()).orElseThrow().openForWrite()
					.setReference(REFERENCE_CATEGORIES, 4, ref -> ref.setAttribute(ATTRIBUTE_PRIORITY, 0))
			));
			final Run objectChanged = readOff(() -> run(query, null));
			final Run virtualChanged = readOnProducts(() -> run(query, null));
			final List<Integer> changed = List.of(1, 2, 5, 6);
			assertEquals(changed, objectChanged.answer());
			assertEquals(changed, virtualChanged.answer());
			final Set<Long> changedCarrier = perOwnerIds(virtualChanged.raw());
			assertTrue(changedCarrier.containsAll(contentTokens(6, 7)), "The unchanged constants keep their content");
			assertFalse(changedCarrier.containsAll(contentTokens(5)), "The changed constant must lose its content token");
			assertNotEquals(virtualCarrier, changedCarrier, "A write that changes the answer must change the tokens");
		} finally {
			closeQuietly(objectCache);
		}
	}

	/**
	 * Warms the ancestor of the virtual constant over the given owners: plans and computes the query through the cache
	 * so its cacheable formulas become adepts, promotes them, and computes it again so the records are initialized.
	 *
	 * @param query          the query
	 * @param virtualOwners  the owners the virtual constant holds - identifies it in the tree
	 * @return the warmed ancestor, whose record is initialized in the eden
	 */
	@Nonnull
	private AndFormula warm(@Nonnull Query query, @Nonnull int[] virtualOwners) {
		final long rowsBefore = maintenance().getVirtualRowsRead();
		final Run warmed = warmRun(query, cache());
		assertTrue(maintenance().getVirtualRowsRead() > rowsBefore, "The query must read virtual rows");
		final AndFormula ancestor = findAncestor(warmed.raw(), virtualOwners);
		assertTrue(isCached(ancestor), "The warmed ancestor must be in the eden: " + ancestor);
		return ancestor;
	}

	/**
	 * Runs the query through a cache until its records are initialized: once to register the adepts, an evaluation
	 * promoting them, once more to initialize the records.
	 *
	 * @param query the query
	 * @param cache the cache
	 * @return the run that initialized the records
	 */
	@Nonnull
	private Run warmRun(@Nonnull Query query, @Nonnull Cache cache) {
		run(query, cache.anteroom());
		cache.anteroom().evaluateAssociatesSynchronously();
		return run(query, cache.anteroom());
	}

	/**
	 * Asserts the ancestor warmed before a write is still answered from the eden: the freshly planned ancestor has the
	 * same key, its record is valid, the next query substitutes it, and the answer is the expected one.
	 *
	 * @param query         the query
	 * @param warmed        the ancestor warmed before the write
	 * @param virtualOwners the owners the virtual constant holds after the write
	 * @param expected      the expected answer
	 */
	private void assertHit(
		@Nonnull Query query,
		@Nonnull AndFormula warmed,
		@Nonnull int[] virtualOwners,
		@Nonnull List<Integer> expected
	) {
		final Run next = run(query, this.anteroom);
		final AndFormula ancestor = findAncestor(next.raw(), virtualOwners);
		assertEquals(recordHash(warmed), recordHash(ancestor), "The write must not change the ancestor's key");
		assertTrue(isCached(ancestor), "The record of the ancestor must still answer it: " + ancestor);
		assertTrue(
			isSubstituted(next, ancestor),
			"The next query must substitute the ancestor or a cacheable formula above it: " + next.analysed()
		);
		assertEquals(expected, next.answer());
		assertEquals(expected, readOff(() -> run(query, null).answer()));
	}

	/**
	 * Asserts two queries differing only in a discriminator never share a record: the second, planned after the first
	 * was warmed, has another key, is not answered from the eden, and computes its own answer.
	 *
	 * @param first          the query warmed first
	 * @param firstOwners    the owners the first query's virtual constant holds
	 * @param second         the query planned after it
	 * @param secondOwners   the owners the second query's virtual constant holds
	 */
	private void assertDiscriminated(
		@Nonnull Query first,
		@Nonnull int[] firstOwners,
		@Nonnull Query second,
		@Nonnull int[] secondOwners
	) {
		final AndFormula warmed = warm(first, firstOwners);
		final Run next = run(second, this.anteroom);
		final AndFormula ancestor = findAncestor(next.raw(), secondOwners);
		assertNotEquals(recordHash(warmed), recordHash(ancestor), "Discriminated bodies must not share a key");
		assertFalse(isCached(ancestor), "The second body must not be answered by the first one's record");
		final List<Integer> expected = readOff(() -> run(second, null).answer());
		assertEquals(expected, next.answer());
		assertNotEquals(readOff(() -> run(first, null).answer()), next.answer(), "The discriminator must matter");
	}

	/**
	 * Asserts the read-on arm is invalidated exactly when the read-off arm is: both re-plan the query, and the record of
	 * each one's ancestor is valid in both or in neither; both answer as expected.
	 *
	 * @param query       the query
	 * @param objectCache the cache of the read-off arm
	 * @param expectedHit the expected outcome, `null` when only the equality of the two arms is asserted
	 * @param expected    the expected answer
	 * @return TRUE when the read-off arm hit
	 */
	private boolean assertSameInvalidation(
		@Nonnull Query query,
		@Nonnull Cache objectCache,
		@Nullable Boolean expectedHit,
		@Nonnull List<Integer> expected
	) {
		final Run objectRun = readOff(() -> run(query, objectCache.anteroom()));
		final Run virtualRun = readOnProducts(() -> run(query, cache().anteroom()));
		final boolean objectHit = isCached(objectCache, ENTITY_CATEGORY, findExistenceAncestor(objectRun.raw()));
		final boolean virtualHit = isCached(cache(), ENTITY_CATEGORY, findExistenceAncestor(virtualRun.raw()));
		assertEquals(objectHit, virtualHit, "The virtual reading must be invalidated exactly when the object reading is");
		if (expectedHit != null) {
			assertEquals(expectedHit, objectHit);
		}
		assertEquals(expected, objectRun.answer());
		assertEquals(expected, virtualRun.answer());
		return objectHit;
	}

	/**
	 * Returns the staleness tokens of the per-owner formulas of the existence formula in the tree - its transactional
	 * ids minus those of the referenced entity formula, the only inner formula it has.
	 *
	 * @param tree the tree
	 * @return the tokens
	 */
	@Nonnull
	private static Set<Long> perOwnerIds(@Nonnull Formula tree) {
		final ReferencedOwnerExistenceFormula existence = findExistence(tree);
		final Set<Long> ids = idsOf(existence);
		ids.removeAll(idsOf(existence.getInnerFormulas()[0]));
		return ids;
	}

	/**
	 * Returns the content tokens of the per-owner constants holding one referenced entity each.
	 *
	 * @param referencedEntities the referenced entity of each constant
	 * @return the tokens
	 */
	@Nonnull
	private static Set<Long> contentTokens(@Nonnull int... referencedEntities) {
		final Set<Long> tokens = new HashSet<>();
		for (final int referencedEntity : referencedEntities) {
			tokens.addAll(idsOf(new ConstantFormula(new BaseBitmap(referencedEntity))));
		}
		return tokens;
	}

	/**
	 * Returns the transactional ids of a formula as a set.
	 *
	 * @param formula the formula
	 * @return its ids
	 */
	@Nonnull
	private static Set<Long> idsOf(@Nonnull Formula formula) {
		final Set<Long> ids = new HashSet<>();
		for (final long id : formula.gatherTransactionalIds()) {
			ids.add(id);
		}
		return ids;
	}

	/**
	 * Returns the existence formula the bidirectional rewrite put into the tree.
	 *
	 * @param tree the tree
	 * @return the existence formula
	 */
	@Nonnull
	private static ReferencedOwnerExistenceFormula findExistence(@Nonnull Formula tree) {
		final List<Formula> path = findPath(tree, ReferencedOwnerExistenceFormula.class::isInstance);
		assertFalse(path.isEmpty(), "The bidirectional rewrite must answer the query: " + tree);
		return (ReferencedOwnerExistenceFormula) path.getLast();
	}

	/**
	 * Returns the nearest {@link AndFormula} above the existence formula of the bidirectional rewrite.
	 *
	 * @param tree the tree
	 * @return the ancestor
	 */
	@Nonnull
	private static AndFormula findExistenceAncestor(@Nonnull Formula tree) {
		return nearestAnd(tree, ReferencedOwnerExistenceFormula.class::isInstance);
	}

	/**
	 * Returns the nearest {@link AndFormula} above the virtual constant over the given owners - the cacheable ancestor
	 * every case names.
	 *
	 * @param tree          the tree
	 * @param virtualOwners the owners the constant holds
	 * @return the ancestor
	 */
	@Nonnull
	private static AndFormula findAncestor(@Nonnull Formula tree, @Nonnull int[] virtualOwners) {
		return nearestAnd(tree, isConstantOver(virtualOwners));
	}

	/**
	 * Returns the nearest {@link AndFormula} above the target that conjoins it with something else - one of its inner
	 * formulas does not hold the target, as the owner attribute next to the `referenceHaving` does not.
	 *
	 * @param tree   the tree
	 * @param target identifies the target formula
	 * @return the ancestor
	 */
	@Nonnull
	private static AndFormula nearestAnd(@Nonnull Formula tree, @Nonnull Predicate<Formula> target) {
		final List<Formula> path = findPath(tree, target);
		assertFalse(path.isEmpty(), "The target formula must be in the tree: " + tree);
		for (int i = path.size() - 2; i >= 0; i--) {
			if (
				path.get(i) instanceof AndFormula andFormula &&
					Arrays.stream(andFormula.getInnerFormulas()).anyMatch(it -> findPath(it, target).isEmpty())
			) {
				return andFormula;
			}
		}
		throw new AssertionError("No AndFormula conjoins the target formula with another one: " + tree);
	}

	/**
	 * Returns a predicate matching a constant over exactly the given primary keys.
	 *
	 * @param primaryKeys the primary keys
	 * @return the predicate
	 */
	@Nonnull
	private static Predicate<Formula> isConstantOver(@Nonnull int[] primaryKeys) {
		return formula -> formula instanceof ConstantFormula constant &&
			Arrays.equals(primaryKeys, constant.getDelegate().getArray());
	}

	/**
	 * Returns the path from the root to the first formula matching the predicate, depth first.
	 *
	 * @param tree      the tree
	 * @param predicate the predicate
	 * @return the path, root first, empty when no formula matches
	 */
	@Nonnull
	private static List<Formula> findPath(@Nonnull Formula tree, @Nonnull Predicate<Formula> predicate) {
		final List<Formula> path = new ArrayList<>();
		return findPath(tree, predicate, path) ? path : List.of();
	}

	/**
	 * Extends the path by the formula and its subtree while looking for a match.
	 *
	 * @param formula   the formula visited
	 * @param predicate the predicate
	 * @param path      the path to the formula, extended in place
	 * @return TRUE when the match was found in the subtree
	 */
	private static boolean findPath(
		@Nonnull Formula formula,
		@Nonnull Predicate<Formula> predicate,
		@Nonnull List<Formula> path
	) {
		path.add(formula);
		if (predicate.test(formula)) {
			return true;
		}
		for (final Formula inner : formula.getInnerFormulas()) {
			if (findPath(inner, predicate, path)) {
				return true;
			}
		}
		path.removeLast();
		return false;
	}

	/**
	 * Returns every cacheable formula of a tree.
	 *
	 * @param tree the tree
	 * @return the cacheable formulas
	 */
	@Nonnull
	private static List<Formula> cacheableNodes(@Nonnull Formula tree) {
		final List<Formula> result = new ArrayList<>();
		final List<Formula> stack = new ArrayList<>(List.of(tree));
		while (!stack.isEmpty()) {
			final Formula formula = stack.removeLast();
			if (formula instanceof CacheableFormula) {
				result.add(formula);
			}
			stack.addAll(Arrays.asList(formula.getInnerFormulas()));
		}
		return result;
	}

	/**
	 * Returns TRUE when the analysed tree of a run substitutes the ancestor - or a cacheable formula above it, which
	 * answers the ancestor's subtree too - by a record of the eden.
	 *
	 * @param run      the run
	 * @param ancestor the ancestor in the run's raw tree
	 * @return TRUE when substituted
	 */
	private boolean isSubstituted(@Nonnull Run run, @Nonnull AndFormula ancestor) {
		final List<Formula> path = findPath(run.raw(), it -> it == ancestor);
		for (final Formula formula : path) {
			if (formula instanceof CacheableFormula && containsFlattened(run.analysed(), recordHash(formula))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns TRUE when the tree holds a record of the eden under the given key.
	 *
	 * @param tree       the tree
	 * @param recordHash the key
	 * @return TRUE when found
	 */
	private static boolean containsFlattened(@Nonnull Formula tree, long recordHash) {
		return !findPath(tree, it -> it instanceof FlattenedFormula flattened && flattened.getHash() == recordHash)
			.isEmpty();
	}

	/**
	 * Returns the key the eden stores the formula under in the catalog of the case.
	 *
	 * @param formula the formula
	 * @return the key
	 */
	private long recordHash(@Nonnull Formula formula) {
		return CacheAnteroom.computeDataStructureHash(this.catalog, ENTITY_PRODUCT, formula);
	}

	/**
	 * Returns TRUE when the eden of the case holds an initialized record valid for the product formula.
	 *
	 * @param formula the formula, freshly planned for a product query
	 * @return TRUE when the record would answer it
	 */
	private boolean isCached(@Nonnull Formula formula) {
		return isCached(cache(), ENTITY_PRODUCT, formula);
	}

	/**
	 * Returns TRUE when the eden holds an initialized record valid for the formula - one whose key is the formula's and
	 * whose transactional id hash equals the formula's.
	 *
	 * @param cache      the cache
	 * @param entityType the collection the formula was planned for
	 * @param formula    the formula, freshly planned
	 * @return TRUE when the record would answer it
	 */
	private boolean isCached(@Nonnull Cache cache, @Nonnull String entityType, @Nonnull Formula formula) {
		final long hash = CacheAnteroom.computeDataStructureHash(this.catalog, entityType, formula);
		return inEngineSession(
			session -> cache.eden().getCachedRecord(session, this.catalog, entityType, formula, Formula.class, hash)
				instanceof FlattenedFormula
		);
	}

	/**
	 * Plans the query on the current snapshot of the catalog of the case, routes the planned tree through the cache
	 * when one is given, and computes it.
	 *
	 * @param query    the query, naming its collection
	 * @param anteroom the anteroom of the cache, `null` to compute the planned tree as it is
	 * @return the run
	 */
	@Nonnull
	private Run run(@Nonnull Query query, @Nullable CacheAnteroom anteroom) {
		final String entityType = (String) query.getCollection().getEntityType();
		return inEngineSession(
			session -> {
				final QueryPlanningContext context = collectionOf(entityType).createQueryContext(
					new EvitaRequest(query.normalizeQuery(), OffsetDateTime.now(), EntityReference.class, null),
					session
				);
				final Formula raw = QueryPlanner.planQuery(context).getFilter();
				final Formula analysed = anteroom == null ?
					raw : FormulaCacheVisitor.analyse(session, entityType, raw, anteroom);
				try (final QueryExecutionContext executionContext = context.createExecutionContext()) {
					analysed.initialize(executionContext);
					return new Run(raw, analysed, toList(analysed.compute().getArray()));
				}
			}
		);
	}

	/**
	 * Runs the logic in a session of the catalog of the case, handing it the engine's own session object - the planning
	 * context accepts no other, and the engine hands it only to the logic of a read-write session. The logic writes
	 * nothing, so the session sees exactly the committed snapshot; the cache does not consult the session's mode here,
	 * only {@link HeapMemoryCacheSupervisor} does, and this test drives the visitor below it.
	 *
	 * @param logic the logic
	 * @param <T>   the type of the result
	 * @return the result
	 */
	private <T> T inEngineSession(@Nonnull Function<EvitaSessionContract, T> logic) {
		return this.evita.updateCatalog(this.catalog, logic);
	}

	/**
	 * Returns the query of products ranked up to the bound that hold an accessory row of `relatedProducts`.
	 *
	 * @param rankBound the rank bound
	 * @param scope     the scope
	 * @return the query
	 */
	@Nonnull
	private static Query relatedAccessoriesUpTo(int rankBound, @Nonnull Scope scope) {
		return productQuery(scope, REFERENCE_RELATED, rankBound, attributeEquals(ATTRIBUTE_RELATION_TYPE, TYPE_ACCESSORY));
	}

	/**
	 * Returns the query of products ranked up to the bound that hold an accessory row of `accessories`.
	 *
	 * @param rankBound the rank bound
	 * @param scope     the scope
	 * @return the query
	 */
	@Nonnull
	private static Query accessoriesUpTo(int rankBound, @Nonnull Scope scope) {
		return productQuery(scope, REFERENCE_ACCESSORIES, rankBound, attributeEquals(ATTRIBUTE_RELATION_TYPE, TYPE_ACCESSORY));
	}

	/**
	 * Returns the query of live products ranked up to {@link #RANK_BOUND} holding an `accessories` row and named in
	 * the locale.
	 *
	 * @param locale the locale
	 * @return the query
	 */
	@Nonnull
	private static Query accessoriesInLocale(@Nonnull Locale locale) {
		return productQuery(Scope.LIVE, REFERENCE_ACCESSORIES, RANK_BOUND, entityLocaleEquals(locale));
	}

	/**
	 * Returns the query of products of the scope ranked up to the bound holding a row of the reference satisfying the
	 * body - `and(attributeLessThanEquals(rank), referenceHaving(...))`, the ancestor every case names.
	 *
	 * @param scope     the scope
	 * @param reference the reference
	 * @param rankBound the rank bound
	 * @param body      the body of the `referenceHaving`
	 * @return the query
	 */
	@Nonnull
	private static Query productQuery(
		@Nonnull Scope scope,
		@Nonnull String reference,
		int rankBound,
		@Nonnull FilterConstraint body
	) {
		return query(
			collection(ENTITY_PRODUCT),
			filterBy(
				scope(scope),
				and(attributeLessThanEquals(ATTRIBUTE_RANK, rankBound), referenceHaving(reference, body))
			)
		);
	}

	/**
	 * Returns the query of categories with a code holding a product with a priority above the bound - answered by the
	 * bidirectional rewrite from the product side.
	 *
	 * @param priority the priority bound
	 * @return the query
	 */
	@Nonnull
	private static Query categoriesWithPriorityAbove(int priority) {
		return query(
			collection(ENTITY_CATEGORY),
			filterBy(
				scope(Scope.LIVE),
				and(
					attributeInSet(
						ATTRIBUTE_CODE,
						IntStream.rangeClosed(1, CATEGORY_COUNT - 1).mapToObj(it -> "C" + it).toArray(String[]::new)
					),
					referenceHaving(REFERENCE_PRODUCTS, attributeGreaterThan(ATTRIBUTE_PRIORITY, priority))
				)
			)
		);
	}

	/**
	 * Returns the live owners holding a single-owner accessory row of `relatedProducts` - the odd owners. The virtual
	 * constant holds all of them whatever the owner attribute next to the `referenceHaving` admits: the conjunction
	 * above it narrows them.
	 *
	 * @return the owners, ascending
	 */
	@Nonnull
	private static int[] accessoryOwnersReadVirtually() {
		return IntStream.rangeClosed(1, OWNER_COUNT)
			.filter(LIVE_PRODUCT)
			.filter(pk -> pk % 2 == 1)
			.toArray();
	}

	/**
	 * Returns the live products ranked up to the bound holding an accessory row of `relatedProducts` - the owners of the
	 * shared accessory target and the odd owners.
	 *
	 * @param rankBound the rank bound
	 * @return the products, ascending
	 */
	@Nonnull
	private static List<Integer> expectedRelatedAccessories(int rankBound) {
		return IntStream.rangeClosed(1, Math.min(OWNER_COUNT, rankBound / 10))
			.filter(LIVE_PRODUCT)
			.filter(pk -> pk <= 6 || pk % 2 == 1)
			.boxed()
			.toList();
	}

	/**
	 * Returns the token of the bucket of a value in a filter index of the live product type index of a reference - the
	 * staleness token a constant over that bucket would carry.
	 *
	 * @param reference the reference
	 * @param attribute the attribute of the reference
	 * @param value     the value
	 * @return the token
	 */
	@Nonnull
	private long[] typeIndexBucketToken(@Nonnull String reference, @Nonnull String attribute, @Nonnull Serializable value) {
		final ReferencedTypeEntityIndex typeIndex = typeIndexOf(ENTITY_PRODUCT, Scope.LIVE, reference);
		final AttributeIndexKey key = typeIndex.getFilterIndexes()
			.stream()
			.filter(it -> attribute.equals(it.attributeName()))
			.findFirst()
			.orElseThrow();
		return typeIndex.getFilterIndex(key).getRecordsEqualToFormula(value).gatherTransactionalIds();
	}

	/**
	 * Returns the token of the bucket of priority `1` in the live type index of `categories` - the counterpart of
	 * `products`.
	 *
	 * @return the token
	 */
	@Nonnull
	private long[] counterpartBucketToken() {
		return typeIndexBucketToken(REFERENCE_CATEGORIES, ATTRIBUTE_PRIORITY, 1);
	}

	/**
	 * Returns the token of the global locale bitmap of the live products.
	 *
	 * @param locale the locale
	 * @return the token
	 */
	@Nonnull
	private long[] globalLocaleToken(@Nonnull Locale locale) {
		final EntityIndex globalIndex = collectionOf(ENTITY_PRODUCT).getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)
		);
		assertNotNull(globalIndex);
		return globalIndex.getRecordsWithLanguageFormula(locale).gatherTransactionalIds();
	}

	/**
	 * Returns the type index of a reference of a collection in a scope.
	 *
	 * @param entityType the collection
	 * @param scope      the scope
	 * @param reference  the reference
	 * @return the type index
	 */
	@Nonnull
	private ReferencedTypeEntityIndex typeIndexOf(@Nonnull String entityType, @Nonnull Scope scope, @Nonnull String reference) {
		final EntityIndex index = collectionOf(entityType).getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, reference)
		);
		return assertInstanceOf(ReferencedTypeEntityIndex.class, index);
	}

	/**
	 * Returns the partitions registered as single-owner in the type index of a reference.
	 *
	 * @param entityType the collection
	 * @param scope      the scope
	 * @param reference  the reference
	 * @return the partition primary keys
	 */
	@Nonnull
	private Set<Integer> registeredPartitions(@Nonnull String entityType, @Nonnull Scope scope, @Nonnull String reference) {
		final Set<Integer> partitions = new TreeSet<>();
		typeIndexOf(entityType, scope, reference).getVirtualReferencePartitionIndex().forEachEntry(
			VirtualReferencePartitionSlot.PARTITION_OWNER, (partition, row) -> partitions.add(partition)
		);
		return partitions;
	}

	/**
	 * Returns the owners of the partitions registered as single-owner in the type index of a reference.
	 *
	 * @param entityType the collection
	 * @param scope      the scope
	 * @param reference  the reference
	 * @return the owners
	 */
	@Nonnull
	private Set<Integer> registeredOwners(@Nonnull String entityType, @Nonnull Scope scope, @Nonnull String reference) {
		final Set<Integer> owners = new TreeSet<>();
		typeIndexOf(entityType, scope, reference).getVirtualReferencePartitionIndex().forEachEntry(
			VirtualReferencePartitionSlot.PARTITION_OWNER, (partition, row) -> owners.add((int) (row >>> 32))
		);
		return owners;
	}

	/**
	 * Collects primary keys into a sorted set.
	 *
	 * @param primaryKeys the primary keys
	 * @return the set
	 */
	@Nonnull
	private static Set<Integer> ownersOf(@Nonnull IntStream primaryKeys) {
		return primaryKeys.boxed().collect(TreeSet::new, Set::add, Set::addAll);
	}

	/**
	 * Converts primary keys to a list.
	 *
	 * @param primaryKeys the primary keys
	 * @return the list
	 */
	@Nonnull
	private static List<Integer> toList(@Nonnull int[] primaryKeys) {
		return IntStream.of(primaryKeys).boxed().toList();
	}

	/**
	 * Returns the current collection of the catalog of the case - a write replaces it.
	 *
	 * @param entityType the collection
	 * @return the collection
	 */
	@Nonnull
	private EntityCollection collectionOf(@Nonnull String entityType) {
		return (EntityCollection) this.evita.getCatalogInstance(this.catalog)
			.orElseThrow()
			.getCollectionForEntity(entityType)
			.orElseThrow();
	}

	/**
	 * Returns the switch holder of the catalog of the case.
	 *
	 * @return the switch holder
	 */
	@Nonnull
	private VirtualReferencePartitionMaintenance maintenance() {
		return ((Catalog) this.evita.getCatalogInstance(this.catalog).orElseThrow())
			.getVirtualReferencePartitionMaintenance();
	}

	/**
	 * Turns the read switch of the products on or off.
	 *
	 * @param readVirtually TRUE to read the registered partitions of the products virtually
	 */
	private void readVirtually(boolean readVirtually) {
		// TODO JNO - remove at the end of #1615 (the read switch; the read-off arms compare against it)
		maintenance().setReadVirtually(ENTITY_PRODUCT, readVirtually);
	}

	/**
	 * Runs the supplier with the read switch of the products off, restoring it afterwards.
	 *
	 * @param supplier the supplier
	 * @param <T>      the type of the result
	 * @return the result
	 */
	private <T> T readOff(@Nonnull Supplier<T> supplier) {
		final boolean before = maintenance().isReadVirtually(ENTITY_PRODUCT);
		readVirtually(false);
		try {
			return supplier.get();
		} finally {
			readVirtually(before);
		}
	}

	/**
	 * Runs the supplier with the read switch of the products on, restoring it afterwards.
	 *
	 * @param supplier the supplier
	 * @param <T>      the type of the result
	 * @return the result
	 */
	private <T> T readOnProducts(@Nonnull Supplier<T> supplier) {
		final boolean before = maintenance().isReadVirtually(ENTITY_PRODUCT);
		readVirtually(true);
		try {
			return supplier.get();
		} finally {
			readVirtually(before);
		}
	}

	/**
	 * Applies a change to the live catalog of the case in its own transaction.
	 *
	 * @param change the change
	 */
	private void write(@Nonnull Consumer<EvitaSessionContract> change) {
		this.evita.updateCatalog(this.catalog, change);
	}

	/**
	 * Returns the cache of the case.
	 *
	 * @return the cache
	 */
	@Nonnull
	private Cache cache() {
		return new Cache(this.anteroom, this.eden);
	}

	/**
	 * Creates a cache with the minimal thresholds: every cacheable formula costing anything is an adept, and every
	 * adept computed once is promoted on the next evaluation.
	 *
	 * @return the cache
	 */
	@Nonnull
	private Cache newCache() {
		final CacheEden newEden = new CacheEden(10_000_000L, 1, -1L, this.scheduler);
		return new Cache(new CacheAnteroom(10_000, 1L, newEden, this.scheduler), newEden);
	}

	/**
	 * Closes a cache other than the one of the case.
	 *
	 * @param cache the cache
	 */
	private static void closeQuietly(@Nonnull Cache cache) {
		try {
			cache.anteroom().close();
		} catch (Exception e) {
			throw new AssertionError("Closing the cache failed.", e);
		}
	}

	/**
	 * Creates the catalog of the case with every collection maintained from the first write, fills it in warm-up,
	 * takes it live and archives the last owners.
	 *
	 * @param catalogName the catalog
	 */
	private void buildCatalog(@Nonnull String catalogName) {
		// TODO JNO - remove at the end of #1615
		this.evita.createCatalogWithVirtualReferencePartitions(catalogName, false);
		this.evita.defineCatalog(catalogName).updateViaNewSession(this.evita);
		final VirtualReferencePartitionMaintenance maintenance =
			((Catalog) this.evita.getCatalogInstance(catalogName).orElseThrow()).getVirtualReferencePartitionMaintenance();
		maintenance.setMaintained(ENTITY_PRODUCT, true);
		maintenance.setMaintained(ENTITY_CATEGORY, true);
		this.evita.updateCatalog(catalogName, VirtualPartitionFormulaCacheTest::fillCatalog);
		this.evita.updateCatalog(catalogName, EvitaSessionContract::goLiveAndClose);
		this.evita.updateCatalog(
			catalogName,
			session -> {
				for (int pk = FIRST_ARCHIVED_OWNER; pk <= OWNER_COUNT; pk++) {
					session.archiveEntity(ENTITY_PRODUCT, pk);
				}
			}
		);
	}

	/**
	 * Defines the schemas and writes the entities described in the class documentation.
	 *
	 * @param session the warm-up session
	 */
	private static void fillCatalog(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_CATEGORY)
			.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.filterableInScope(Scope.LIVE))
			.updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withLocale(LOCALE_EN, LOCALE_DE)
			.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.filterableInScope(BOTH_SCOPES))
			.withAttribute(ATTRIBUTE_RANK, Integer.class, whichIs -> whichIs.filterableInScope(BOTH_SCOPES))
			.withAttribute(
				ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().filterableInScope(BOTH_SCOPES).nullable()
			)
			.withReferenceToEntity(
				REFERENCE_RELATED, ENTITY_PRODUCT, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.indexedForFilteringInScope(BOTH_SCOPES)
					.withAttribute(ATTRIBUTE_RELATION_TYPE, String.class, thatIs -> thatIs.filterableInScope(BOTH_SCOPES))
			)
			.withReferenceToEntity(
				REFERENCE_ACCESSORIES, ENTITY_PRODUCT, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.indexedForFilteringInScope(BOTH_SCOPES)
					.withAttribute(ATTRIBUTE_RELATION_TYPE, String.class, thatIs -> thatIs.filterableInScope(BOTH_SCOPES))
			)
			.withReferenceToEntity(
				REFERENCE_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.indexedForFilteringInScope(Scope.LIVE)
					.withAttribute(ATTRIBUTE_PRIORITY, Integer.class, thatIs -> thatIs.filterableInScope(Scope.LIVE))
			)
			.updateVia(session);
		session.defineEntitySchema(ENTITY_CATEGORY)
			.withReflectedReferenceToEntity(
				REFERENCE_PRODUCTS, ENTITY_PRODUCT, REFERENCE_CATEGORIES,
				whichIs -> whichIs
					.withCardinality(Cardinality.ZERO_OR_MORE)
					.withAttributesInherited()
					.indexedForFilteringInScope(Scope.LIVE)
			)
			.updateVia(session);
		for (int pk = 1; pk <= CATEGORY_COUNT; pk++) {
			session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, pk).setAttribute(ATTRIBUTE_CODE, "C" + pk));
		}
		for (int pk = 1; pk <= PRODUCT_COUNT; pk++) {
			session.upsertEntity(newProduct(session, pk));
		}
	}

	/**
	 * Creates one product with its references.
	 *
	 * @param session    the session
	 * @param primaryKey the product primary key
	 * @return the entity builder
	 */
	@Nonnull
	private static EntityBuilder newProduct(@Nonnull EvitaSessionContract session, int primaryKey) {
		final EntityBuilder product = session.createNewEntity(ENTITY_PRODUCT, primaryKey)
			.setAttribute(ATTRIBUTE_CODE, "P" + primaryKey)
			.setAttribute(ATTRIBUTE_RANK, primaryKey * 10)
			.setAttribute(ATTRIBUTE_NAME, LOCALE_EN, "product " + primaryKey)
			.setReference(REFERENCE_CATEGORIES, 1, ref -> ref.setAttribute(ATTRIBUTE_PRIORITY, primaryKey % 5));
		if (primaryKey % 2 == 1) {
			product.setAttribute(ATTRIBUTE_NAME, LOCALE_DE, "Produkt " + primaryKey);
		}
		if (primaryKey <= 3) {
			product.setReference(REFERENCE_CATEGORIES, 2, ref -> ref.setAttribute(ATTRIBUTE_PRIORITY, 4));
		} else if (primaryKey <= 7) {
			// products 4 - 7 alone in categories 3 - 6, with priorities 1 - 4
			product.setReference(
				REFERENCE_CATEGORIES, primaryKey - 1, ref -> ref.setAttribute(ATTRIBUTE_PRIORITY, primaryKey - 3)
			);
		}
		if (primaryKey <= OWNER_COUNT) {
			final String singleType = primaryKey % 2 == 1 ? TYPE_ACCESSORY : TYPE_SPARE;
			product.setReference(
				REFERENCE_RELATED, 20 + primaryKey, ref -> ref.setAttribute(ATTRIBUTE_RELATION_TYPE, singleType)
			);
			if (primaryKey <= 6) {
				product.setReference(
					REFERENCE_RELATED, SHARED_ACCESSORY_TARGET,
					ref -> ref.setAttribute(ATTRIBUTE_RELATION_TYPE, TYPE_ACCESSORY)
				);
			} else if (primaryKey <= 12) {
				product.setReference(
					REFERENCE_RELATED, SHARED_SPARE_TARGET, ref -> ref.setAttribute(ATTRIBUTE_RELATION_TYPE, TYPE_SPARE)
				);
			}
			product.setReference(
				REFERENCE_ACCESSORIES, 40 + primaryKey, ref -> ref.setAttribute(ATTRIBUTE_RELATION_TYPE, TYPE_ACCESSORY)
			);
		}
		return product;
	}

}
