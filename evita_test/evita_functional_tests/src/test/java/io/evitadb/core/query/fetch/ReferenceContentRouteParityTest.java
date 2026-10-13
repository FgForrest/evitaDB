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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.exception.EntityLocaleMissingException;
import io.evitadb.api.exception.RollbackException;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.GroupHaving;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.ManagedReferencesBehaviour;
import io.evitadb.api.query.require.Page;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.query.require.Strip;
import io.evitadb.api.requestResponse.EvitaRequest.ReferenceContentKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaUniqueMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceAttributeSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.exception.AttributeNotFilterableException;
import io.evitadb.core.exception.ReferenceComponentNotIndexedException;
import io.evitadb.core.exception.ReferenceNotIndexedException;
import io.evitadb.core.query.response.ServerEntityDecorator;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.Functions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.parallel.Isolated;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Collator;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A temporary randomized sweep comparing the two routes of `referenceContent(..., filterBy(...))` while both exist
 * ({@link ReferenceContentRoute}): the body route must answer exactly what an independent oracle answers, and every
 * difference from the index route must fall into a class of behaviour that was decided to change.
 *
 * It is the only test that switches the route in-process, so it runs isolated from every other test.
 *
 * ## The oracle
 *
 * The oracle never touches the row filter of the body route. It is built together with each generated filter from the
 * meaning every constraint has on a single reference row: it reads the raw owners and targets
 * (`entityFetchAllContent()`, the delegate of the decorator), answers `entityHaving` and `groupHaving` by public
 * queries over the target and group collections in each scope the node is evaluated in, the existence of a target by a
 * public query over the examined scopes, compares a `BigDecimal` scaled half-up to the indexed decimal places, a string
 * decomposed (NFD) - ordered naturally, or by the collator of the leaf locale with ties broken naturally where the
 * attribute is localized - an array by any element and a range by the overlap or the envelope of its bounds, and reads
 * a localized attribute in each owner scope by that scope's declaration: in the locale of the leaf where the attribute
 * is bound to a locale, in any locale for `attributeEquals`, `attributeInSet` and `attributeIs` where it is unique
 * across locales. It predicts the refusals that do not depend on the data - a localized leaf without a locale,
 * a constraint without a meaning on a row, an inverted interval of a scalar attribute, an `inScope` of a scope the
 * request does not read - and the refusal must name the attribute, the constraint or the bounds.
 *
 * Some readings are mirrored from the decided design rather than derived from it (`inScope`, the owner locale, a
 * dropped group being no group, `attributeIs` in any locale, the locale refusal), and so are the scopes and the locale
 * the bodies of the referenced entities and groups are fetched in: the request derived for them reads the scopes of
 * the request of the owners - or the `scope(...)` inside `entityHaving` - and the locale of the filter, or of the
 * request of the owners when the filter names none.
 *
 * ## The shapes of a case
 *
 * Every case is one generated filter (none, a lone `scope(...)`, or a tree over the admitted constraints) fetched in
 * one shape: plain rows, rows with the bodies of their entities and groups, pages and strips with or without an
 * ordering (checked against the chunk of the same route's unchunked answer, bodies included), two named sets,
 * a nameless `referenceContent()`, a nested level, an enrichment and an archival of an owner (both read in LIVE
 * whatever the scope of the owner) and `getEntity` with `dataInLocales`. The query, `getEntity`, enrichment and
 * archival paths are compared with the index route as well; the other shapes are checked against the oracle alone.
 *
 * ## What fails the sweep
 *
 * Every answer of the body route must be the oracle's, and every chunk the chunk of the same route's whole answer.
 * Every difference of the index route must fall into a class that was decided to change - or into one of two known
 * defects of the index route outside this pull request, counted apart: #1699 (ranges of one partition sharing a
 * border) and the nested query of `groupHaving` planned with the ordering of the referenced entities. One known defect
 * of both routes is counted apart from the chunks as well: the pre-fetch slice sorts the raw rows, which still read a
 * dropped `rank`. Each run must also meet every situation of {@link #REQUIRED_HITS} - the premises that make it
 * discriminate - and the filter rejections are counted apart from those of the existence gate.
 *
 * ## The fixture
 *
 * `rpOwner` (12 owners, 9 to 12 archived; locales EN and DE) references `rpTarget` (targets 1 to 10, 9 and 10
 * archived, 11 and 12 missing; titles in EN, odd targets in DE too; `number` sortable) grouped by `rpGroup` (groups 1
 * to 4, 5 archived, 6 missing; a localized `label` in EN for odd groups and in DE for groups 1 and 2) through
 *
 * - `liveOnly` - indexed in LIVE: `int`, `dec` (`BigDecimal`, two places) and `str` filterable, `intRaw`, `decRaw`
 *   (beyond the int range too) and `arr` (an array, also empty) not, `loc` localized and filterable, `range` an
 *   `IntegerNumberRange`, `valid` a `DateTimeRange` (open-ended too), `rank` sortable,
 * - `both` - indexed in both scopes: `int`, `loc`, `mixed` (localized, unique across locales in LIVE and within
 *   a locale in ARCHIVED), `filtOne` (filterable in LIVE only) and `rank` (sortable in both scopes),
 * - `dups` - duplicates told apart by `role`, indexed in LIVE, with `int` and the sortable `rank`; the duplicates of
 *   an owner reference the first six targets only, so that they often share one,
 * - `nowhere` - indexed in no scope, with `int` and `str`,
 * - `reflected` - the reflection of `rpTarget.ownerLinks`, with the inherited `weight`.
 *
 * The strings hold non-ASCII letters, some in the composed (NFC) and some in the decomposed (NFD) form. The rows, their
 * groups and attribute values are generated from a fixed seed; some groups and attribute values are dropped afterwards.
 * The filters are generated from a fixed seed as well, so that the sweep answers the same in every build; the system
 * property {@link #SEED_PROPERTY} sets another one, {@link #ITERATIONS_PROPERTY} the number of cases.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Parity sweep of the index and the body route of the filter of referenceContent")
@Isolated
@Tag(ENGINE)
@Tag(QUERY)
@Tag(REFERENCE)
@TestInstance(Lifecycle.PER_CLASS)
class ReferenceContentRouteParityTest implements EvitaTestSupport {
	private static final String SEED_PROPERTY = "evita.test.routeParitySeed";
	private static final String ITERATIONS_PROPERTY = "evita.test.routeParityIterations";
	private static final long DEFAULT_SEED = 20261013L;
	private static final int DEFAULT_ITERATIONS = 500;
	private static final long FIXTURE_SEED = 1615L;
	private static final String CATALOG = "referenceContentRouteParity";
	private static final String OWNER = "rpOwner";
	private static final String TARGET = "rpTarget";
	private static final String GROUP = "rpGroup";
	private static final String REF_LIVE_ONLY = "liveOnly";
	private static final String REF_BOTH = "both";
	private static final String REF_DUPS = "dups";
	private static final String REF_NOWHERE = "nowhere";
	private static final String REF_REFLECTED = "reflected";
	private static final String REF_OWNER_LINKS = "ownerLinks";
	private static final String[] REFERENCES = {REF_LIVE_ONLY, REF_BOTH, REF_DUPS, REF_NOWHERE, REF_REFLECTED};
	private static final Set<String> GROUPED_REFERENCES = Set.of(REF_LIVE_ONLY, REF_BOTH, REF_DUPS);
	private static final String ATTR_CODE = "code";
	private static final String ATTR_NUMBER = "number";
	private static final String ATTR_TITLE = "title";
	private static final String ATTR_LABEL = "label";
	private static final String ATTR_NAME = "name";
	private static final String ATTR_INT = "int";
	private static final String ATTR_INT_RAW = "intRaw";
	private static final String ATTR_DEC = "dec";
	private static final String ATTR_DEC_RAW = "decRaw";
	private static final String ATTR_STR = "str";
	private static final String ATTR_LOC = "loc";
	private static final String ATTR_ARR = "arr";
	private static final String ATTR_RANGE = "range";
	private static final String ATTR_VALID = "valid";
	private static final String ATTR_MIXED = "mixed";
	private static final String ATTR_FILT_ONE = "filtOne";
	private static final String ATTR_ROLE = "role";
	private static final String ATTR_RANK = "rank";
	private static final String ATTR_WEIGHT = "weight";
	private static final Locale[] LOCALES = {Locale.ENGLISH, Locale.GERMAN};
	private static final Scope[] LIVE = {Scope.LIVE};
	private static final Scope[] ARCHIVED = {Scope.ARCHIVED};
	private static final Scope[] BOTH = {Scope.LIVE, Scope.ARCHIVED};
	private static final Scope[][] SCOPE_SETS = {LIVE, ARCHIVED, BOTH};
	private static final int OWNER_COUNT = 12;
	private static final int[] ARCHIVED_OWNERS = {9, 10, 11, 12};
	private static final int LAST_LIVE_OWNER = 8;
	private static final int[] ARCHIVED_TARGETS = {9, 10};
	private static final int TARGET_COUNT = 12;
	private static final int CREATED_TARGET_COUNT = 10;
	private static final int GROUP_COUNT = 6;
	private static final int CREATED_GROUP_COUNT = 5;
	private static final Integer[] INT_VALUES = {1, 2, 3};
	private static final BigDecimal[] DEC_VALUES = {
		new BigDecimal("1.00"), new BigDecimal("1.01"), new BigDecimal("2.50")
	};
	private static final BigDecimal HUGE = new BigDecimal("12345678901.23");
	private static final BigDecimal[] DEC_PROBES = {
		new BigDecimal("1.005"), new BigDecimal("1.01"), new BigDecimal("1.014"), new BigDecimal("2.5"),
		new BigDecimal("12345678901.22"), new BigDecimal("12345678901.23")
	};
	/**
	 * The values of the strings: `é` composed and decomposed, `ä` composed and `č` decomposed - one character to the
	 * collator, two code points to {@link String#compareTo} in the decomposed form.
	 */
	private static final String[] STR_VALUES = {"a", "ab", "b", "ba", "é", "é", "ä", "z"};
	private static final String[] LOC_VALUES = {"x", "xy", "y", "ä", "č", "d", "a"};
	private static final String[] ARR_ELEMENTS = {"p", "q", "r"};
	private static final String[] FILT_ONE_VALUES = {"f1", "f2"};
	private static final String[] ROLES = {"a", "b", "c"};
	private static final String[] CODES = {"a", "b", "c"};
	/**
	 * The values of `valid` - closed and open-ended, all far from the moment the sweep runs at, so that the moment of
	 * `attributeInRangeNow` does not matter within a range of years.
	 */
	private static final DateTimeRange[] VALID_VALUES = {
		DateTimeRange.between(moment(2000, 1), moment(2001, 1)),
		DateTimeRange.between(moment(2000, 6), moment(2100, 1)),
		DateTimeRange.since(moment(2000, 1)),
		DateTimeRange.until(moment(2100, 1)),
		DateTimeRange.between(moment(2100, 1), moment(2101, 1))
	};
	private static final OffsetDateTime[] VALID_PROBES = {
		moment(1999, 1), moment(2000, 1), moment(2000, 6), moment(2001, 1), moment(2050, 1), moment(2100, 1),
		moment(2100, 6), moment(2102, 1)
	};
	/**
	 * The features whose situations every run must have met - the premises that make the sweep discriminate.
	 */
	private static final String[] REQUIRED_HITS = {
		"dupSplitVerdicts", "archivedOwnerAttributeLeafOnMultiOwnerPage", "groupHavingReached", "pagedSorted",
		"pagedUnsorted", "strip", "chunkRowsSharingKey", "keyChunkMissesRowOfChunk", "clampedStripMissesRowOfChunk",
		"secondGroupOfDuplicates", "sortedSplitDuplicates", "bodyPresent", "bodyMissing", "groupBodyPresent",
		"ownerOutsideRequestScopes", "nestedRowsAdmitted", "nestedRowsRejected", "named", "nameless", "enrich",
		"archive", "noFilterAny", "noFilterExisting", "scopeOnly", "scopeInEntityHaving", "localeInNot",
		"localeInGroupHaving", "collation", "dateTimeRangeNow", "openEndedRange", "invertedBetweenRange", "refused:R6",
		"refused:R10", "refused:locale", "getEntityWithLocale"
	};

	private TestPaths paths;
	private Evita evita;
	/**
	 * The raw owners - the delegates of the decorators, carrying every row, every attribute of every locale.
	 */
	private Map<Integer, Entity> owners;
	/**
	 * The raw targets, carrying their `ownerLinks` rows - the owners of the nested level.
	 */
	private Map<Integer, Entity> targets;
	/**
	 * Every value of `mixed` the fixture holds, so that the generated filters hit some.
	 */
	private final List<String> mixedValues = new ArrayList<>(64);
	/**
	 * The targets whose rows of a range attribute ever indexed in LIVE carry two different values sharing a border,
	 * by the attribute - the shape of the known issue #1699 (see {@link #explain}). Every row of the fixture was
	 * written in LIVE, so the values of archived owners and the dropped values count as well: their removal strips the
	 * shared border from the surviving range.
	 */
	private final Map<String, Set<Integer>> sharedBorderTargets = CollectionUtils.createHashMap(2);

	/**
	 * Returns the first moment of the month, in UTC.
	 *
	 * @param year  the year
	 * @param month the month
	 * @return the moment
	 */
	@Nonnull
	private static OffsetDateTime moment(int year, int month) {
		return OffsetDateTime.of(year, month, 1, 0, 0, 0, 0, ZoneOffset.UTC);
	}

	/**
	 * Builds the fixture described on the class and reads the raw owners and targets for the oracle.
	 */
	@BeforeAll
	void setUp() {
		this.paths = createTestPaths("ReferenceContentRouteParityTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		this.evita.updateCatalog(CATALOG, this::createFixture);
		this.evita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
		this.evita.updateCatalog(CATALOG, ReferenceContentRouteParityTest::dropSomeData);
		this.owners = readRaw(OWNER, OWNER_COUNT);
		this.targets = readRaw(TARGET, CREATED_TARGET_COUNT);
		for (String attribute : new String[]{ATTR_RANGE, ATTR_VALID}) {
			final Map<Integer, Set<Serializable>> valuesByTarget = CollectionUtils.createHashMap(16);
			for (Entity owner : this.owners.values()) {
				for (ReferenceContract row : owner.getReferences(REF_LIVE_ONLY)) {
					final Optional<AttributeValue> value = row.getAttributeValue(attribute);
					if (value.isPresent() && value.get().value() != null) {
						valuesByTarget
							.computeIfAbsent(row.getReferencedPrimaryKey(), it -> CollectionUtils.createHashSet(4))
							.add(value.get().value());
					}
				}
			}
			final Set<Integer> targetsSharingBorder = CollectionUtils.createHashSet(16);
			valuesByTarget.forEach(
				(target, values) -> {
					if (shareBorder(values)) {
						targetsSharingBorder.add(target);
					}
				}
			);
			this.sharedBorderTargets.put(attribute, targetsSharingBorder);
		}
	}

	/**
	 * Reads the raw entities of the collection in both scopes.
	 *
	 * @param entityType the collection
	 * @param count      the number of entities, primary keys 1 to `count`
	 * @return the raw entities by primary key
	 */
	@Nonnull
	private Map<Integer, Entity> readRaw(@Nonnull String entityType, int count) {
		return this.evita.queryCatalog(
			CATALOG,
			session -> {
				final Map<Integer, Entity> result = new TreeMap<>();
				for (int pk = 1; pk <= count; pk++) {
					final SealedEntity entity = session.getEntity(entityType, pk, BOTH, entityFetchAllContent())
						.orElseThrow();
					result.put(pk, ((EntityDecorator) entity).getDelegate());
				}
				return result;
			}
		);
	}

	/**
	 * Tells whether two different ranges of the set share their start or their end - an open end counting as a border
	 * shared with every other open end of its side.
	 *
	 * @param ranges the distinct ranges
	 * @return TRUE when two of them share a border
	 */
	private static boolean shareBorder(@Nonnull Set<Serializable> ranges) {
		for (Serializable first : ranges) {
			for (Serializable second : ranges) {
				if (!first.equals(second) && (
					Objects.equals(lowerBound(first), lowerBound(second)) ||
						Objects.equals(upperBound(first), upperBound(second))
				)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Returns the lower bound of a range value, NULL for an open one.
	 *
	 * @param range the range
	 * @return the bound
	 */
	@Nullable
	private static Object lowerBound(@Nonnull Serializable range) {
		return range instanceof DateTimeRange dateTimeRange ?
			dateTimeRange.getPreciseFrom() : ((IntegerNumberRange) range).getPreciseFrom();
	}

	/**
	 * Returns the upper bound of a range value, NULL for an open one.
	 *
	 * @param range the range
	 * @return the bound
	 */
	@Nullable
	private static Object upperBound(@Nonnull Serializable range) {
		return range instanceof DateTimeRange dateTimeRange ?
			dateTimeRange.getPreciseTo() : ((IntegerNumberRange) range).getPreciseTo();
	}

	/**
	 * Closes the engine and removes its data.
	 */
	@AfterAll
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/**
	 * Builds the schemas and the entities of the fixture in warm-up.
	 *
	 * @param session the session to write in
	 */
	private void createFixture(@Nonnull EvitaSessionContract session) {
		final Random random = new Random(FIXTURE_SEED);
		session.defineEntitySchema(GROUP)
			.withoutGeneratedPrimaryKey()
			.withLocale(LOCALES)
			.withAttribute(ATTR_CODE, String.class, thatIs -> thatIs.filterableInScope(BOTH))
			.withAttribute(ATTR_LABEL, String.class, thatIs -> thatIs.localized().nullable())
			.updateVia(session);
		for (int pk = 1; pk <= CREATED_GROUP_COUNT; pk++) {
			final EntityBuilder group = session.createNewEntity(GROUP, pk).setAttribute(ATTR_CODE, "g" + pk);
			if (pk % 2 == 1) {
				group.setAttribute(ATTR_LABEL, Locale.ENGLISH, "l" + pk);
			}
			if (pk <= 2) {
				group.setAttribute(ATTR_LABEL, Locale.GERMAN, "k" + pk);
			}
			session.upsertEntity(group);
		}
		session.archiveEntity(GROUP, CREATED_GROUP_COUNT);

		session.defineEntitySchema(TARGET)
			.withoutGeneratedPrimaryKey()
			.withLocale(LOCALES)
			.withAttribute(ATTR_CODE, String.class, thatIs -> thatIs.filterableInScope(BOTH))
			.withAttribute(
				ATTR_NUMBER, Integer.class, thatIs -> thatIs.filterableInScope(BOTH).sortableInScope(BOTH)
			)
			.withAttribute(ATTR_TITLE, String.class, thatIs -> thatIs.localized().nullable())
			.updateVia(session);
		for (int pk = 1; pk <= CREATED_TARGET_COUNT; pk++) {
			final EntityBuilder target = session.createNewEntity(TARGET, pk)
				.setAttribute(ATTR_CODE, CODES[pk % CODES.length])
				.setAttribute(ATTR_NUMBER, pk % 5)
				.setAttribute(ATTR_TITLE, Locale.ENGLISH, "t" + pk);
			if (pk % 2 == 1) {
				target.setAttribute(ATTR_TITLE, Locale.GERMAN, "d" + pk);
			}
			session.upsertEntity(target);
		}
		for (int pk : ARCHIVED_TARGETS) {
			session.archiveEntity(TARGET, pk);
		}

		session.defineEntitySchema(OWNER)
			.withoutGeneratedPrimaryKey()
			.withLocale(LOCALES)
			.withAttribute(ATTR_NAME, String.class, thatIs -> thatIs.localized().nullable())
			.withReferenceToEntity(
				REF_LIVE_ONLY, TARGET, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.withGroupTypeRelatedToEntity(GROUP)
					.indexedForFilteringAndPartitioningInScope(Scope.LIVE)
					.indexedWithComponents(
						ReferenceIndexedComponents.REFERENCED_ENTITY, ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
					.withAttribute(ATTR_INT, Integer.class, thatIs -> thatIs.filterableInScope(Scope.LIVE).nullable())
					.withAttribute(ATTR_INT_RAW, Integer.class, AttributeSchemaEditor::nullable)
					.withAttribute(
						ATTR_DEC, BigDecimal.class,
						thatIs -> thatIs.filterableInScope(Scope.LIVE).indexDecimalPlaces(2).nullable()
					)
					.withAttribute(ATTR_DEC_RAW, BigDecimal.class, thatIs -> thatIs.indexDecimalPlaces(2).nullable())
					.withAttribute(ATTR_STR, String.class, thatIs -> thatIs.filterableInScope(Scope.LIVE).nullable())
					.withAttribute(
						ATTR_LOC, String.class, thatIs -> thatIs.localized().filterableInScope(Scope.LIVE).nullable()
					)
					.withAttribute(ATTR_ARR, String[].class, AttributeSchemaEditor::nullable)
					.withAttribute(
						ATTR_RANGE, IntegerNumberRange.class, thatIs -> thatIs.filterableInScope(Scope.LIVE).nullable()
					)
					.withAttribute(
						ATTR_VALID, DateTimeRange.class, thatIs -> thatIs.filterableInScope(Scope.LIVE).nullable()
					)
					.withAttribute(ATTR_RANK, Integer.class, thatIs -> thatIs.sortableInScope(Scope.LIVE).nullable())
			)
			.withReferenceToEntity(
				REF_BOTH, TARGET, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.withGroupTypeRelatedToEntity(GROUP)
					.indexedForFilteringAndPartitioningInScope(BOTH)
					.indexedWithComponents(
						ReferenceIndexedComponents.REFERENCED_ENTITY, ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
					.withAttribute(ATTR_INT, Integer.class, thatIs -> thatIs.filterableInScope(BOTH).nullable())
					.withAttribute(
						ATTR_LOC, String.class, thatIs -> thatIs.localized().filterableInScope(BOTH).nullable()
					)
					.withAttribute(
						ATTR_MIXED, String.class, thatIs -> thatIs.localized().uniqueInScope(BOTH).nullable()
					)
					.withAttribute(
						ATTR_FILT_ONE, String.class, thatIs -> thatIs.filterableInScope(Scope.LIVE).nullable()
					)
					.withAttribute(ATTR_RANK, Integer.class, thatIs -> thatIs.sortableInScope(BOTH).nullable())
			)
			.withReferenceToEntity(
				REF_DUPS, TARGET, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				whichIs -> whichIs
					.withGroupTypeRelatedToEntity(GROUP)
					.indexedForFilteringAndPartitioningInScope(Scope.LIVE)
					.indexedWithComponents(
						ReferenceIndexedComponents.REFERENCED_ENTITY, ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
					.withAttribute(
						ATTR_ROLE, String.class, thatIs -> thatIs.filterableInScope(Scope.LIVE).representative()
					)
					.withAttribute(ATTR_INT, Integer.class, thatIs -> thatIs.filterableInScope(Scope.LIVE).nullable())
					.withAttribute(ATTR_RANK, Integer.class, thatIs -> thatIs.sortableInScope(Scope.LIVE).nullable())
			)
			.withReferenceToEntity(
				REF_NOWHERE, TARGET, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.withAttribute(ATTR_INT, Integer.class, AttributeSchemaEditor::nullable)
					.withAttribute(ATTR_STR, String.class, AttributeSchemaEditor::nullable)
			)
			.updateVia(session);
		session.updateEntitySchema(
			new ModifyEntitySchemaMutation(
				OWNER,
				new ModifyReferenceAttributeSchemaMutation(
					REF_BOTH,
					new SetAttributeSchemaUniqueMutation(
						ATTR_MIXED,
						new ScopedAttributeUniquenessType[]{
							new ScopedAttributeUniquenessType(
								Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION
							),
							new ScopedAttributeUniquenessType(
								Scope.ARCHIVED, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE
							)
						}
					)
				)
			)
		);
		session.defineEntitySchema(TARGET)
			.withReferenceToEntity(
				REF_OWNER_LINKS, OWNER, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringInScope(Scope.LIVE)
					.withAttribute(
						ATTR_WEIGHT, Integer.class, thatIs -> thatIs.filterableInScope(Scope.LIVE).nullable()
					)
			)
			.updateVia(session);
		session.defineEntitySchema(OWNER)
			.withReflectedReferenceToEntity(
				REF_REFLECTED, TARGET, REF_OWNER_LINKS,
				whichIs -> whichIs.withAttributesInherited().indexedForFilteringInScope(Scope.LIVE)
			)
			.updateVia(session);

		for (int pk = 1; pk <= OWNER_COUNT; pk++) {
			final EntityBuilder owner = session.createNewEntity(OWNER, pk)
				.setAttribute(ATTR_NAME, Locale.ENGLISH, "o" + pk);
			final boolean german = pk % 3 != 0;
			if (german) {
				owner.setAttribute(ATTR_NAME, Locale.GERMAN, "e" + pk);
			}
			final Locale[] ownerLocales = german ? LOCALES : new Locale[]{Locale.ENGLISH};
			for (int target : distinctTargets(random, 5)) {
				owner.setReference(
					REF_LIVE_ONLY, target,
					whichIs -> {
						setGroup(random, whichIs);
						setMaybe(random, () -> whichIs.setAttribute(ATTR_INT, pick(random, INT_VALUES)));
						setMaybe(random, () -> whichIs.setAttribute(ATTR_INT_RAW, pick(random, INT_VALUES)));
						setMaybe(random, () -> whichIs.setAttribute(ATTR_DEC, pick(random, DEC_VALUES)));
						setMaybe(
							random,
							() -> whichIs.setAttribute(
								ATTR_DEC_RAW, random.nextInt(4) == 0 ? HUGE : pick(random, DEC_VALUES)
							)
						);
						setMaybe(random, () -> whichIs.setAttribute(ATTR_STR, pick(random, STR_VALUES)));
						for (Locale locale : ownerLocales) {
							setMaybe(random, () -> whichIs.setAttribute(ATTR_LOC, locale, pick(random, LOC_VALUES)));
						}
						setMaybe(random, () -> whichIs.setAttribute(ATTR_ARR, randomArray(random)));
						setMaybe(
							random,
							() -> {
								final int from = random.nextInt(5);
								whichIs.setAttribute(
									ATTR_RANGE, IntegerNumberRange.between(from, from + random.nextInt(3))
								);
							}
						);
						setMaybe(random, () -> whichIs.setAttribute(ATTR_VALID, pick(random, VALID_VALUES)));
						setMaybe(random, () -> whichIs.setAttribute(ATTR_RANK, pick(random, INT_VALUES)));
					}
				);
			}
			final int ownerPk = pk;
			for (int target : distinctTargets(random, 4)) {
				owner.setReference(
					REF_BOTH, target,
					whichIs -> {
						setGroup(random, whichIs);
						setMaybe(random, () -> whichIs.setAttribute(ATTR_INT, pick(random, INT_VALUES)));
						for (Locale locale : ownerLocales) {
							setMaybe(random, () -> whichIs.setAttribute(ATTR_LOC, locale, pick(random, LOC_VALUES)));
							setMaybe(
								random,
								() -> {
									final String value = "m" + ownerPk + "-" + target + "-" + locale.getLanguage();
									this.mixedValues.add(value);
									whichIs.setAttribute(ATTR_MIXED, locale, value);
								}
							);
						}
						setMaybe(random, () -> whichIs.setAttribute(ATTR_FILT_ONE, pick(random, FILT_ONE_VALUES)));
						setMaybe(random, () -> whichIs.setAttribute(ATTR_RANK, pick(random, INT_VALUES)));
					}
				);
			}
			final Set<String> duplicates = CollectionUtils.createHashSet(8);
			for (int i = 1 + random.nextInt(6); i > 0; i--) {
				// half of the targets only, so that the duplicates of one owner often share a target
				final int target = 1 + random.nextInt(TARGET_COUNT / 2);
				final String role = pick(random, ROLES);
				if (duplicates.add(target + "/" + role)) {
					owner.setOrUpdateReference(
						REF_DUPS, target, Functions.alwaysFalse(),
						whichIs -> {
							setGroup(random, whichIs);
							whichIs.setAttribute(ATTR_ROLE, role);
							setMaybe(random, () -> whichIs.setAttribute(ATTR_INT, pick(random, INT_VALUES)));
							setMaybe(random, () -> whichIs.setAttribute(ATTR_RANK, pick(random, INT_VALUES)));
						}
					);
				}
			}
			for (int target : distinctTargets(random, 3)) {
				owner.setReference(
					REF_NOWHERE, target,
					whichIs -> {
						setMaybe(random, () -> whichIs.setAttribute(ATTR_INT, pick(random, INT_VALUES)));
						setMaybe(random, () -> whichIs.setAttribute(ATTR_STR, pick(random, STR_VALUES)));
					}
				);
			}
			session.upsertEntity(owner);
		}
		for (int pk = 1; pk <= CREATED_TARGET_COUNT; pk++) {
			final EntityBuilder target = session.getEntity(TARGET, pk, BOTH, entityFetchAllContent())
				.orElseThrow()
				.openForWrite();
			for (int i = random.nextInt(4); i > 0; i--) {
				final int owner = 1 + random.nextInt(OWNER_COUNT);
				target.setReference(
					REF_OWNER_LINKS, owner,
					whichIs -> setMaybe(random, () -> whichIs.setAttribute(ATTR_WEIGHT, pick(random, INT_VALUES)))
				);
			}
			session.upsertEntity(target);
		}
		for (int pk : ARCHIVED_OWNERS) {
			session.archiveEntity(OWNER, pk);
		}
	}

	/**
	 * Rewrites the first `liveOnly` row holding a group and the first `both` row holding an `int` of every third owner
	 * as rows without any value or group, and removes the first `nowhere` row of every fourth owner - in a
	 * transaction of the live catalog. `setReference(name, pk, consumer)` replaces the whole row by design (the row is
	 * built from scratch, and `updateReference` is the API that keeps it), so the group and every attribute value of
	 * the rewritten rows are dropped, not only the group and the `int` the consumer removes.
	 *
	 * @param session the session to write in
	 */
	private static void dropSomeData(@Nonnull EvitaSessionContract session) {
		for (int pk = 1; pk <= OWNER_COUNT; pk += 3) {
			final SealedEntity owner = session.getEntity(OWNER, pk, BOTH, entityFetchAllContent()).orElseThrow();
			final EntityBuilder builder = owner.openForWrite();
			owner.getReferences(REF_LIVE_ONLY).stream()
				.filter(row -> row.getGroup().isPresent())
				.findFirst()
				.ifPresent(
					row -> builder.setReference(
						REF_LIVE_ONLY, row.getReferencedPrimaryKey(), ReferenceBuilder::removeGroup
					)
				);
			owner.getReferences(REF_BOTH).stream()
				.filter(row -> row.getAttribute(ATTR_INT) != null)
				.findFirst()
				.ifPresent(
					row -> builder.setReference(
						REF_BOTH, row.getReferencedPrimaryKey(), whichIs -> whichIs.removeAttribute(ATTR_INT)
					)
				);
			if (pk % 4 == 1) {
				owner.getReferences(REF_NOWHERE).stream().findFirst().ifPresent(
					row -> builder.removeReference(REF_NOWHERE, row.getReferencedPrimaryKey())
				);
			}
			session.upsertEntity(builder);
		}
	}

	/**
	 * Returns up to `max - 1` distinct random targets, existing, archived or missing.
	 *
	 * @param random the source of randomness
	 * @param max    the exclusive upper bound of the count
	 * @return the targets
	 */
	@Nonnull
	private static Set<Integer> distinctTargets(@Nonnull Random random, int max) {
		final Set<Integer> targets = new TreeSet<>();
		for (int i = random.nextInt(max); i > 0; i--) {
			targets.add(1 + random.nextInt(TARGET_COUNT));
		}
		return targets;
	}

	/**
	 * Sets a random group - existing, archived or missing - or none.
	 *
	 * @param random  the source of randomness
	 * @param builder the reference
	 */
	private static void setGroup(@Nonnull Random random, @Nonnull ReferenceBuilder builder) {
		if (random.nextInt(5) > 0) {
			builder.setGroup(1 + random.nextInt(GROUP_COUNT));
		}
	}

	/**
	 * Runs the setter with three chances in four.
	 *
	 * @param random the source of randomness
	 * @param setter the setter
	 */
	private static void setMaybe(@Nonnull Random random, @Nonnull Runnable setter) {
		if (random.nextInt(4) > 0) {
			setter.run();
		}
	}

	/**
	 * Picks a random element.
	 *
	 * @param random the source of randomness
	 * @param values the elements
	 * @param <T>    the type of the elements
	 * @return the element
	 */
	@Nonnull
	private static <T> T pick(@Nonnull Random random, @Nonnull T[] values) {
		return values[random.nextInt(values.length)];
	}

	/**
	 * Returns a random subset of {@link #ARR_ELEMENTS}, possibly empty.
	 *
	 * @param random the source of randomness
	 * @return the array
	 */
	@Nonnull
	private static String[] randomArray(@Nonnull Random random) {
		final List<String> elements = new ArrayList<>(3);
		for (String element : ARR_ELEMENTS) {
			if (random.nextBoolean()) {
				elements.add(element);
			}
		}
		return elements.toArray(String[]::new);
	}

	@Test
	@DisplayName("the body route answers as the oracle does and differs from the index route in decided classes only")
	void shouldAnswerAsTheOracleAndDifferFromTheIndexRouteInDecidedClassesOnly() {
		final long seed = Long.getLong(SEED_PROPERTY, DEFAULT_SEED);
		final int iterations = Integer.getInteger(ITERATIONS_PROPERTY, DEFAULT_ITERATIONS);
		System.out.println("ReferenceContentRouteParityTest: seed " + seed + ", " + iterations + " iterations");
		final Random random = new Random(seed);
		final Statistics statistics = new Statistics();
		final List<String> failures = new ArrayList<>(16);
		for (int i = 0; i < iterations && failures.size() < 10; i++) {
			final Case theCase = new CaseGenerator(random, this.mixedValues).generate(i);
			try {
				runCase(theCase, random, statistics, failures);
			} catch (RuntimeException | AssertionError ex) {
				failures.add("seed " + seed + ", " + theCase + ": " + ex);
			}
		}
		System.out.println("ReferenceContentRouteParityTest: " + statistics);
		if (!failures.isEmpty()) {
			fail("seed " + seed + ", " + failures.size() + " failures:\n" + String.join("\n", failures));
		}
		assertTrue(statistics.admittedRows > 0, "The premise: the body route admits some rows");
		assertTrue(statistics.filterRejectedRows > 0, "The premise: the filter rejects some rows");
		assertTrue(statistics.gateRejectedRows > 0, "The premise: the existence gate rejects some rows");
		assertTrue(statistics.differences > 0, "The premise: the routes differ somewhere");
		final List<String> missing = new ArrayList<>(4);
		for (String feature : REQUIRED_HITS) {
			if (statistics.hits.getOrDefault(feature, 0) == 0) {
				missing.add(feature);
			}
		}
		assertTrue(missing.isEmpty(), () -> "The premise: the sweep met every situation, but not " + missing);
	}

	/**
	 * Runs one case in its shape and checks the outcomes.
	 *
	 * @param theCase    the case
	 * @param random     the source of randomness
	 * @param statistics the statistics to update
	 * @param failures   the failures to add to
	 */
	private void runCase(
		@Nonnull Case theCase,
		@Nonnull Random random,
		@Nonnull Statistics statistics,
		@Nonnull List<String> failures
	) {
		switch (theCase.shape()) {
			case PLAIN, BODIES -> {
				runQuery(theCase, statistics, failures);
				runGetEntity(theCase, 1 + random.nextInt(OWNER_COUNT), statistics, failures);
			}
			case CHUNKED -> runChunked(theCase, statistics, failures);
			case NAMED -> runNamed(theCase, statistics, failures);
			case NAMELESS -> runNameless(theCase, statistics, failures);
			case NESTED -> runQuery(theCase, statistics, failures);
			case ENRICH -> runEnrich(theCase, 1 + random.nextInt(OWNER_COUNT), statistics, failures);
			case ARCHIVE -> runArchive(theCase, 1 + random.nextInt(LAST_LIVE_OWNER), statistics, failures);
		}
	}

	/**
	 * Fetches every owner in the scopes of the case through a query, on both routes, and checks the outcomes.
	 *
	 * @param theCase    the case
	 * @param statistics the statistics to update
	 * @param failures   the failures to add to
	 */
	private void runQuery(@Nonnull Case theCase, @Nonnull Statistics statistics, @Nonnull List<String> failures) {
		final Path path = Path.query(theCase);
		final FetchSpec spec = theCase.fetchSpec();
		final Outcome body = onRoute(ReferenceContentRoute.BODY, () -> fetchByQuery(theCase, spec.content(null)));
		final Outcome index = onRoute(ReferenceContentRoute.INDEX, () -> fetchByQuery(theCase, spec.content(null)));
		check(
			theCase, path, spec, body, index,
			theCase.withoutNotApplicable() == null ?
				null :
				() -> onRoute(
					ReferenceContentRoute.INDEX,
					() -> fetchByQuery(theCase, spec.withFilterBy(theCase.withoutNotApplicable()).content(null))
				),
			statistics, failures
		);
	}

	/**
	 * Fetches one owner through `getEntity` in both scopes - with `dataInLocales` when the case asks for it - on both
	 * routes, and checks the outcomes.
	 *
	 * @param theCase    the case
	 * @param owner      the primary key of the owner
	 * @param statistics the statistics to update
	 * @param failures   the failures to add to
	 */
	private void runGetEntity(
		@Nonnull Case theCase,
		int owner,
		@Nonnull Statistics statistics,
		@Nonnull List<String> failures
	) {
		final Path path = Path.getEntity(owner);
		final FetchSpec spec = theCase.fetchSpec();
		final Locale dataLocale = theCase.getEntityLocale();
		final Function<FetchSpec, EntityContentRequire[]> requirements = it -> dataLocale == null ?
			new EntityContentRequire[]{it.content(null)} :
			new EntityContentRequire[]{it.content(null), dataInLocales(dataLocale)};
		final Outcome body = onRoute(
			ReferenceContentRoute.BODY, () -> fetchByGetEntity(owner, requirements.apply(spec), spec.describer(null))
		);
		final Outcome index = onRoute(
			ReferenceContentRoute.INDEX, () -> fetchByGetEntity(owner, requirements.apply(spec), spec.describer(null))
		);
		if (dataLocale != null) {
			theCase.hit(statistics, "getEntityWithLocale");
		}
		check(
			theCase, path, spec, body, index,
			theCase.withoutNotApplicable() == null ?
				null :
				() -> onRoute(
					ReferenceContentRoute.INDEX,
					() -> {
						final FetchSpec withoutNotApplicable = spec.withFilterBy(theCase.withoutNotApplicable());
						return fetchByGetEntity(
							owner, requirements.apply(withoutNotApplicable), withoutNotApplicable.describer(null)
						);
					}
				),
			statistics, failures
		);
	}

	/**
	 * Enriches one owner, fetched in both scopes without references, by the references the filter keeps - on both
	 * routes. The enrichment reads LIVE whatever the scope of the owner, so an archived owner lives outside the scopes
	 * of its request.
	 *
	 * @param theCase    the case
	 * @param owner      the primary key of the owner
	 * @param statistics the statistics to update
	 * @param failures   the failures to add to
	 */
	private void runEnrich(
		@Nonnull Case theCase,
		int owner,
		@Nonnull Statistics statistics,
		@Nonnull List<String> failures
	) {
		final Function<FetchSpec, Supplier<Outcome>> enrich = spec -> () -> this.evita.queryCatalog(
			CATALOG,
			session -> {
				final SealedEntity entity = session.getEntity(OWNER, owner, BOTH, attributeContentAll())
					.orElseThrow();
				final SealedEntity enriched = session.enrichEntity(entity, spec.content(null));
				return Outcome.of(Map.of(owner, spec.describer(null).apply(enriched)));
			}
		);
		theCase.hit(statistics, "enrich");
		checkOnBothRoutes(theCase, Path.enrich(owner), enrich, statistics, failures);
	}

	/**
	 * Archives one live owner with the references the filter keeps, in a transaction rolled back afterwards - on
	 * both routes. The request of `archiveEntity` reads LIVE, while the owner it returns lives in ARCHIVED.
	 *
	 * @param theCase    the case
	 * @param owner      the primary key of the live owner
	 * @param statistics the statistics to update
	 * @param failures   the failures to add to
	 */
	private void runArchive(
		@Nonnull Case theCase,
		int owner,
		@Nonnull Statistics statistics,
		@Nonnull List<String> failures
	) {
		// the archival changes the rows the owner holds - a reflected reference does not survive a change of scope -
		// so the oracle reads the raw owner as the archival leaves it
		final List<Entity> archivedOwner = new ArrayList<>(1);
		final Function<FetchSpec, Supplier<Outcome>> archive = spec -> () -> {
			final List<Outcome> outcome = new ArrayList<>(1);
			try {
				this.evita.updateCatalog(
					CATALOG,
					session -> {
						final SealedEntity archived = session.archiveEntity(OWNER, owner, spec.content(null))
							.orElseThrow();
						outcome.add(Outcome.of(Map.of(owner, spec.describer(null).apply(archived))));
						if (archivedOwner.isEmpty()) {
							archivedOwner.add(
								((EntityDecorator) session.getEntity(OWNER, owner, BOTH, entityFetchAllContent())
									.orElseThrow()).getDelegate()
							);
						}
						session.setRollbackOnly();
					}
				);
			} catch (RollbackException ignored) {
				// the transaction is rolled back on purpose, the outcome was captured inside it
			}
			return outcome.get(0);
		};
		theCase.hit(statistics, "archive");
		checkOnBothRoutes(
			theCase, Path.archive(owner, () -> archivedOwner.isEmpty() ? null : archivedOwner.get(0)), archive,
			statistics, failures
		);
	}

	/**
	 * Fetches the case on both routes and checks the outcomes - running the index route once more without the
	 * container the query model drops as not applicable, when the filter holds one.
	 *
	 * @param theCase    the case
	 * @param path       the path the owners are fetched by
	 * @param fetch      the fetch of what the case asks for
	 * @param statistics the statistics to update
	 * @param failures   the failures to add to
	 */
	private void checkOnBothRoutes(
		@Nonnull Case theCase,
		@Nonnull Path path,
		@Nonnull Function<FetchSpec, Supplier<Outcome>> fetch,
		@Nonnull Statistics statistics,
		@Nonnull List<String> failures
	) {
		final FetchSpec spec = theCase.fetchSpec();
		final FilterBy withoutNotApplicable = theCase.withoutNotApplicable();
		check(
			theCase, path, spec, onRoute(ReferenceContentRoute.BODY, fetch.apply(spec)),
			onRoute(ReferenceContentRoute.INDEX, fetch.apply(spec)),
			withoutNotApplicable == null ?
				null :
				() -> onRoute(ReferenceContentRoute.INDEX, fetch.apply(spec.withFilterBy(withoutNotApplicable))),
			statistics, failures
		);
	}

	/**
	 * Fetches every owner with the bodies of the referenced entities, ordered or not, once in one chunk and once whole
	 * - on the body route - and checks that the chunk holds the rows of the whole answer at the positions of the
	 * chunk, with the same bodies. The whole answer is checked against the oracle and the index route as a query is.
	 *
	 * @param theCase    the case
	 * @param statistics the statistics to update
	 * @param failures   the failures to add to
	 */
	private void runChunked(@Nonnull Case theCase, @Nonnull Statistics statistics, @Nonnull List<String> failures) {
		final Path path = Path.query(theCase);
		final FetchSpec spec = theCase.fetchSpec();
		final RequireConstraint chunk = Objects.requireNonNull(theCase.chunk());
		final Outcome whole = onRoute(ReferenceContentRoute.BODY, () -> fetchByQuery(theCase, spec.content(null)));
		final Outcome index = onRoute(ReferenceContentRoute.INDEX, () -> fetchByQuery(theCase, spec.content(null)));
		final FilterBy withoutNotApplicable = theCase.withoutNotApplicable();
		final Supplier<Outcome> indexWithoutNotApplicable = withoutNotApplicable == null ?
			null :
			() -> onRoute(
				ReferenceContentRoute.INDEX,
				() -> fetchByQuery(theCase, spec.withFilterBy(withoutNotApplicable).content(null))
			);
		if (!check(theCase, path, spec, whole, index, indexWithoutNotApplicable, statistics, failures)) {
			return;
		}
		final Outcome chunked = onRoute(
			ReferenceContentRoute.BODY, () -> fetchByQuery(theCase, spec.content(chunk))
		);
		theCase.hit(statistics, theCase.orderBy() == null ? "pagedUnsorted" : "pagedSorted");
		if (chunk instanceof Strip) {
			theCase.hit(statistics, "strip");
		}
		if (whole.rows() == null) {
			if (chunked.rows() != null || !whole.sameRefusal(chunked)) {
				failures.add(theCase + " / chunk " + chunk + ": " + chunked + ", the whole answer " + whole);
			}
			return;
		}
		if (chunked.rows() == null) {
			failures.add(theCase + " / chunk " + chunk + ": " + chunked + ", the whole answer " + whole);
			return;
		}
		// the bodies are fetched once for every owner, so a key slice misses a body only when no owner's slice holds it
		final Set<String> keysOfKeyChunks = CollectionUtils.createHashSet(16);
		final Set<String> keysOfClampedStrips = CollectionUtils.createHashSet(16);
		for (List<String> wholeRows : whole.rows().values()) {
			keysOfKeyChunks.addAll(chunk(distinctKeys(wholeRows), chunk));
			if (chunk instanceof Strip strip && !wholeRows.isEmpty()) {
				// a strip starting past the end clamped to the last row instead of starting at the first one
				final int from = Math.min(strip.getOffset(), wholeRows.size() - 1);
				keysOfClampedStrips.addAll(
					keys(wholeRows.subList(from, Math.min(from + strip.getLimit(), wholeRows.size())))
				);
			}
		}
		for (Entry<Integer, List<String>> entry : whole.rows().entrySet()) {
			final List<String> wholeRows = entry.getValue();
			final List<String> expected = chunk(wholeRows, chunk);
			if (!expected.equals(chunked.rows().get(entry.getKey())) && holdsDroppedRank(theCase, entry.getKey())) {
				// a known defect outside this pull request, on both routes: the slice sorts the raw rows, whose dropped
				// `rank` still reads as a value, while the decorator sorts rows that hide it - so the slice fetches the
				// bodies of other rows than the page keeps
				statistics.knownDefects.merge("pre-sort reads a dropped sort value", 1, Integer::sum);
			} else if (!expected.equals(chunked.rows().get(entry.getKey()))) {
				final Outcome indexChunked = onRoute(
					ReferenceContentRoute.INDEX, () -> fetchByQuery(theCase, spec.content(chunk))
				);
				failures.add(
					theCase + " / chunk " + chunk + " of owner " + entry.getKey() + ": the body route answered " +
						chunked.rows().get(entry.getKey()) + ", the chunk of its whole answer " + wholeRows +
						" is " + expected + " (the index route answered " +
						(indexChunked.rows() == null ? indexChunked : indexChunked.rows().get(entry.getKey())) +
						"); rows " + rawRows(theCase.reference(), Set.of(entry.getKey()))
				);
			}
			if (expected.size() < wholeRows.size() && repeatsKey(wholeRows)) {
				theCase.hit(statistics, "chunkRowsSharingKey");
				if (theCase.orderBy() == null && missesFetchedBody(expected, keysOfKeyChunks)) {
					// the chunks of the distinct keys would miss the body of an entity a row of the chunk carries
					theCase.hit(statistics, "keyChunkMissesRowOfChunk");
				}
			}
			if (chunk instanceof Strip strip && strip.getOffset() >= wholeRows.size() && !wholeRows.isEmpty()) {
				theCase.hit(statistics, "stripBeyondEnd");
				if (missesFetchedBody(expected, keysOfClampedStrips)) {
					// the strips clamped to the end would miss the body of an entity a row of the chunk carries
					theCase.hit(statistics, "clampedStripMissesRowOfChunk");
				}
			}
		}
	}

	/**
	 * Tells whether a row of the chunk carries the body of an entity whose key the chunks of keys miss.
	 *
	 * @param rows the rows of the chunk
	 * @param keys the keys the chunks of keys hold
	 * @return TRUE when one does
	 */
	private static boolean missesFetchedBody(@Nonnull List<String> rows, @Nonnull Set<String> keys) {
		for (String row : rows) {
			if (row.contains(" b1") && !keys.contains(row.split("[/ ]")[0])) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Tells whether the case orders by `rank` and the owner holds an existing row whose `rank` was dropped.
	 *
	 * @param theCase the case
	 * @param ownerPk the owner
	 * @return TRUE when it does
	 */
	private boolean holdsDroppedRank(@Nonnull Case theCase, int ownerPk) {
		if (theCase.orderBy() == null || !theCase.orderBy().toString().contains("'" + ATTR_RANK + "'")) {
			return false;
		}
		for (ReferenceContract row : this.owners.get(ownerPk).getReferences(theCase.reference())) {
			final Optional<AttributeValue> rank = row.exists() ? row.getAttributeValue(ATTR_RANK) : Optional.empty();
			if (rank.isPresent() && !rank.get().exists()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns the referenced keys of the described rows.
	 *
	 * @param rows the descriptions of the rows
	 * @return the keys
	 */
	@Nonnull
	private static Set<String> keys(@Nonnull List<String> rows) {
		final Set<String> keys = CollectionUtils.createHashSet(rows.size());
		for (String row : rows) {
			keys.add(row.split("[/ ]")[0]);
		}
		return keys;
	}

	/**
	 * Returns the distinct referenced keys of the described rows, in the order they first appear.
	 *
	 * @param rows the descriptions of the rows
	 * @return the keys
	 */
	@Nonnull
	private static List<String> distinctKeys(@Nonnull List<String> rows) {
		final List<String> keys = new ArrayList<>(rows.size());
		for (String row : rows) {
			final String key = row.split("[/ ]")[0];
			if (!keys.contains(key)) {
				keys.add(key);
			}
		}
		return keys;
	}

	/**
	 * Tells whether two of the rows reference the same entity.
	 *
	 * @param rows the descriptions of the rows
	 * @return TRUE when a referenced key repeats
	 */
	private static boolean repeatsKey(@Nonnull List<String> rows) {
		final Set<String> keys = CollectionUtils.createHashSet(rows.size());
		for (String row : rows) {
			if (!keys.add(row.split("[/ ]")[0])) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns the rows a chunk of the whole answer holds - as the decorator chunks them: a page past the last one falls
	 * back to the first, a strip starting past the end starts at the beginning.
	 *
	 * @param rows  the rows of the whole answer, in its order
	 * @param chunk the chunk
	 * @return the rows of the chunk
	 */
	@Nonnull
	private static List<String> chunk(@Nonnull List<String> rows, @Nonnull RequireConstraint chunk) {
		final int size = rows.size();
		final int from;
		final int limit;
		if (chunk instanceof Page page) {
			final int pageNumber = (page.getPageNumber() - 1) * page.getPageSize() + 1 > size ?
				1 : page.getPageNumber();
			from = (pageNumber - 1) * page.getPageSize();
			limit = page.getPageSize();
		} else {
			final Strip strip = (Strip) chunk;
			from = strip.getOffset() >= size ? 0 : strip.getOffset();
			limit = strip.getLimit();
		}
		return rows.subList(Math.min(from, size), Math.min(from + limit, size));
	}

	/**
	 * Fetches every owner with two named sets of the reference - the filter of the case and another one - on the body
	 * route, and checks each against the oracle of its own filter.
	 *
	 * @param theCase    the case
	 * @param statistics the statistics to update
	 * @param failures   the failures to add to
	 */
	private void runNamed(@Nonnull Case theCase, @Nonnull Statistics statistics, @Nonnull List<String> failures) {
		final Path path = Path.query(theCase);
		final FetchSpec first = theCase.fetchSpec();
		final FetchSpec second = new FetchSpec(
			theCase.reference(), ManagedReferencesBehaviour.ANY, Objects.requireNonNull(theCase.otherFilter()),
			false, false, null, null
		);
		final List<Map<Integer, List<String>>> setRows = List.of(new TreeMap<>(), new TreeMap<>());
		final Outcome fetched = onRoute(
			ReferenceContentRoute.BODY,
			() -> this.evita.queryCatalog(
				CATALOG,
				session -> {
					for (SealedEntity owner : session.queryListOfSealedEntities(
						query(
							collection(OWNER),
							filterBy(theCase.ownerFilter()),
							require(page(1, 100), entityFetch(first.content(null, "a"), second.content(null, "b")))
						)
					)) {
						for (int i = 0; i < 2; i++) {
							final FetchSpec set = i == 0 ? first : second;
							final DataChunk<ReferenceContract> rows = ((ServerEntityDecorator) owner)
								.getReferencesForReferenceContentInstance(
									new ReferenceContentKey(i == 0 ? "a" : "b", theCase.reference())
								)
								.orElseThrow();
							setRows.get(i).put(
								owner.getPrimaryKeyOrThrowException(),
								describeRows(rows.getData(), set.bodies(), set.groupBodies(), null)
							);
						}
					}
					return Outcome.of(setRows.get(0));
				}
			)
		);
		theCase.hit(statistics, "named");
		for (int i = 0; i < 2; i++) {
			// a refusal of one set refuses the whole request, so the second set is checked only when it answered
			final Outcome actual = fetched.rows() == null ? fetched : Outcome.of(setRows.get(i));
			if (i == 1 && actual.rows() == null) {
				break;
			}
			final Outcome expected = oracle(i == 0 ? first : second, path, actual.ownerPks(), statistics, theCase);
			if (!expected.answers(actual)) {
				failures.add(
					theCase + " / named set " + (i == 0 ? "a" : "b") + ": the body route answered " + actual +
						", the oracle " + expected
				);
			}
		}
	}

	/**
	 * Fetches every owner with a nameless `referenceContent()` carrying the filter - on the body route - and checks
	 * the rows of every reference against the oracle of the filter on that reference.
	 *
	 * @param theCase    the case
	 * @param statistics the statistics to update
	 * @param failures   the failures to add to
	 */
	private void runNameless(@Nonnull Case theCase, @Nonnull Statistics statistics, @Nonnull List<String> failures) {
		final Path path = Path.query(theCase);
		final FetchSpec spec = theCase.fetchSpec();
		final FilterBy filterBy = spec.filter().filterBy();
		final ReferenceContent nameless = new ReferenceContent(
			null, spec.managed(), ArrayUtils.EMPTY_STRING_ARRAY, new RequireConstraint[]{attributeContentAll()},
			filterBy == null ? new Constraint<?>[0] : new Constraint<?>[]{filterBy}
		);
		final Outcome fetched = onRoute(
			ReferenceContentRoute.BODY,
			() -> this.evita.queryCatalog(
				CATALOG,
				session -> {
					final Map<Integer, List<String>> rows = new TreeMap<>();
					for (SealedEntity owner : session.queryListOfSealedEntities(
						query(
							collection(OWNER), filterBy(theCase.ownerFilter()),
							require(page(1, 100), entityFetch(nameless))
						)
					)) {
						for (String reference : REFERENCES) {
							for (String row : describeRows(owner.getReferences(reference), false, false, null)) {
								rows.computeIfAbsent(owner.getPrimaryKeyOrThrowException(), it -> new ArrayList<>(8))
									.add(reference + ":" + row);
							}
						}
						rows.putIfAbsent(owner.getPrimaryKeyOrThrowException(), new ArrayList<>(0));
					}
					return Outcome.of(rows);
				}
			)
		);
		theCase.hit(statistics, "nameless");
		final Map<Integer, List<String>> expectedRows = new TreeMap<>();
		Outcome refusal = null;
		for (String reference : REFERENCES) {
			final Outcome expected = oracle(spec.onReference(reference), path, fetched.ownerPks(), statistics, theCase);
			if (expected.rows() == null) {
				refusal = expected;
				break;
			}
			expected.rows().forEach(
				(owner, rows) -> {
					final List<String> all = expectedRows.computeIfAbsent(owner, it -> new ArrayList<>(8));
					for (String row : rows) {
						all.add(reference + ":" + row);
					}
				}
			);
		}
		final Outcome expected = refusal == null ? Outcome.of(expectedRows) : refusal;
		if (!expected.answers(fetched)) {
			failures.add(
				theCase + " / nameless: the body route answered " + fetched + ", the oracle " + expected
			);
		}
	}

	/**
	 * Compares the body route with the oracle, and the index route with the body route.
	 *
	 * @param theCase                   the case
	 * @param path                      the path the owners were fetched by
	 * @param spec                      what was fetched
	 * @param body                      the outcome of the body route
	 * @param index                     the outcome of the index route
	 * @param indexWithoutNotApplicable runs the index route without the container the query model drops as not
	 *                                  applicable, NULL when the filter holds none
	 * @param statistics                the statistics to update
	 * @param failures                  the failures to add to
	 * @return TRUE when the body route answered as the oracle did
	 */
	private boolean check(
		@Nonnull Case theCase,
		@Nonnull Path path,
		@Nonnull FetchSpec spec,
		@Nonnull Outcome body,
		@Nonnull Outcome index,
		@Nullable Supplier<Outcome> indexWithoutNotApplicable,
		@Nonnull Statistics statistics,
		@Nonnull List<String> failures
	) {
		final Outcome expected = oracle(spec, path, body.ownerPks(), statistics, theCase);
		if (!expected.answers(body)) {
			failures.add(
				theCase + " / " + path.label() + ": the body route answered " + body + ", the oracle " + expected +
					"; rows " + rawRows(theCase.reference(), body.ownerPks())
			);
			return false;
		}
		statistics.outcomes++;
		if (expected.rows() == null) {
			statistics.refusalsPredicted++;
		} else {
			theCase.hitFeatures(statistics);
		}
		if (body.rows() == null && index.rows() == null && body.sameRefusal(index)) {
			if (body.failure().equals(index.failure())) {
				statistics.refusedAlikeByBoth++;
			} else {
				statistics.refusedAlikeOtherMessage++;
				if (statistics.otherMessages.size() < 3) {
					statistics.otherMessages.add(body.failure() + " / index: " + index.failure());
				}
			}
			return true;
		}
		if (!body.sameRows(index)) {
			final Set<String> classes = indexWithoutNotApplicable != null &&
				indexWithoutNotApplicable.get().sameRows(body) ?
				// the index route reads an empty container not normalized away as "nothing", the row filter skips it
				Set.of("1.2 not applicable container skipped") :
				explain(theCase, path, body, index);
			if (classes.isEmpty()) {
				failures.add(
					theCase + " / " + path.label() + ": the index route answered " + index + ", the body route " +
						body + " - in no decided class; rows " + rawRows(theCase.reference(), body.ownerPks())
				);
			} else {
				statistics.differences++;
				for (String decidedClass : classes) {
					statistics.differencesByClass.merge(decidedClass, 1, Integer::sum);
				}
			}
		}
		return true;
	}

	/**
	 * Describes the raw rows of the reference the owners hold - every attribute value of every locale, the group and
	 * the dropped flags - for the failure messages.
	 *
	 * @param reference the reference
	 * @param ownerPks  the owners
	 * @return the description
	 */
	@Nonnull
	private String rawRows(@Nonnull String reference, @Nonnull Set<Integer> ownerPks) {
		final StringBuilder result = new StringBuilder(256);
		for (int ownerPk : ownerPks) {
			final Entity owner = this.owners.get(ownerPk);
			result.append("\n  owner ").append(ownerPk).append(' ').append(owner.getScope())
				.append(' ').append(owner.getAllLocales()).append(':');
			for (ReferenceContract row : owner.getReferences(reference)) {
				result.append("\n    ").append(row.getReferencedPrimaryKey())
					.append(row.exists() ? "" : " DROPPED")
					.append(" group ")
					.append(row.getGroup().map(it -> it.getPrimaryKey() + (it.exists() ? "" : " DROPPED")).orElse("-"));
				for (AttributeValue value : row.getAttributeValues()) {
					result.append(' ').append(value.key()).append('=')
						.append(value.value() instanceof Object[] array ? Arrays.toString(array) : value.value())
						.append(value.exists() ? "" : " DROPPED");
				}
			}
		}
		return result.toString();
	}

	/**
	 * Returns the decided classes that may explain a difference between the routes - the features of the case that
	 * the routes were decided to treat differently.
	 *
	 * @param theCase the case
	 * @param path    the path the owners were fetched by
	 * @param body    the outcome of the body route
	 * @param index   the outcome of the index route
	 * @return the classes, empty when nothing explains the difference
	 */
	@Nonnull
	private Set<String> explain(
		@Nonnull Case theCase,
		@Nonnull Path path,
		@Nonnull Outcome body,
		@Nonnull Outcome index
	) {
		final Set<String> classes = new TreeSet<>();
		final String indexFailure = index.failure();
		if (indexFailure != null && body.failure() == null) {
			if (indexFailure.contains("Virtual read of")) {
				classes.add("D4 root scope over a single-owner partition");
			}
			if (indexFailure.startsWith(ReferenceComponentNotIndexedException.class.getSimpleName())) {
				classes.add("D2/R4 group component not required");
			}
			if (
				indexFailure.startsWith(AttributeNotFilterableException.class.getSimpleName()) ||
					indexFailure.startsWith(ReferenceNotIndexedException.class.getSimpleName()) ||
					indexFailure.startsWith(ArithmeticException.class.getSimpleName())
			) {
				classes.add("R1/U2 no index needed");
			}
			if (
				indexFailure.startsWith(GenericEvitaInternalError.class.getSimpleName()) &&
					indexFailure.contains("Cannot convert big decimal")
			) {
				// the index encodes a decimal as an int, which a value beyond its range cannot be (T9)
				classes.add("R1 decimal beyond the int range");
			}
			if (
				indexFailure.startsWith(AttributeNotFoundException.class.getSimpleName()) &&
					indexFailure.contains("`" + GROUP + "`") && theCase.features().contains("groupHaving") &&
					theCase.orderBy() != null && theCase.orderBy().toString().contains("entityProperty")
			) {
				// the index route plans the nested query of `groupHaving` with the ordering of the referenced
				// entities its processing scope carries (`HavingTranslatorHelper`), against the group collection,
				// which lacks the attribute; the row filter plans its nested queries without the ordering
				classes.add("D1/R5 index route orders groupHaving by the referenced entity");
			}
			if (indexFailure.startsWith(EntityLocaleMissingException.class.getSimpleName())) {
				if (theCase.filterLocale() != null) {
					classes.add("Q1 leaf locale");
				}
				if (theCase.mixedTraitLeaf()) {
					// the index route refuses the attribute in a partition of a scope binding it to a locale, the row
					// filter admits it because another scope it is compiled for makes it unique across locales
					classes.add("OQ-U/per-scope readings");
				}
			}
			return classes;
		}
		if (body.failure() != null) {
			// the oracle predicted the refusal of the body route: it refuses whatever the data, the index route only
			// when it has a row to evaluate the filter on - or never for a reference no examined scope indexes
			classes.add("R9/U1 refused whatever the data");
			return classes;
		}
		final Set<Scope> examinedScopes = theCase.filterSpec().examinedScopes(path.requestScopes());
		if (!theCase.indexedInAll(examinedScopes)) {
			classes.add("U1/D3 scope not indexing the reference");
		}
		if (path.ownerScope() != null || isOwnerOutside(path, body.ownerPks(), Set.of(path.requestScopes()))) {
			// the index route reads the partitions of the scopes of the request, so the rows of an owner living
			// elsewhere are never judged there - a root `scope(...)` widening the scopes the targets are looked up in
			// does not change that
			classes.add("U1 owner outside the scopes of the request");
		}
		if (theCase.rootGroupHaving()) {
			classes.add("D1/R5 root groupHaving per row");
		}
		final Set<Integer> sharedBorder = new HashSet<>(8);
		for (String attribute : new String[]{ATTR_RANGE, ATTR_VALID}) {
			if (theCase.features().contains("rangeAxisLeaf:" + attribute)) {
				sharedBorder.addAll(this.sharedBorderTargets.get(attribute));
			}
		}
		if (!sharedBorder.isEmpty() && differInTargetsOnly(body, index, sharedBorder)) {
			// the known issue #1699, outside this pull request: the reference type index keys a range by the
			// partition, so two owners' ranges of one target sharing a border miscount - and a removal strips the
			// border from the survivor; `referenceHaving` of the main query answers wrong the same way
			classes.add("known #1699: ranges of one partition sharing a border");
		}
		if (theCase.filterLocale() != null && theCase.localizedLeaf() &&
			!theCase.filterLocale().equals(path.requestLocale())) {
			classes.add("Q1 leaf locale");
		}
		if (theCase.filterLocale() != null && theCase.localizedLeaf()) {
			classes.add("T3 locale optimizer");
		}
		if (theCase.mixedTraitLeaf()) {
			classes.add("OQ-U/per-scope readings");
		}
		if (REF_DUPS.equals(theCase.reference())) {
			classes.add("G1/G2/X3 duplicates per row");
		}
		return classes;
	}

	/**
	 * Tells whether an owner of the outcome lives outside the scopes.
	 *
	 * @param path     the path the owners were fetched by
	 * @param ownerPks the owners
	 * @param scopes   the scopes
	 * @return TRUE when one does
	 */
	private boolean isOwnerOutside(
		@Nonnull Path path,
		@Nonnull Set<Integer> ownerPks,
		@Nonnull Set<Scope> scopes
	) {
		for (int ownerPk : ownerPks) {
			if (!scopes.contains(path.ownerScopeOf(path.ownerOf(ownerPk, this.owners)))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Tells whether every row the routes disagree on references one of the targets.
	 *
	 * @param body    the outcome of the body route
	 * @param index   the outcome of the index route
	 * @param targets the targets
	 * @return TRUE when the differing rows all reference one of them
	 */
	private static boolean differInTargetsOnly(
		@Nonnull Outcome body,
		@Nonnull Outcome index,
		@Nonnull Set<Integer> targets
	) {
		if (body.rows() == null || index.rows() == null || !body.rows().keySet().equals(index.rows().keySet())) {
			return false;
		}
		for (Entry<Integer, List<String>> entry : body.rows().entrySet()) {
			final Set<String> differing = new HashSet<>(stripped(entry.getValue()));
			final List<String> indexRows = stripped(index.rows().get(entry.getKey()));
			differing.addAll(indexRows);
			final Set<String> common = new HashSet<>(stripped(entry.getValue()));
			common.retainAll(indexRows);
			differing.removeAll(common);
			for (String row : differing) {
				if (!targets.contains(Integer.parseInt(row.split("/")[0]))) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Runs the supplier with the route switched, switching it back afterwards.
	 *
	 * @param route    the route
	 * @param supplier the supplier
	 * @return the outcome of the supplier, or the failure it threw
	 */
	@Nonnull
	private static Outcome onRoute(@Nonnull ReferenceContentRoute route, @Nonnull Supplier<Outcome> supplier) {
		final ReferenceContentRoute previous = ReferenceContentRoute.switchTo(route);
		try {
			return supplier.get();
		} catch (RuntimeException ex) {
			return Outcome.failed(ex);
		} finally {
			ReferenceContentRoute.switchTo(previous);
		}
	}

	/**
	 * Fetches every owner in the scopes of the case, in its locale, with the references the requirement keeps.
	 *
	 * @param theCase     the case
	 * @param requirement the requirement of the references
	 * @return the kept rows by owner, in the order of the answer
	 */
	@Nonnull
	private Outcome fetchByQuery(@Nonnull Case theCase, @Nonnull ReferenceContent requirement) {
		final Function<SealedEntity, List<String>> describer = theCase.fetchSpec().describer(
			theCase.chunk() == null ? null : requirement
		);
		return this.evita.queryCatalog(
			CATALOG,
			session -> {
				final Map<Integer, List<String>> rows = new TreeMap<>();
				for (SealedEntity owner : session.queryListOfSealedEntities(
					query(
						collection(OWNER),
						filterBy(theCase.ownerFilter()),
						require(page(1, 100), entityFetch(requirement))
					)
				)) {
					rows.put(owner.getPrimaryKeyOrThrowException(), describer.apply(owner));
				}
				return Outcome.of(rows);
			}
		);
	}

	/**
	 * Fetches one owner, looked up in both scopes, with the references the requirements keep.
	 *
	 * @param owner        the primary key of the owner
	 * @param requirements the requirements
	 * @param describer    describes the kept rows of the owner
	 * @return the kept rows
	 */
	@Nonnull
	private Outcome fetchByGetEntity(
		int owner,
		@Nonnull EntityContentRequire[] requirements,
		@Nonnull Function<SealedEntity, List<String>> describer
	) {
		return this.evita.queryCatalog(
			CATALOG,
			session -> {
				final SealedEntity entity = session.getEntity(OWNER, owner, BOTH, requirements).orElseThrow();
				return Outcome.of(Map.of(owner, describer.apply(entity)));
			}
		);
	}

	/**
	 * Describes the rows in their order: `target`, or `target/role` for `dups`, followed by ` b1` / ` b0` for a fetched
	 * or missing body of the referenced entity, ` g1` / ` g0` for the body of the group, and the owners of the nested
	 * level of the body in braces.
	 *
	 * @param references  the rows
	 * @param bodies      TRUE to describe the bodies of the referenced entities
	 * @param groupBodies TRUE to describe the bodies of the groups
	 * @param level2      the nested level, NULL for none
	 * @return the descriptions
	 */
	@Nonnull
	private static List<String> describeRows(
		@Nonnull Iterable<ReferenceContract> references,
		boolean bodies,
		boolean groupBodies,
		@Nullable Level2 level2
	) {
		final List<String> result = new ArrayList<>(8);
		for (ReferenceContract reference : references) {
			final StringBuilder row = new StringBuilder(32).append(key(reference));
			if (bodies) {
				final Optional<SealedEntity> body = reference.getReferencedEntity();
				row.append(body.isPresent() ? " b1" : " b0");
				if (level2 != null && body.isPresent()) {
					final List<Integer> owners = new ArrayList<>(4);
					for (ReferenceContract link : body.get().getReferences(REF_OWNER_LINKS)) {
						owners.add(link.getReferencedPrimaryKey());
					}
					owners.sort(null);
					row.append(' ').append(owners);
				}
			}
			if (groupBodies) {
				row.append(reference.getGroupEntity().isPresent() ? " g1" : " g0");
			}
			result.add(row.toString());
		}
		return result;
	}

	/**
	 * Returns the key a row is described by: `target`, or `target/role` for `dups`.
	 *
	 * @param reference the row
	 * @return the key
	 */
	@Nonnull
	private static String key(@Nonnull ReferenceContract reference) {
		return REF_DUPS.equals(reference.getReferenceName()) ?
			reference.getReferencedPrimaryKey() + "/" + reference.getAttribute(ATTR_ROLE) :
			String.valueOf(reference.getReferencedPrimaryKey());
	}

	/**
	 * Returns the descriptions sorted.
	 *
	 * @param rows the descriptions
	 * @return a sorted copy
	 */
	@Nonnull
	private static List<String> sorted(@Nonnull List<String> rows) {
		final List<String> result = new ArrayList<>(rows);
		result.sort(null);
		return result;
	}

	/**
	 * Returns the keys of the described rows, sorted - the descriptions without the bodies.
	 *
	 * @param rows the descriptions
	 * @return the keys
	 */
	@Nonnull
	private static List<String> stripped(@Nonnull List<String> rows) {
		final List<String> result = new ArrayList<>(rows.size());
		for (String row : rows) {
			final int space = row.indexOf(' ');
			result.add(space < 0 ? row : row.substring(0, space));
		}
		result.sort(null);
		return result;
	}

	/**
	 * Returns what the oracle expects for the owners.
	 *
	 * @param spec       what is fetched
	 * @param path       the path the owners are fetched by
	 * @param ownerPks   the primary keys of the owners the request returns
	 * @param statistics the statistics to count the situations met in
	 * @param theCase    the case, to count its situations
	 * @return the expected outcome
	 */
	@Nonnull
	private Outcome oracle(
		@Nonnull FetchSpec spec,
		@Nonnull Path path,
		@Nonnull Set<Integer> ownerPks,
		@Nonnull Statistics statistics,
		@Nonnull Case theCase
	) {
		final FilterSpec filter = spec.filter();
		final Locale leafLocale = filter.locale() == null ? path.requestLocale() : filter.locale();
		final Set<Scope> examinedScopes = filter.examinedScopes(path.requestScopes());
		// the fetch compiles the attribute readings for the examined scopes and for the scopes of its owners
		final Set<Scope> readingScopes = EnumSet.copyOf(examinedScopes);
		for (int ownerPk : ownerPks) {
			readingScopes.add(path.ownerScopeOf(path.ownerOf(ownerPk, this.owners)));
		}
		final Refusal refusal = filter.firstRefusal(leafLocale, Set.of(path.requestScopes()), readingScopes);
		if (refusal != null) {
			theCase.hit(statistics, "refused:" + refusal.kind());
			return Outcome.refused(refusal);
		}
		return this.evita.queryCatalog(
			CATALOG,
			session -> {
				final OracleContext context = new OracleContext(session, leafLocale, examinedScopes, statistics);
				// a missing or archived target cannot answer a condition - only the ones existing in the examined
				// scopes are judged, in the locale of the bodies under EXISTING
				final boolean gated = spec.managed() == ManagedReferencesBehaviour.EXISTING ||
					filter.filterBy() != null;
				final Locale requiredLocale = spec.managed() == ManagedReferencesBehaviour.EXISTING ?
					leafLocale : null;
				final Set<Integer> existing = gated ?
					context.existing(TARGET, examinedScopes, requiredLocale) : Set.of();
				// the bodies are fetched by a request derived from the entity-level constraints of the filter, which
				// read their scope and locale, or from the request of the owners when there is no filter
				final Set<Scope> bodyScopes = filter.bodyScopes(path.bodyParentScopes());
				final Set<Integer> bodies = spec.bodies() ? context.existing(TARGET, bodyScopes, leafLocale) : Set.of();
				final Set<Integer> groupBodies = spec.groupBodies() ?
					context.existing(GROUP, bodyScopes, leafLocale) : Set.of();
				final Set<Integer> existingGroups = spec.groupBodies() &&
					spec.managed() == ManagedReferencesBehaviour.EXISTING ?
					context.existing(GROUP, Set.of(path.requestScopes()), requiredLocale) : null;
				final Map<Integer, List<String>> rows = new TreeMap<>();
				boolean archivedOwnerTested = false;
				for (int ownerPk : ownerPks) {
					final Entity owner = path.ownerOf(ownerPk, this.owners);
					final Scope ownerScope = path.ownerScopeOf(owner);
					final List<String> kept = new ArrayList<>(8);
					final Map<Integer, int[]> verdictsByTarget = CollectionUtils.createHashMap(8);
					final Map<Integer, Integer> firstGroupByTarget = CollectionUtils.createHashMap(8);
					int tested = 0;
					for (ReferenceContract row : owner.getReferences(spec.reference())) {
						if (!row.exists()) {
							continue;
						}
						final int target = row.getReferencedPrimaryKey();
						if (gated && !existing.contains(target)) {
							statistics.gateRejectedRows++;
							continue;
						}
						tested++;
						final int[] verdicts = verdictsByTarget.computeIfAbsent(target, it -> new int[2]);
						final Integer groupOfRow = row.getGroup().map(GroupEntityReference::getPrimaryKey).orElse(null);
						final Integer firstGroup = groupOfRow == null ?
							null : firstGroupByTarget.putIfAbsent(target, groupOfRow);
						if (filter.oracle().test(context, ownerScope, owner, row)) {
							verdicts[0]++;
							statistics.admittedRows++;
							final String description = describe(
								row, spec, bodies, groupBodies, existingGroups, context, statistics, theCase
							);
							if (
								description.endsWith(" g1") && firstGroup != null && !firstGroup.equals(groupOfRow) &&
									ownerPks.size() == 1 && spec.orderBy() == null
							) {
								// a duplicate in another group than the first row of its target - alone on the page,
								// so no other owner's row can have its group recorded, and unordered, so the groups
								// are recorded per referenced key rather than read off the rows
								theCase.hit(statistics, "secondGroupOfDuplicates");
							}
							kept.add(description);
						} else {
							verdicts[1]++;
							statistics.filterRejectedRows++;
						}
					}
					if (REF_DUPS.equals(spec.reference())) {
						for (int[] verdicts : verdictsByTarget.values()) {
							if (verdicts[0] > 0 && verdicts[1] > 0) {
								theCase.hit(statistics, "dupSplitVerdicts");
								if (spec.orderBy() != null) {
									// a rejected row sharing the key of an admitted one, sorted into the slice
									theCase.hit(statistics, "sortedSplitDuplicates");
								}
							}
						}
					}
					if (tested > 0 && !examinedScopes.contains(ownerScope)) {
						theCase.hit(statistics, "ownerOutsideRequestScopes");
					}
					archivedOwnerTested |= tested > 0 && ownerScope == Scope.ARCHIVED;
					kept.sort(null);
					rows.put(ownerPk, kept);
				}
				if (
					path.query() && archivedOwnerTested && ownerPks.size() > 1 &&
						theCase.features().contains("attributeLeaf")
				) {
					theCase.hit(statistics, "archivedOwnerAttributeLeafOnMultiOwnerPage");
				}
				return Outcome.of(rows);
			}
		);
	}

	/**
	 * Describes a kept row as {@link #describeRows} does, with the bodies the oracle expects.
	 *
	 * @param row            the row
	 * @param spec           what is fetched
	 * @param bodies         the targets whose bodies are fetched
	 * @param groupBodies    the groups whose bodies are fetched
	 * @param existingGroups the groups existing in the scopes of the request, NULL when every group counts
	 * @param context        the context of the evaluation
	 * @param statistics     the statistics to count the situations met in
	 * @param theCase        the case, to count its situations
	 * @return the description
	 */
	@Nonnull
	private String describe(
		@Nonnull ReferenceContract row,
		@Nonnull FetchSpec spec,
		@Nonnull Set<Integer> bodies,
		@Nonnull Set<Integer> groupBodies,
		@Nullable Set<Integer> existingGroups,
		@Nonnull OracleContext context,
		@Nonnull Statistics statistics,
		@Nonnull Case theCase
	) {
		final StringBuilder description = new StringBuilder(32).append(key(row));
		if (spec.bodies()) {
			final int target = row.getReferencedPrimaryKey();
			final boolean present = bodies.contains(target);
			theCase.hit(statistics, present ? "bodyPresent" : "bodyMissing");
			description.append(present ? " b1" : " b0");
			if (spec.level2() != null && present) {
				description.append(' ').append(
					level2Rows(spec.level2(), this.targets.get(target), context, statistics, theCase)
				);
			}
		}
		if (spec.groupBodies()) {
			final Optional<GroupEntityReference> group = row.getGroup();
			final boolean present = group.isPresent() && group.get().exists() &&
				(existingGroups == null || existingGroups.contains(group.get().getPrimaryKey())) &&
				groupBodies.contains(group.get().getPrimaryKey());
			if (present) {
				theCase.hit(statistics, "groupBodyPresent");
			}
			description.append(present ? " g1" : " g0");
		}
		return description.toString();
	}

	/**
	 * Returns the owners the nested level keeps of the `ownerLinks` rows of a fetched target: the nested request reads
	 * the scopes the body was fetched in, and its filter needs the owners to exist there.
	 *
	 * @param level2     the nested level
	 * @param target     the raw target
	 * @param context    the context of the enclosing evaluation
	 * @param statistics the statistics to count the situations met in
	 * @param theCase    the case, to count its situations
	 * @return the kept owners, ascending
	 */
	@Nonnull
	private List<Integer> level2Rows(
		@Nonnull Level2 level2,
		@Nonnull Entity target,
		@Nonnull OracleContext context,
		@Nonnull Statistics statistics,
		@Nonnull Case theCase
	) {
		final Set<Integer> existingOwners = context.existing(OWNER, level2.scopes(), null);
		final List<Integer> kept = new ArrayList<>(4);
		for (ReferenceContract row : target.getReferences(REF_OWNER_LINKS)) {
			if (row.exists() && existingOwners.contains(row.getReferencedPrimaryKey())) {
				if (level2.oracle().test(context, target.getScope(), target, row)) {
					kept.add(row.getReferencedPrimaryKey());
					theCase.hit(statistics, "nestedRowsAdmitted");
				} else {
					theCase.hit(statistics, "nestedRowsRejected");
				}
			}
		}
		kept.sort(null);
		return kept;
	}

	/**
	 * The path the owners are fetched by.
	 *
	 * @param label            names the path in the failure messages
	 * @param requestScopes    the scopes of the request
	 * @param requestLocale    the locale of the request, NULL for none
	 * @param bodyParentScopes the scopes the bodies of the referenced entities are fetched in without a filter
	 * @param ownerScope       the scope the owner lives in when the path moves it, NULL when it stays where it is
	 * @param movedOwner       returns the raw owner as the path leaves it when the path moves it, NULL otherwise
	 * @param query            TRUE for the query of a page of owners
	 */
	private record Path(
		@Nonnull String label,
		@Nonnull Scope[] requestScopes,
		@Nullable Locale requestLocale,
		@Nonnull Set<Scope> bodyParentScopes,
		@Nullable Scope ownerScope,
		@Nullable Supplier<Entity> movedOwner,
		boolean query
	) {

		/**
		 * Returns the path of the query of every owner in the scopes and the locale of the case.
		 *
		 * @param theCase the case
		 * @return the path
		 */
		@Nonnull
		static Path query(@Nonnull Case theCase) {
			return new Path(
				"query", theCase.requestScopes(), theCase.queryLocale(), Set.of(theCase.requestScopes()), null, null,
				true
			);
		}

		/**
		 * Returns the path of `getEntity` in both scopes - its request names no locale.
		 *
		 * @param owner the owner
		 * @return the path
		 */
		@Nonnull
		static Path getEntity(int owner) {
			return new Path("getEntity of " + owner, BOTH, null, Set.of(BOTH), null, null, false);
		}

		/**
		 * Returns the path of an enrichment - its request reads LIVE.
		 *
		 * @param owner the owner
		 * @return the path
		 */
		@Nonnull
		static Path enrich(int owner) {
			return new Path("enrichEntity of " + owner, LIVE, null, Set.of(LIVE), null, null, false);
		}

		/**
		 * Returns the path of an archival - its request reads LIVE, the owner it returns lives in ARCHIVED.
		 *
		 * @param owner         the owner
		 * @param archivedOwner returns the raw owner as the archival leaves it
		 * @return the path
		 */
		@Nonnull
		static Path archive(int owner, @Nonnull Supplier<Entity> archivedOwner) {
			return new Path(
				"archiveEntity of " + owner, LIVE, null, Set.of(LIVE), Scope.ARCHIVED, archivedOwner, false
			);
		}

		/**
		 * Returns the raw owner the path judges: the one the path leaves when it moves the owner, the fixture's
		 * otherwise.
		 *
		 * @param ownerPk the primary key of the owner
		 * @param owners  the raw owners of the fixture
		 * @return the raw owner
		 */
		@Nonnull
		Entity ownerOf(int ownerPk, @Nonnull Map<Integer, Entity> owners) {
			final Entity moved = this.movedOwner == null ? null : this.movedOwner.get();
			return moved == null ? owners.get(ownerPk) : moved;
		}

		/**
		 * Returns the scope the owner is judged in.
		 *
		 * @param owner the raw owner
		 * @return its scope on this path
		 */
		@Nonnull
		Scope ownerScopeOf(@Nonnull Entity owner) {
			return this.ownerScope == null ? owner.getScope() : this.ownerScope;
		}

	}

	/**
	 * The outcome of a fetch: the kept rows by owner, or the failure.
	 *
	 * @param rows      the kept rows by owner, NULL when the fetch failed
	 * @param failure   the simple name of the exception class followed by its message, NULL when the fetch succeeded
	 * @param fragments the fragments the message of a refusal the oracle predicts must contain, NULL otherwise
	 */
	private record Outcome(
		@Nullable Map<Integer, List<String>> rows,
		@Nullable String failure,
		@Nullable String[] fragments
	) {

		/**
		 * Creates a successful outcome.
		 *
		 * @param rows the kept rows by owner
		 * @return the outcome
		 */
		@Nonnull
		static Outcome of(@Nonnull Map<Integer, List<String>> rows) {
			return new Outcome(rows, null, null);
		}

		/**
		 * Creates a failed outcome.
		 *
		 * @param exception the exception
		 * @return the outcome
		 */
		@Nonnull
		static Outcome failed(@Nonnull Throwable exception) {
			return new Outcome(null, exception.getClass().getSimpleName() + ": " + exception.getMessage(), null);
		}

		/**
		 * Creates the refusal the oracle predicts.
		 *
		 * @param refusal the refusal
		 * @return the outcome
		 */
		@Nonnull
		static Outcome refused(@Nonnull Refusal refusal) {
			return new Outcome(
				null, refusal.type().getSimpleName() + ": " + String.join(" ... ", refusal.fragments()),
				refusal.fragments()
			);
		}

		/**
		 * Tells whether the actual outcome is what this expected one says: the same rows, or a refusal of the same
		 * exception class whose message contains every fragment.
		 *
		 * @param actual the actual outcome
		 * @return TRUE when it is
		 */
		boolean answers(@Nonnull Outcome actual) {
			if (this.rows != null) {
				if (actual.rows == null || !this.rows.keySet().equals(actual.rows.keySet())) {
					return false;
				}
				for (Entry<Integer, List<String>> entry : this.rows.entrySet()) {
					if (!sorted(entry.getValue()).equals(sorted(actual.rows.get(entry.getKey())))) {
						return false;
					}
				}
				return true;
			}
			if (actual.failure == null || !failureClass(this.failure).equals(failureClass(actual.failure))) {
				return false;
			}
			for (String fragment : Objects.requireNonNull(this.fragments)) {
				if (!actual.failure.contains(fragment)) {
					return false;
				}
			}
			return true;
		}

		/**
		 * Tells whether the other outcome keeps the same rows - their bodies left aside - or fails with an exception
		 * of the same class.
		 *
		 * @param other the other outcome
		 * @return TRUE when they are the same
		 */
		boolean sameRows(@Nonnull Outcome other) {
			if (this.rows == null || other.rows == null) {
				return this.rows == null && other.rows == null && sameRefusal(other);
			}
			if (!this.rows.keySet().equals(other.rows.keySet())) {
				return false;
			}
			for (Entry<Integer, List<String>> entry : this.rows.entrySet()) {
				if (!stripped(entry.getValue()).equals(stripped(other.rows.get(entry.getKey())))) {
					return false;
				}
			}
			return true;
		}

		/**
		 * Tells whether both outcomes fail with an exception of the same class.
		 *
		 * @param other the other outcome
		 * @return TRUE when they do
		 */
		boolean sameRefusal(@Nonnull Outcome other) {
			return this.failure != null && other.failure != null &&
				failureClass(this.failure).equals(failureClass(other.failure));
		}

		/**
		 * Returns the exception class of a failure.
		 *
		 * @param failure the failure
		 * @return the simple name of the exception class
		 */
		@Nonnull
		private static String failureClass(@Nonnull String failure) {
			final int colon = failure.indexOf(':');
			return colon < 0 ? failure : failure.substring(0, colon);
		}

		/**
		 * Returns the owners of a successful outcome.
		 *
		 * @return the owner primary keys, empty for a failure
		 */
		@Nonnull
		Set<Integer> ownerPks() {
			return this.rows == null ? Set.of() : this.rows.keySet();
		}

		@Nonnull
		@Override
		public String toString() {
			return this.rows == null ? String.valueOf(this.failure) : this.rows.toString();
		}

	}

	/**
	 * Counts what the sweep saw, to prove it is not vacuous.
	 */
	private static final class Statistics {
		int outcomes;
		int refusalsPredicted;
		int refusedAlikeByBoth;
		int refusedAlikeOtherMessage;
		int admittedRows;
		int gateRejectedRows;
		int filterRejectedRows;
		int differences;
		final Map<String, Integer> differencesByClass = new TreeMap<>();
		/**
		 * The mismatches of the body route explained by known defects outside this pull request, by the defect.
		 */
		final Map<String, Integer> knownDefects = new TreeMap<>();
		/**
		 * The first refusals of both routes alike whose messages differ, for the log.
		 */
		final List<String> otherMessages = new ArrayList<>(3);
		/**
		 * The situations met, by feature - see {@link #REQUIRED_HITS}.
		 */
		final Map<String, Integer> hits = new TreeMap<>();

		@Override
		public String toString() {
			return "checked " + this.outcomes + " outcomes (" + this.refusalsPredicted + " refusals predicted), " +
				this.refusedAlikeByBoth + " refused alike by both routes (" + this.refusedAlikeOtherMessage +
				" with another message" + (this.otherMessages.isEmpty() ? "" : ": " + this.otherMessages) + "), " +
				this.admittedRows + " rows admitted, " + this.filterRejectedRows +
				" rejected by the filter, " + this.gateRejectedRows + " by the existence gate, " +
				this.differences + " differences from the index route: " + this.differencesByClass +
				"; known defects met: " + this.knownDefects + "; situations met: " + this.hits;
		}
	}

	/**
	 * The shape a case is fetched in.
	 */
	private enum Shape {
		/**
		 * The rows through a query and through `getEntity`.
		 */
		PLAIN,
		/**
		 * The rows with the bodies of their entities and groups, through a query and through `getEntity`.
		 */
		BODIES,
		/**
		 * A page or a strip of the rows with their bodies, ordered or not, against the whole answer.
		 */
		CHUNKED,
		/**
		 * Two named sets of the reference with different filters.
		 */
		NAMED,
		/**
		 * A nameless `referenceContent()` applying the filter to every reference.
		 */
		NAMELESS,
		/**
		 * The rows with their bodies and the filtered references of the bodies.
		 */
		NESTED,
		/**
		 * An enrichment of an owner, in a request reading LIVE.
		 */
		ENRICH,
		/**
		 * An archival of a live owner, in a request reading LIVE.
		 */
		ARCHIVE
	}

	/**
	 * What a fetch asks for.
	 *
	 * @param reference   the reference
	 * @param managed     ANY or EXISTING
	 * @param filter      the filter
	 * @param bodies      TRUE to fetch the bodies of the referenced entities
	 * @param groupBodies TRUE to fetch the bodies of the groups
	 * @param orderBy     the ordering of the references, NULL for none
	 * @param level2      the nested level, NULL for none
	 */
	private record FetchSpec(
		@Nonnull String reference,
		@Nonnull ManagedReferencesBehaviour managed,
		@Nonnull FilterSpec filter,
		boolean bodies,
		boolean groupBodies,
		@Nullable OrderBy orderBy,
		@Nullable Level2 level2
	) {

		/**
		 * Returns the requirement fetching the reference.
		 *
		 * @param chunk the chunk, NULL for every row
		 * @return the requirement
		 */
		@Nonnull
		ReferenceContent content(@Nullable RequireConstraint chunk) {
			return content(chunk, null);
		}

		/**
		 * Returns the requirement fetching the reference, possibly as a named set.
		 *
		 * @param chunk    the chunk, NULL for every row
		 * @param instance the name of the set, NULL for none
		 * @return the requirement
		 */
		@Nonnull
		ReferenceContent content(@Nullable RequireConstraint chunk, @Nullable String instance) {
			final List<RequireConstraint> requirements = new ArrayList<>(4);
			requirements.add(attributeContentAll());
			if (this.bodies) {
				requirements.add(
					this.level2 == null ?
						entityFetch(attributeContent(ATTR_CODE)) :
						entityFetch(
							attributeContent(ATTR_CODE),
							new ReferenceContent(
								null, ManagedReferencesBehaviour.ANY, new String[]{REF_OWNER_LINKS},
								new RequireConstraint[]{attributeContentAll()},
								new Constraint<?>[]{this.level2.filterBy()}
							)
						)
				);
			}
			if (this.groupBodies) {
				requirements.add(entityGroupFetch(attributeContent(ATTR_CODE)));
			}
			if (chunk != null) {
				requirements.add(chunk);
			}
			final List<Constraint<?>> children = new ArrayList<>(2);
			if (this.filter.filterBy() != null) {
				children.add(this.filter.filterBy());
			}
			if (this.orderBy != null) {
				children.add(this.orderBy);
			}
			return new ReferenceContent(
				instance, this.managed, new String[]{this.reference},
				requirements.toArray(RequireConstraint[]::new), children.toArray(Constraint<?>[]::new)
			);
		}

		/**
		 * Returns the describer of the rows an owner keeps.
		 *
		 * @param chunked the requirement of a chunked fetch, which keeps the order of the chunk; NULL for the whole
		 *                answer, sorted
		 * @return the describer
		 */
		@Nonnull
		Function<SealedEntity, List<String>> describer(@Nullable ReferenceContent chunked) {
			return owner -> {
				final List<String> rows = describeRows(
					chunked == null && this.orderBy == null ?
						owner.getReferences(this.reference) : owner.getReferenceChunk(this.reference).getData(),
					this.bodies, this.groupBodies, this.level2
				);
				return chunked == null && this.orderBy == null ? sorted(rows) : rows;
			};
		}

		/**
		 * Returns the same fetch with another filter.
		 *
		 * @param filterBy the filter
		 * @return the fetch
		 */
		@Nonnull
		FetchSpec withFilterBy(@Nonnull FilterBy filterBy) {
			return new FetchSpec(
				this.reference, this.managed, this.filter.withFilterBy(filterBy), this.bodies, this.groupBodies,
				this.orderBy, this.level2
			);
		}

		/**
		 * Returns the same fetch of another reference.
		 *
		 * @param reference the reference
		 * @return the fetch
		 */
		@Nonnull
		FetchSpec onReference(@Nonnull String reference) {
			return new FetchSpec(
				reference, this.managed, this.filter, this.bodies, this.groupBodies, this.orderBy, this.level2
			);
		}

	}

	/**
	 * A filter of the references and what it means.
	 *
	 * @param filterBy   the filter, NULL for none
	 * @param oracle     the meaning of the filter on a row
	 * @param locale     the locale of the filter's own `entityLocaleEquals`, NULL when there is none
	 * @param rootScope  the scopes of the root `scope(...)`, NULL when there is none
	 * @param innerScope the scopes of a `scope(...)` inside `entityHaving`, NULL when there is none
	 * @param refusals   the refusals the filter may meet, in the order the compiler meets them
	 */
	private record FilterSpec(
		@Nullable FilterBy filterBy,
		@Nonnull RowOracle oracle,
		@Nullable Locale locale,
		@Nullable Scope[] rootScope,
		@Nullable Scope[] innerScope,
		@Nonnull List<Refusal> refusals
	) {

		/**
		 * Returns the scopes the references are looked up in - those of the request and of every `scope(...)` of the
		 * filter.
		 *
		 * @param requestScopes the scopes of the request
		 * @return the scopes
		 */
		@Nonnull
		Set<Scope> examinedScopes(@Nonnull Scope[] requestScopes) {
			final EnumSet<Scope> result = EnumSet.copyOf(Arrays.asList(requestScopes));
			if (this.rootScope != null) {
				result.addAll(Arrays.asList(this.rootScope));
			}
			if (this.innerScope != null) {
				result.addAll(Arrays.asList(this.innerScope));
			}
			return result;
		}

		/**
		 * Returns the scopes the bodies of the referenced entities and groups are fetched in: the request derived for
		 * them keeps the scopes of the request of the owners, unless the entity-level constraints of the filter it
		 * keeps hold a `scope(...)` - the one inside `entityHaving`.
		 *
		 * @param parentScopes the scopes of the request of the owners
		 * @return the scopes
		 */
		@Nonnull
		Set<Scope> bodyScopes(@Nonnull Set<Scope> parentScopes) {
			return this.innerScope == null ? parentScopes : Set.of(this.innerScope);
		}

		/**
		 * Returns the first refusal the compiler meets.
		 *
		 * @param leafLocale    the locale the attribute leaves read, NULL for none
		 * @param requestScopes the scopes of the request
		 * @param readingScopes the scopes the attribute leaves outside `inScope` choose a reading for
		 * @return the refusal, NULL when the filter is admitted
		 */
		@Nullable
		Refusal firstRefusal(
			@Nullable Locale leafLocale,
			@Nonnull Set<Scope> requestScopes,
			@Nonnull Set<Scope> readingScopes
		) {
			for (Refusal refusal : this.refusals) {
				if (refusal.condition().applies(leafLocale, requestScopes, readingScopes)) {
					return refusal;
				}
			}
			return null;
		}

		/**
		 * Returns the same filter in another form the query model holds equal.
		 *
		 * @param filterBy the filter
		 * @return the filter
		 */
		@Nonnull
		FilterSpec withFilterBy(@Nonnull FilterBy filterBy) {
			return new FilterSpec(
				filterBy, this.oracle, this.locale, this.rootScope, this.innerScope, this.refusals
			);
		}

	}

	/**
	 * The filter of the nested level - over `rpTarget.ownerLinks`.
	 *
	 * @param filterBy the filter
	 * @param oracle   its meaning on a row
	 * @param scopes   the scopes the nested request reads - those the bodies of the targets are fetched in
	 */
	private record Level2(@Nonnull FilterBy filterBy, @Nonnull RowOracle oracle, @Nonnull Set<Scope> scopes) {
	}

	/**
	 * A refusal the compiler meets whatever the data, when its condition holds.
	 *
	 * @param kind      names the refusal in the statistics
	 * @param type      the class of the exception
	 * @param condition when the refusal applies
	 * @param fragments the fragments its message contains
	 */
	private record Refusal(
		@Nonnull String kind,
		@Nonnull Class<? extends Throwable> type,
		@Nonnull RefusalCondition condition,
		@Nonnull String[] fragments
	) {
	}

	/**
	 * When a refusal applies.
	 */
	@FunctionalInterface
	private interface RefusalCondition {

		/**
		 * Tells whether the refusal applies.
		 *
		 * @param leafLocale    the locale the attribute leaves read, NULL for none
		 * @param requestScopes the scopes of the request
		 * @param readingScopes the scopes the attribute leaves outside `inScope` choose a reading for
		 * @return TRUE when it does
		 */
		boolean applies(
			@Nullable Locale leafLocale,
			@Nonnull Set<Scope> requestScopes,
			@Nonnull Set<Scope> readingScopes
		);

	}

	/**
	 * A generated case - one filter of one reference, fetched in one shape, in some scopes, locale and
	 * managed-references behaviour.
	 *
	 * @param index                the number of the case
	 * @param shape                the shape the case is fetched in
	 * @param reference            the reference
	 * @param requestScopes        the scopes of the query
	 * @param queryLocale          the locale of the query, NULL for none
	 * @param managedReferences    ANY or EXISTING
	 * @param filterSpec           the filter and its meaning
	 * @param features             the features the generator put in the case
	 * @param localizedAttribute   the localized attribute a leaf of the filter reads, NULL when there is none
	 * @param withoutNotApplicable the filter without the container the query model drops as not applicable, NULL when
	 *                             it holds none
	 * @param bodies               TRUE to fetch the bodies of the referenced entities
	 * @param groupBodies          TRUE to fetch the bodies of the groups
	 * @param orderBy              the ordering of the references, NULL for none
	 * @param chunk                the page or strip of a chunked case, NULL otherwise
	 * @param otherFilter          the filter of the second named set, NULL unless the shape is named
	 * @param level2               the nested level, NULL unless the shape is nested
	 * @param getEntityLocale      the locale `getEntity` asks for the data in, NULL for none
	 * @param onlyOwner            the only owner the query of a chunked case fetches, NULL for every owner - one owner
	 *                             alone has the bodies of no other owner's slice
	 */
	private record Case(
		int index,
		@Nonnull Shape shape,
		@Nonnull String reference,
		@Nonnull Scope[] requestScopes,
		@Nullable Locale queryLocale,
		@Nonnull ManagedReferencesBehaviour managedReferences,
		@Nonnull FilterSpec filterSpec,
		@Nonnull Set<String> features,
		@Nullable String localizedAttribute,
		@Nullable FilterBy withoutNotApplicable,
		boolean bodies,
		boolean groupBodies,
		@Nullable OrderBy orderBy,
		@Nullable RequireConstraint chunk,
		@Nullable FilterSpec otherFilter,
		@Nullable Level2 level2,
		@Nullable Locale getEntityLocale,
		@Nullable Integer onlyOwner
	) {

		/**
		 * Returns what the case fetches.
		 *
		 * @return the fetch
		 */
		@Nonnull
		FetchSpec fetchSpec() {
			return new FetchSpec(
				this.reference, this.managedReferences, this.filterSpec, this.bodies, this.groupBodies, this.orderBy,
				this.level2
			);
		}

		/**
		 * Returns the filter of the owners: their scopes and the locale of the query.
		 *
		 * @return the constraints
		 */
		@Nonnull
		FilterConstraint[] ownerFilter() {
			final List<FilterConstraint> constraints = new ArrayList<>(3);
			constraints.add(scope(this.requestScopes));
			if (this.queryLocale != null) {
				constraints.add(entityLocaleEquals(this.queryLocale));
			}
			if (this.onlyOwner != null) {
				constraints.add(entityPrimaryKeyInSet(this.onlyOwner));
			}
			return constraints.toArray(FilterConstraint[]::new);
		}

		/**
		 * Returns the locale of the filter's own `entityLocaleEquals`.
		 *
		 * @return the locale, NULL when there is none
		 */
		@Nullable
		Locale filterLocale() {
			return this.filterSpec.locale();
		}

		/**
		 * Tells whether the reference is indexed in all the scopes.
		 *
		 * @param scopes the scopes
		 * @return TRUE when every scope indexes the reference
		 */
		boolean indexedInAll(@Nonnull Set<Scope> scopes) {
			return switch (this.reference) {
				case REF_BOTH -> true;
				case REF_NOWHERE -> false;
				default -> !scopes.contains(Scope.ARCHIVED);
			};
		}

		/**
		 * Tells whether the root holds a `groupHaving` - which the index route lifts out of the filter and decides per
		 * referenced key, over the rows of all owners fetched along.
		 *
		 * @return TRUE for the shape of D1
		 */
		boolean rootGroupHaving() {
			return this.features.contains("rootGroupHaving");
		}

		/**
		 * Tells whether a leaf reads a localized attribute.
		 *
		 * @return TRUE when one does
		 */
		boolean localizedLeaf() {
			return this.localizedAttribute != null;
		}

		/**
		 * Tells whether a leaf reads an attribute whose flags differ per scope.
		 *
		 * @return TRUE when one does
		 */
		boolean mixedTraitLeaf() {
			return this.features.contains("mixedTrait");
		}

		/**
		 * Counts a situation met.
		 *
		 * @param statistics the statistics
		 * @param feature    the situation
		 */
		void hit(@Nonnull Statistics statistics, @Nonnull String feature) {
			statistics.hits.merge(feature, 1, Integer::sum);
		}

		/**
		 * Counts the features of the generated filter whose situation is met whenever an answer of the case is
		 * checked.
		 *
		 * @param statistics the statistics
		 */
		void hitFeatures(@Nonnull Statistics statistics) {
			for (String feature : this.features) {
				if (feature.startsWith("hit:")) {
					hit(statistics, feature.substring(4));
				}
			}
		}

		@Nonnull
		@Override
		public String toString() {
			return "case " + this.index + " " + this.shape + " [" + this.reference + " in " +
				Arrays.toString(this.requestScopes) + ", locale " + this.queryLocale + ", " + this.managedReferences +
				(this.orderBy == null ? "" : ", " + this.orderBy) + (this.chunk == null ? "" : ", " + this.chunk) +
				(this.getEntityLocale == null ? "" : ", getEntity in " + this.getEntityLocale) +
				(this.otherFilter == null ? "" : ", other set " + this.otherFilter.filterBy()) +
				(this.onlyOwner == null ? "" : ", owner " + this.onlyOwner) +
				(this.level2 == null ? "" : ", nested " + this.level2.filterBy()) + "]: " + this.filterSpec.filterBy();
		}

	}

	/**
	 * A generated constraint together with its meaning on a single row.
	 *
	 * @param constraint the constraint, NULL for a container the query model drops as not applicable
	 * @param oracle     the meaning of the constraint on a row
	 */
	private record Node(@Nullable FilterConstraint constraint, @Nonnull RowOracle oracle) {
	}

	/**
	 * The meaning of a constraint on a single reference row of an owner.
	 */
	@FunctionalInterface
	private interface RowOracle {

		/**
		 * Tells whether the row satisfies the constraint.
		 *
		 * @param context    the context of the evaluation
		 * @param ownerScope the scope of the owner
		 * @param owner      the raw owner
		 * @param row        the existing row
		 * @return TRUE when the row satisfies the constraint
		 */
		boolean test(
			@Nonnull OracleContext context,
			@Nonnull Scope ownerScope,
			@Nonnull Entity owner,
			@Nonnull ReferenceContract row
		);

	}

	/**
	 * What the oracle needs while evaluating one case: the session for the nested queries, the locale of the leaves
	 * and the examined scopes, the memo of the nested queries and the statistics of the situations met.
	 */
	private static final class OracleContext {
		private final EvitaSessionContract session;
		@Nullable private final Locale leafLocale;
		private final Set<Scope> examinedScopes;
		private final Statistics statistics;
		private final Map<String, Set<Integer>> nestedMemo = CollectionUtils.createHashMap(16);

		/**
		 * Creates the context of one evaluation.
		 *
		 * @param session        the session the nested queries run in
		 * @param leafLocale     the locale the localized attribute leaves read, NULL when none is known
		 * @param examinedScopes the scopes the references are looked up in
		 * @param statistics     the statistics of the situations met
		 */
		OracleContext(
			@Nonnull EvitaSessionContract session,
			@Nullable Locale leafLocale,
			@Nonnull Set<Scope> examinedScopes,
			@Nonnull Statistics statistics
		) {
			this.session = session;
			this.leafLocale = leafLocale;
			this.examinedScopes = examinedScopes;
			this.statistics = statistics;
		}

		/**
		 * Counts a situation met.
		 *
		 * @param feature the situation
		 */
		void hit(@Nonnull String feature) {
			this.statistics.hits.merge(feature, 1, Integer::sum);
		}

		/**
		 * Returns the entities of the collection existing in the scopes, in the locale when one is passed.
		 *
		 * @param entityType the collection
		 * @param scopes     the scopes
		 * @param locale     the locale the entities must have data in, NULL for any
		 * @return the primary keys
		 */
		@Nonnull
		Set<Integer> existing(@Nonnull String entityType, @Nonnull Set<Scope> scopes, @Nullable Locale locale) {
			final Scope[] scopeArray = scopes.toArray(Scope[]::new);
			return nested(
				entityType,
				locale == null ?
					filterBy(scope(scopeArray)) : filterBy(scope(scopeArray), entityLocaleEquals(locale)),
				"existing " + entityType + " " + locale + " " + scopes
			);
		}

		/**
		 * Returns the entities a nested filter selects in any of the scopes - a public query per scope.
		 *
		 * @param entityType the collection
		 * @param filter     the nested filter
		 * @param scopes     the scopes
		 * @return the primary keys
		 */
		@Nonnull
		Set<Integer> nested(@Nonnull String entityType, @Nonnull FilterConstraint filter, @Nonnull Set<Scope> scopes) {
			final Set<Integer> result = CollectionUtils.createHashSet(16);
			for (Scope scope : scopes) {
				result.addAll(
					nested(entityType, filterBy(filter, scope(scope)), entityType + " " + filter + " " + scope)
				);
			}
			return result;
		}

		/**
		 * Runs and memoizes a public query.
		 *
		 * @param entityType the collection
		 * @param filterBy   the filter
		 * @param key        the memo key
		 * @return the primary keys
		 */
		@Nonnull
		private Set<Integer> nested(@Nonnull String entityType, @Nonnull FilterBy filterBy, @Nonnull String key) {
			return this.nestedMemo.computeIfAbsent(
				key,
				it -> {
					final Set<Integer> result = CollectionUtils.createHashSet(16);
					for (EntityReference reference : this.session.queryList(
						query(collection(entityType), filterBy, require(page(1, 1000))), EntityReference.class
					)) {
						result.add(reference.getPrimaryKey());
					}
					return result;
				}
			);
		}

	}

	/**
	 * Generates one case. Every filter holds at most one `entityLocaleEquals` outside `groupHaving` - bare in the root,
	 * in an `inScope` of the root, in an `entityHaving`, under a `not` or under an `or` - and at most one `scope(...)`,
	 * directly in the root or inside an `entityHaving`.
	 */
	private static final class CaseGenerator {
		private final Random random;
		private final List<String> mixedValues;
		private Shape shape;
		private String reference;
		private Scope[] requestScopes;
		@Nullable private Locale filterLocale;
		@Nullable private Locale leafLocale;
		@Nullable private Scope[] rootScope;
		@Nullable private Scope[] innerScope;
		private final Set<String> features = CollectionUtils.createHashSet(8);
		@Nullable private String localizedAttribute;
		private final List<Refusal> refusals = new ArrayList<>(4);
		/**
		 * TRUE while generating a filter that must have a meaning on every reference - only keys, locales and
		 * `entityHaving`, for the nameless `referenceContent()`.
		 */
		private boolean keysOnly;
		private final Map<String, Boolean> unboundInLive = Map.of(ATTR_MIXED, true, ATTR_LOC, false);

		/**
		 * Creates a generator of one case.
		 *
		 * @param random      the source of randomness
		 * @param mixedValues the values of `mixed` the fixture holds
		 */
		CaseGenerator(@Nonnull Random random, @Nonnull List<String> mixedValues) {
			this.random = random;
			this.mixedValues = mixedValues;
		}

		/**
		 * Generates the case.
		 *
		 * @param index the number of the case
		 * @return the case
		 */
		@Nonnull
		Case generate(int index) {
			this.shape = pickShape();
			// the chunks of duplicates are where a page of rows and a page of referenced keys part ways
			this.reference = this.shape == Shape.CHUNKED && this.random.nextBoolean() ?
				REF_DUPS : pick(this.random, REFERENCES);
			this.requestScopes = pick(this.random, SCOPE_SETS);
			this.keysOnly = this.shape == Shape.NAMELESS;
			final Locale queryLocale = this.random.nextInt(3) == 0 ? null : pick(this.random, LOCALES);
			final ManagedReferencesBehaviour managedReferences = this.random.nextInt(4) == 0 ?
				ManagedReferencesBehaviour.EXISTING : ManagedReferencesBehaviour.ANY;
			// a chunk needs rows to cut, so a chunked case keeps every row more often
			final int filterMode = this.shape == Shape.CHUNKED && this.random.nextBoolean() ?
				this.random.nextInt(11) : this.random.nextInt(100);
			final FilterSpec filterSpec;
			final FilterBy withoutNotApplicable;
			if (filterMode < 6) {
				this.features.add(
					managedReferences == ManagedReferencesBehaviour.ANY ? "hit:noFilterAny" : "hit:noFilterExisting"
				);
				filterSpec = new FilterSpec(
					null, (context, ownerScope, owner, row) -> true, null, null, null, List.of()
				);
				withoutNotApplicable = null;
			} else if (filterMode < 11) {
				this.features.add("hit:scopeOnly");
				this.rootScope = pick(this.random, SCOPE_SETS);
				filterSpec = new FilterSpec(
					filterBy(scope(this.rootScope)), (context, ownerScope, owner, row) -> true, null, this.rootScope,
					null, List.of()
				);
				withoutNotApplicable = null;
			} else {
				final int localePlacement = this.random.nextInt(12);
				this.filterLocale = localePlacement < 5 ? pick(this.random, LOCALES) : null;
				this.leafLocale = this.filterLocale == null ? queryLocale : this.filterLocale;
				this.rootScope = this.random.nextInt(6) == 0 ? pick(this.random, SCOPE_SETS) : null;
				final List<FilterConstraint> rootConstraints = new ArrayList<>(4);
				final List<RowOracle> rootOracles = new ArrayList<>(4);
				if (this.rootScope != null) {
					rootConstraints.add(scope(this.rootScope));
				}
				if (this.filterLocale != null) {
					final Node localeNode = switch (localePlacement) {
						case 0 -> ownerLocale(this.filterLocale);
						case 1 -> {
							final Scope inScope = pick(this.random, this.requestScopes);
							this.refusals.add(inScopeRefusal(inScope));
							yield inScope(inScope, List.of(ownerLocale(this.filterLocale)));
						}
						case 2 -> entityHaving(null, true);
						case 3 -> {
							this.features.add("hit:localeInNot");
							final Node owned = ownerLocale(this.filterLocale);
							yield new Node(
								not(owned.constraint()),
								(context, ownerScope, owner, row) ->
									!owned.oracle().test(context, ownerScope, owner, row)
							);
						}
						default -> or(List.of(ownerLocale(this.filterLocale), node(2, null)));
					};
					rootConstraints.add(localeNode.constraint());
					rootOracles.add(localeNode.oracle());
				}
				final int rowNodes = 1 + this.random.nextInt(2);
				boolean groupHaving = false;
				for (int i = 0; i < rowNodes; i++) {
					final Node node = node(1, null);
					if (node.constraint() instanceof GroupHaving) {
						groupHaving = true;
					}
					if (node.constraint() != null) {
						rootConstraints.add(node.constraint());
						rootOracles.add(node.oracle());
					}
				}
				if (groupHaving) {
					this.features.add("rootGroupHaving");
				}
				if (this.random.nextInt(20) == 0) {
					// a container the query model drops as not applicable
					withoutNotApplicable = new FilterBy(rootConstraints.toArray(FilterConstraint[]::new));
					rootConstraints.add(io.evitadb.api.query.QueryConstraints.and());
				} else {
					withoutNotApplicable = null;
				}
				filterSpec = new FilterSpec(
					new FilterBy(rootConstraints.toArray(FilterConstraint[]::new)),
					(context, ownerScope, owner, row) -> {
						for (RowOracle oracle : rootOracles) {
							if (!oracle.test(context, ownerScope, owner, row)) {
								return false;
							}
						}
						return true;
					},
					this.filterLocale, this.rootScope, this.innerScope, List.copyOf(this.refusals)
				);
			}
			final boolean bodies = this.shape == Shape.BODIES || this.shape == Shape.CHUNKED ||
				this.shape == Shape.NESTED || (this.shape != Shape.NAMELESS && this.random.nextInt(4) == 0);
			final boolean groupBodies = bodies && GROUPED_REFERENCES.contains(this.reference) &&
				this.shape != Shape.NESTED && this.random.nextInt(4) > 0;
			return new Case(
				index, this.shape, this.reference, this.requestScopes, queryLocale, managedReferences, filterSpec,
				Set.copyOf(this.features), this.localizedAttribute, withoutNotApplicable, bodies, groupBodies,
				this.shape == Shape.CHUNKED && this.random.nextBoolean() ? referenceOrderBy() : null,
				this.shape == Shape.CHUNKED ? chunk() : null,
				this.shape == Shape.NAMED ? otherFilter() : null,
				this.shape == Shape.NESTED ? level2(filterSpec, queryLocale) : null,
				(this.shape == Shape.PLAIN || this.shape == Shape.BODIES) && this.random.nextInt(4) == 0 ?
					pick(this.random, LOCALES) : null,
				this.shape == Shape.CHUNKED && this.random.nextInt(3) > 0 ? ownerInRequestScopes() : null
			);
		}

		/**
		 * Returns a random owner living in a scope of the request.
		 *
		 * @return the primary key of the owner
		 */
		private int ownerInRequestScopes() {
			if (Arrays.equals(this.requestScopes, LIVE)) {
				return 1 + this.random.nextInt(LAST_LIVE_OWNER);
			} else if (Arrays.equals(this.requestScopes, ARCHIVED)) {
				return pick(this.random, Arrays.stream(ARCHIVED_OWNERS).boxed().toArray(Integer[]::new));
			} else {
				return 1 + this.random.nextInt(OWNER_COUNT);
			}
		}

		/**
		 * Picks the shape of the case.
		 *
		 * @return the shape
		 */
		@Nonnull
		private Shape pickShape() {
			final int choice = this.random.nextInt(100);
			if (choice < 25) {
				return Shape.PLAIN;
			} else if (choice < 40) {
				return Shape.BODIES;
			} else if (choice < 60) {
				return Shape.CHUNKED;
			} else if (choice < 68) {
				return Shape.NAMED;
			} else if (choice < 75) {
				return Shape.NAMELESS;
			} else if (choice < 85) {
				return Shape.NESTED;
			} else if (choice < 93) {
				return Shape.ENRICH;
			} else {
				return Shape.ARCHIVE;
			}
		}

		/**
		 * Returns an ordering of the references valid wherever the case runs: by the target's sortable `number`, or
		 * by the reference's own `rank` where it is sortable in every scope the query reads.
		 *
		 * @return the ordering
		 */
		@Nonnull
		private OrderBy referenceOrderBy() {
			final OrderDirection direction = this.random.nextBoolean() ? OrderDirection.ASC : OrderDirection.DESC;
			final boolean rankSortable = REF_BOTH.equals(this.reference) || (
				(REF_LIVE_ONLY.equals(this.reference) || REF_DUPS.equals(this.reference)) &&
					Arrays.equals(this.requestScopes, LIVE)
			);
			return rankSortable && this.random.nextBoolean() ?
				orderBy(attributeNatural(ATTR_RANK, direction)) :
				orderBy(entityProperty(attributeNatural(ATTR_NUMBER, direction)));
		}

		/**
		 * Returns a random page or strip - past the end of the rows now and then.
		 *
		 * @return the chunk
		 */
		@Nonnull
		private RequireConstraint chunk() {
			return this.random.nextBoolean() ?
				page(1 + this.random.nextInt(4), 1 + this.random.nextInt(2)) :
				strip(this.random.nextInt(6), 1 + this.random.nextInt(2));
		}

		/**
		 * Returns the filter of the second named set - on keys only.
		 *
		 * @return the filter
		 */
		@Nonnull
		private FilterSpec otherFilter() {
			final Integer[] keys = randomKeys(TARGET_COUNT);
			final Set<Integer> keySet = Set.of(Arrays.stream(keys).distinct().toArray(Integer[]::new));
			final boolean negated = this.random.nextBoolean();
			return new FilterSpec(
				negated ? filterBy(not(entityPrimaryKeyInSet(keys))) : filterBy(entityPrimaryKeyInSet(keys)),
				(context, ownerScope, owner, row) -> keySet.contains(row.getReferencedPrimaryKey()) != negated,
				null, null, null, List.of()
			);
		}

		/**
		 * Returns the nested level: a filter of the `ownerLinks` of the fetched targets, read in the scopes the bodies
		 * of the targets are fetched in.
		 *
		 * @param filterSpec  the filter of the first level
		 * @param queryLocale the locale of the query
		 * @return the nested level
		 */
		@Nonnull
		private Level2 level2(@Nonnull FilterSpec filterSpec, @Nullable Locale queryLocale) {
			final int value = 1 + this.random.nextInt(3);
			final Integer[] keys = randomKeys(OWNER_COUNT);
			final Set<Integer> keySet = Set.of(Arrays.stream(keys).distinct().toArray(Integer[]::new));
			final Node node = switch (this.random.nextInt(5)) {
				case 0 -> new Node(
					attributeEquals(ATTR_WEIGHT, value),
					(context, ownerScope, owner, row) -> Objects.equals(row.getAttribute(ATTR_WEIGHT), value)
				);
				case 1 -> new Node(
					attributeLessThanEquals(ATTR_WEIGHT, value),
					(context, ownerScope, owner, row) -> {
						final Integer weight = row.getAttribute(ATTR_WEIGHT);
						return weight != null && weight <= value;
					}
				);
				case 2 -> new Node(
					attributeIsNull(ATTR_WEIGHT),
					(context, ownerScope, owner, row) -> row.getAttribute(ATTR_WEIGHT) == null
				);
				case 3 -> new Node(
					entityPrimaryKeyInSet(keys),
					(context, ownerScope, owner, row) -> keySet.contains(row.getReferencedPrimaryKey())
				);
				default -> new Node(
					not(entityPrimaryKeyInSet(keys)),
					(context, ownerScope, owner, row) -> !keySet.contains(row.getReferencedPrimaryKey())
				);
			};
			final Set<Scope> parentScopes = Set.of(this.requestScopes);
			return new Level2(
				filterBy(node.constraint()), node.oracle(), filterSpec.bodyScopes(parentScopes)
			);
		}

		/**
		 * Generates a node of the row tree.
		 *
		 * @param depth  the depth of the node
		 * @param scopes the scopes the node is compiled for (P), NULL for the examined scopes of the request
		 * @return the node
		 */
		@Nonnull
		private Node node(int depth, @Nullable Set<Scope> scopes) {
			final int choice = this.random.nextInt(100);
			if (!this.keysOnly && choice < 2) {
				return withoutMeaningOnRow();
			} else if (depth >= 3 || choice < 50) {
				return leaf(scopes);
			} else if (choice < 60) {
				return and(children(depth, scopes));
			} else if (choice < 70) {
				return or(children(depth, scopes));
			} else if (choice < 78) {
				final Node child = node(depth + 1, scopes);
				return new Node(
					not(child.constraint()),
					(context, ownerScope, owner, row) -> !child.oracle().test(context, ownerScope, owner, row)
				);
			} else if (choice < 86 && scopes == null) {
				// `inScope` nested in another one is refused by the query model itself
				final Scope inScope = pick(this.random, this.requestScopes);
				this.refusals.add(inScopeRefusal(inScope));
				return inScope(inScope, children(depth, EnumSet.of(inScope)));
			} else if (
				choice < 93 || this.keysOnly || REF_NOWHERE.equals(this.reference) ||
					REF_REFLECTED.equals(this.reference)
			) {
				return entityHaving(scopes, false);
			} else {
				return groupHaving(scopes);
			}
		}

		/**
		 * Returns the refusal of an `inScope` whose scope the request does not read - the enrichment and the archival
		 * read LIVE whatever the scopes of the case.
		 *
		 * @param inScope the scope of the container
		 * @return the refusal
		 */
		@Nonnull
		private static Refusal inScopeRefusal(@Nonnull Scope inScope) {
			return new Refusal(
				"inScope", EvitaInvalidUsageException.class,
				(leafLocale, requestScopes, readingScopes) -> !requestScopes.contains(inScope),
				new String[]{"`" + inScope + "` used in `inScope`", "was not requested"}
			);
		}

		/**
		 * Creates a constraint without a meaning on a row - refused whatever the data (R6).
		 *
		 * @return the node
		 */
		@Nonnull
		private Node withoutMeaningOnRow() {
			final FilterConstraint constraint = this.random.nextBoolean() ?
				priceInCurrency("EUR") : entityPrimaryKeyBetween(1, 3);
			final String name = constraint.getName();
			this.refusals.add(
				new Refusal(
					"R6", EvitaInvalidUsageException.class, (leafLocale, requestScopes, readingScopes) -> true,
					new String[]{name, "`" + this.reference + "`"}
				)
			);
			return new Node(constraint, (context, ownerScope, owner, row) -> false);
		}

		/**
		 * Generates two or three children.
		 *
		 * @param depth  the depth of the parent
		 * @param scopes the scopes the children are compiled for, NULL for the examined scopes of the request
		 * @return the children
		 */
		@Nonnull
		private List<Node> children(int depth, @Nullable Set<Scope> scopes) {
			final List<Node> children = new ArrayList<>(3);
			for (int i = 2 + this.random.nextInt(2); i > 0; i--) {
				children.add(node(depth + 1, scopes));
			}
			return children;
		}

		/**
		 * Creates an `and` node.
		 *
		 * @param children the children
		 * @return the node
		 */
		@Nonnull
		private static Node and(@Nonnull List<Node> children) {
			return new Node(
				io.evitadb.api.query.QueryConstraints.and(
					children.stream().map(Node::constraint).toArray(FilterConstraint[]::new)
				),
				(context, ownerScope, owner, row) -> {
					for (Node child : children) {
						if (!child.oracle().test(context, ownerScope, owner, row)) {
							return false;
						}
					}
					return true;
				}
			);
		}

		/**
		 * Creates an `or` node.
		 *
		 * @param children the children
		 * @return the node
		 */
		@Nonnull
		private static Node or(@Nonnull List<Node> children) {
			return new Node(
				io.evitadb.api.query.QueryConstraints.or(
					children.stream().map(Node::constraint).toArray(FilterConstraint[]::new)
				),
				(context, ownerScope, owner, row) -> {
					for (Node child : children) {
						if (child.oracle().test(context, ownerScope, owner, row)) {
							return true;
						}
					}
					return false;
				}
			);
		}

		/**
		 * Creates an `inScope` node: the children apply to the rows of the owners of the scope only.
		 *
		 * @param scope    the scope
		 * @param children the children
		 * @return the node
		 */
		@Nonnull
		private static Node inScope(@Nonnull Scope scope, @Nonnull List<Node> children) {
			final Node conjunction = and(children);
			return new Node(
				io.evitadb.api.query.QueryConstraints.inScope(
					scope, children.stream().map(Node::constraint).toArray(FilterConstraint[]::new)
				),
				(context, ownerScope, owner, row) ->
					ownerScope != scope || conjunction.oracle().test(context, ownerScope, owner, row)
			);
		}

		/**
		 * Creates an `entityLocaleEquals` row leaf: the owner holds the locale.
		 *
		 * @param locale the locale
		 * @return the node
		 */
		@Nonnull
		private static Node ownerLocale(@Nonnull Locale locale) {
			return new Node(
				entityLocaleEquals(locale),
				(context, ownerScope, owner, row) -> owner.getAllLocales().contains(locale)
			);
		}

		/**
		 * Creates an `entityHaving` node over the target collection - now and then with a `scope(...)` inside, which
		 * the nested query is planned in and the existence gate widens the examined scopes by.
		 *
		 * @param scopes     the scopes the nested query runs in, NULL for the examined scopes of the request
		 * @param withLocale TRUE to put the filter locale into the nested filter
		 * @return the node
		 */
		@Nonnull
		private Node entityHaving(@Nullable Set<Scope> scopes, boolean withLocale) {
			final FilterConstraint nested = nestedTargetFilter();
			final FilterConstraint filter = withLocale ?
				io.evitadb.api.query.QueryConstraints.and(entityLocaleEquals(this.filterLocale), nested) : nested;
			final Scope[] ownScope;
			if (this.rootScope == null && this.innerScope == null && this.random.nextInt(6) == 0) {
				ownScope = pick(this.random, SCOPE_SETS);
				this.innerScope = ownScope;
				this.features.add("hit:scopeInEntityHaving");
			} else {
				ownScope = null;
			}
			return new Node(
				io.evitadb.api.query.QueryConstraints.entityHaving(
					ownScope == null ? filter : io.evitadb.api.query.QueryConstraints.and(scope(ownScope), filter)
				),
				(context, ownerScope, owner, row) -> context.nested(
					TARGET, filter,
					ownScope == null ? (scopes == null ? context.examinedScopes : scopes) : Set.of(ownScope)
				).contains(row.getReferencedPrimaryKey())
			);
		}

		/**
		 * Creates a `groupHaving` node over the group collection: a row without a group, or with a dropped one,
		 * satisfies nothing.
		 *
		 * @param scopes the scopes the nested query runs in, NULL for the examined scopes of the request
		 * @return the node
		 */
		@Nonnull
		private Node groupHaving(@Nullable Set<Scope> scopes) {
			final FilterConstraint filter = switch (this.random.nextInt(4)) {
				case 0 -> attributeEquals(ATTR_CODE, "g" + (1 + this.random.nextInt(GROUP_COUNT)));
				case 1 -> entityPrimaryKeyInSet(randomKeys(GROUP_COUNT));
				case 2 -> {
					// a locale of the groups - not the locale of the filter, which never looks into `groupHaving`
					this.features.add("hit:localeInGroupHaving");
					yield entityLocaleEquals(pick(this.random, LOCALES));
				}
				default -> not(attributeInSet(ATTR_CODE, "g1", "g2"));
			};
			this.features.add("groupHaving");
			return new Node(
				io.evitadb.api.query.QueryConstraints.groupHaving(filter),
				(context, ownerScope, owner, row) -> {
					final Optional<GroupEntityReference> group = row.getGroup();
					if (group.isEmpty() || !group.get().exists()) {
						return false;
					}
					context.hit("groupHavingReached");
					return context.nested(GROUP, filter, scopes == null ? context.examinedScopes : scopes)
						.contains(group.get().getPrimaryKey());
				}
			);
		}

		/**
		 * Generates a filter over the target collection.
		 *
		 * @return the filter
		 */
		@Nonnull
		private FilterConstraint nestedTargetFilter() {
			return switch (this.random.nextInt(5)) {
				case 0 -> attributeEquals(ATTR_CODE, pick(this.random, CODES));
				case 1 -> attributeInSet(ATTR_CODE, pick(this.random, CODES), pick(this.random, CODES));
				case 2 -> attributeLessThan(ATTR_NUMBER, this.random.nextInt(5));
				case 3 -> entityPrimaryKeyInSet(randomKeys(TARGET_COUNT));
				default -> not(attributeEquals(ATTR_CODE, pick(this.random, CODES)));
			};
		}

		/**
		 * Returns one to three random primary keys up to the bound.
		 *
		 * @param bound the inclusive bound
		 * @return the keys
		 */
		@Nonnull
		private Integer[] randomKeys(int bound) {
			final Integer[] keys = new Integer[1 + this.random.nextInt(3)];
			for (int i = 0; i < keys.length; i++) {
				keys[i] = 1 + this.random.nextInt(bound);
			}
			return keys;
		}

		/**
		 * Generates a leaf of the row tree.
		 *
		 * @param scopes the scopes the leaf is compiled for, NULL for the examined scopes of the request
		 * @return the leaf
		 */
		@Nonnull
		private Node leaf(@Nullable Set<Scope> scopes) {
			if (this.keysOnly || this.random.nextInt(6) == 0) {
				final Integer[] keys = randomKeys(TARGET_COUNT);
				final Set<Integer> keySet = Set.of(Arrays.stream(keys).distinct().toArray(Integer[]::new));
				return new Node(
					entityPrimaryKeyInSet(keys),
					(context, ownerScope, owner, row) -> keySet.contains(row.getReferencedPrimaryKey())
				);
			}
			this.features.add("attributeLeaf");
			return switch (this.reference) {
				case REF_LIVE_ONLY -> switch (this.random.nextInt(11)) {
					case 0 -> integerLeaf(ATTR_INT);
					case 1 -> integerLeaf(ATTR_INT_RAW);
					case 2 -> decimalLeaf(ATTR_DEC);
					case 3 -> decimalLeaf(ATTR_DEC_RAW);
					case 4 -> stringLeaf(ATTR_STR, STR_VALUES, false);
					case 5 -> localizedLeaf(ATTR_LOC, LOC_VALUES, scopes);
					case 6 -> arrayLeaf();
					case 7, 8 -> validLeaf();
					default -> rangeLeaf();
				};
				case REF_BOTH -> switch (this.random.nextInt(5)) {
					case 0 -> integerLeaf(ATTR_INT);
					case 1, 4 -> localizedLeaf(ATTR_LOC, LOC_VALUES, scopes);
					case 2 -> {
						this.features.add("mixedTrait");
						yield localizedLeaf(
							ATTR_MIXED,
							this.mixedValues.isEmpty() ?
								new String[]{"none"} :
								new String[]{
									this.mixedValues.get(this.random.nextInt(this.mixedValues.size())),
									this.mixedValues.get(this.random.nextInt(this.mixedValues.size())),
									"none"
								},
							scopes
						);
					}
					default -> {
						this.features.add("mixedTrait");
						yield stringLeaf(ATTR_FILT_ONE, FILT_ONE_VALUES, false);
					}
				};
				case REF_DUPS -> this.random.nextBoolean() ?
					integerLeaf(ATTR_INT) : stringLeaf(ATTR_ROLE, ROLES, false);
				case REF_NOWHERE -> this.random.nextBoolean() ?
					integerLeaf(ATTR_INT) : stringLeaf(ATTR_STR, STR_VALUES, false);
				default -> integerLeaf(ATTR_WEIGHT);
			};
		}

		/**
		 * Generates a leaf on an `Integer` attribute - now and then an inverted interval, refused whatever the data.
		 *
		 * @param attribute the attribute
		 * @return the leaf
		 */
		@Nonnull
		private Node integerLeaf(@Nonnull String attribute) {
			final int value = this.random.nextInt(5);
			final int other = this.random.nextInt(5);
			if (this.random.nextInt(12) == 0 && value != other) {
				final int from = Math.max(value, other);
				final int to = Math.min(value, other);
				this.refusals.add(
					new Refusal(
						"R10", EvitaInvalidUsageException.class, (leafLocale, requestScopes, readingScopes) -> true,
						new String[]{"From must be lower than to: " + from + " vs. " + to}
					)
				);
				return new Node(attributeBetween(attribute, from, to), (context, ownerScope, owner, row) -> false);
			}
			return switch (this.random.nextInt(8)) {
				case 0 -> valueLeaf(attributeEquals(attribute, value), attribute, v -> ((Integer) v) == value);
				case 1 -> valueLeaf(
					attributeInSet(attribute, value, other), attribute,
					v -> ((Integer) v) == value || ((Integer) v) == other
				);
				case 2 -> valueLeaf(attributeLessThan(attribute, value), attribute, v -> ((Integer) v) < value);
				case 3 -> valueLeaf(attributeLessThanEquals(attribute, value), attribute, v -> ((Integer) v) <= value);
				case 4 -> valueLeaf(attributeGreaterThan(attribute, value), attribute, v -> ((Integer) v) > value);
				case 5 -> valueLeaf(
					attributeGreaterThanEquals(attribute, value), attribute, v -> ((Integer) v) >= value
				);
				case 6 -> {
					final int from = Math.min(value, other);
					final int to = Math.max(value, other);
					yield valueLeaf(
						attributeBetween(attribute, from, to), attribute,
						v -> ((Integer) v) >= from && ((Integer) v) <= to
					);
				}
				default -> presenceLeaf(attribute, null);
			};
		}

		/**
		 * Generates a leaf on a `BigDecimal` attribute with two indexed decimal places - compared scaled half-up.
		 *
		 * @param attribute the attribute
		 * @return the leaf
		 */
		@Nonnull
		private Node decimalLeaf(@Nonnull String attribute) {
			final BigDecimal value = pick(this.random, DEC_PROBES);
			final BigDecimal other = pick(this.random, DEC_PROBES);
			return switch (this.random.nextInt(5)) {
				case 0 -> valueLeaf(attributeEquals(attribute, value), attribute, v -> compare(v, value) == 0);
				case 1 -> valueLeaf(attributeLessThan(attribute, value), attribute, v -> compare(v, value) < 0);
				case 2 -> valueLeaf(attributeGreaterThan(attribute, value), attribute, v -> compare(v, value) > 0);
				case 3 -> {
					final BigDecimal from = value.min(other);
					final BigDecimal to = value.max(other);
					yield valueLeaf(
						attributeBetween(attribute, from, to), attribute,
						v -> compare(v, from) >= 0 && compare(v, to) <= 0
					);
				}
				default -> presenceLeaf(attribute, null);
			};
		}

		/**
		 * Compares two decimals the way the index does - scaled half-up to two places.
		 *
		 * @param value the value of the row
		 * @param probe the value of the constraint
		 * @return the result of the comparison
		 */
		private static int compare(@Nonnull Serializable value, @Nonnull BigDecimal probe) {
			return ((BigDecimal) value).setScale(2, RoundingMode.HALF_UP)
				.compareTo(probe.setScale(2, RoundingMode.HALF_UP));
		}

		/**
		 * Generates a leaf on a string attribute. The comparisons order the decomposed strings naturally, or - for
		 * a localized attribute - by the collator of the leaf locale, with ties broken naturally.
		 *
		 * @param attribute the attribute
		 * @param values    the values to probe with
		 * @param localized TRUE when the attribute is localized
		 * @return the leaf
		 */
		@Nonnull
		private Node stringLeaf(@Nonnull String attribute, @Nonnull String[] values, boolean localized) {
			final String value = this.random.nextInt(6) == 0 ? "zz" : pick(this.random, values);
			final String other = pick(this.random, values);
			final String prefix = value.substring(0, 1);
			final Map<Scope, Boolean> anyLocale = localized ? anyLocaleReadings(attribute) : null;
			// a localized attribute is compared more often - the collator of the leaf locale orders it
			final int choice = localized && this.random.nextBoolean() ?
				5 + this.random.nextInt(3) : this.random.nextInt(9);
			return switch (choice) {
				case 0 -> valueLeaf(
					attributeEquals(attribute, value), attribute, anyLocale, v -> nfd(v).equals(nfd(value))
				);
				case 1 -> valueLeaf(
					attributeInSet(attribute, value, other), attribute, anyLocale,
					v -> nfd(v).equals(nfd(value)) || nfd(v).equals(nfd(other))
				);
				case 2 -> valueLeaf(
					attributeStartsWith(attribute, prefix), attribute, v -> nfd(v).startsWith(nfd(prefix))
				);
				case 3 -> valueLeaf(attributeEndsWith(attribute, prefix), attribute, v -> nfd(v).endsWith(nfd(prefix)));
				case 4 -> valueLeaf(attributeContains(attribute, prefix), attribute, v -> nfd(v).contains(nfd(prefix)));
				case 5 -> comparisonLeaf(attributeLessThan(attribute, value), attribute, localized, null, value);
				case 6 -> comparisonLeaf(attributeGreaterThan(attribute, value), attribute, localized, value, null);
				case 7 -> {
					// the interval may come out inverted under the order the leaf compiles with - refused then (R10)
					this.refusals.add(
						new Refusal(
							"R10", EvitaInvalidUsageException.class,
							(leafLocale, requestScopes, readingScopes) ->
								order(localized ? leafLocale : null).compare(nfd(value), nfd(other)) > 0,
							new String[]{"From must be lower than to"}
						)
					);
					yield comparisonLeaf(attributeBetween(attribute, value, other), attribute, localized, value, other);
				}
				default -> presenceLeaf(attribute, anyLocale);
			};
		}

		/**
		 * Creates a comparison of a string leaf: `attributeLessThan` keeps a value below the upper bound,
		 * `attributeGreaterThan` above the lower one, `attributeBetween` within both, inclusive.
		 *
		 * @param constraint the constraint
		 * @param attribute  the attribute
		 * @param localized  TRUE when the attribute is localized
		 * @param lower      the lower bound, NULL for none
		 * @param upper      the upper bound, NULL for none
		 * @return the leaf
		 */
		@Nonnull
		private Node comparisonLeaf(
			@Nonnull FilterConstraint constraint,
			@Nonnull String attribute,
			boolean localized,
			@Nullable String lower,
			@Nullable String upper
		) {
			this.features.add("collationLeaf");
			final boolean inclusive = lower != null && upper != null;
			return valueLeaf(
				constraint, attribute, null,
				(context, v) -> {
					final Comparator<String> order = order(localized ? context.leafLocale : null);
					final String value = nfd(v);
					if (localized && context.leafLocale != null && !value.chars().allMatch(ch -> ch < 128)) {
						context.hit("collation");
					}
					final int toLower = lower == null ? 1 : order.compare(value, nfd(lower));
					final int toUpper = upper == null ? -1 : order.compare(value, nfd(upper));
					return inclusive ? toLower >= 0 && toUpper <= 0 : toLower > 0 && toUpper < 0;
				}
			);
		}

		/**
		 * Returns the order strings are compared in - decomposed, by the collator of the locale with ties broken
		 * naturally, or naturally without a locale.
		 *
		 * @param locale the locale, NULL for none
		 * @return the order
		 */
		@Nonnull
		private static Comparator<String> order(@Nullable Locale locale) {
			if (locale == null) {
				return Comparator.naturalOrder();
			}
			final Collator collator = Collator.getInstance(locale);
			return (first, second) -> {
				final int collated = collator.compare(first, second);
				return collated != 0 ? collated : first.compareTo(second);
			};
		}

		/**
		 * Generates a leaf on a localized string attribute - only where the case has a leaf locale, unless the
		 * attribute is unique across locales in LIVE. The leaf is refused without a locale, unless the attribute is
		 * unique across locales in LIVE and LIVE is a scope the leaf is compiled for.
		 *
		 * @param attribute the attribute
		 * @param values    the values to probe with
		 * @param scopes    the scopes the leaf is compiled for, NULL for the reading scopes of the fetch
		 * @return the leaf
		 */
		@Nonnull
		private Node localizedLeaf(@Nonnull String attribute, @Nonnull String[] values, @Nullable Set<Scope> scopes) {
			if (this.leafLocale == null && !(ATTR_MIXED.equals(attribute) && this.random.nextInt(4) > 0)) {
				// a case refused for the lack of a locale now and then, mostly an answerable one
				if (this.random.nextInt(8) != 0) {
					return integerLeafOf(this.reference);
				}
			}
			this.localizedAttribute = attribute;
			this.refusals.add(
				new Refusal(
					"locale", EntityLocaleMissingException.class,
					(leafLocale, requestScopes, readingScopes) -> leafLocale == null && !(
						ATTR_MIXED.equals(attribute) && (scopes == null ? readingScopes : scopes).contains(Scope.LIVE)
					),
					new String[]{"`" + attribute + "`"}
				)
			);
			return stringLeaf(attribute, values, true);
		}

		/**
		 * Returns an `Integer` leaf of the reference.
		 *
		 * @param referenceName the reference
		 * @return the leaf
		 */
		@Nonnull
		private Node integerLeafOf(@Nonnull String referenceName) {
			return integerLeaf(REF_REFLECTED.equals(referenceName) ? ATTR_WEIGHT : ATTR_INT);
		}

		/**
		 * Returns, per scope, whether the attribute is read in any locale by `attributeEquals`, `attributeInSet` and
		 * `attributeIs` - where it is unique across locales.
		 *
		 * @param attribute the attribute
		 * @return TRUE for the scopes reading any locale
		 */
		@Nonnull
		private Map<Scope, Boolean> anyLocaleReadings(@Nonnull String attribute) {
			final Map<Scope, Boolean> result = new EnumMap<>(Scope.class);
			result.put(Scope.LIVE, this.unboundInLive.getOrDefault(attribute, false));
			result.put(Scope.ARCHIVED, false);
			return result;
		}

		/**
		 * Generates a leaf on the array attribute - any element counts.
		 *
		 * @return the leaf
		 */
		@Nonnull
		private Node arrayLeaf() {
			final String value = pick(this.random, ARR_ELEMENTS);
			final String other = pick(this.random, ARR_ELEMENTS);
			return switch (this.random.nextInt(4)) {
				case 0 -> valueLeaf(attributeEquals(ATTR_ARR, value), ATTR_ARR, v -> v.equals(value));
				case 1 -> valueLeaf(
					attributeInSet(ATTR_ARR, value, other), ATTR_ARR, v -> v.equals(value) || v.equals(other)
				);
				case 2 -> valueLeaf(
					attributeStartsWith(ATTR_ARR, value), ATTR_ARR, v -> ((String) v).startsWith(value)
				);
				default -> presenceLeaf(ATTR_ARR, null);
			};
		}

		/**
		 * Generates a leaf on the `IntegerNumberRange` attribute - the overlap for `attributeBetween` (nothing for
		 * an inverted interval), the envelope for `attributeInRange`.
		 *
		 * @return the leaf
		 */
		@Nonnull
		private Node rangeLeaf() {
			final int value = this.random.nextInt(7);
			final int other = this.random.nextInt(7);
			return switch (this.random.nextInt(3)) {
				case 0 -> {
					// the two constraints reading the borders of the ranges, which the known issue #1699 miscounts
					this.features.add("rangeAxisLeaf:" + ATTR_RANGE);
					final boolean inverted = this.random.nextBoolean() && value != other;
					final int from = inverted ? Math.max(value, other) : Math.min(value, other);
					final int to = inverted ? Math.min(value, other) : Math.max(value, other);
					if (inverted) {
						this.features.add("hit:invertedBetweenRange");
					}
					yield valueLeaf(
						attributeBetween(ATTR_RANGE, from, to), ATTR_RANGE,
						v -> from <= to && ((IntegerNumberRange) v).getPreciseFrom() <= to &&
							((IntegerNumberRange) v).getPreciseTo() >= from
					);
				}
				case 1 -> {
					this.features.add("rangeAxisLeaf:" + ATTR_RANGE);
					yield valueLeaf(
						attributeInRange(ATTR_RANGE, value), ATTR_RANGE,
						v -> ((IntegerNumberRange) v).getPreciseFrom() <= value &&
							((IntegerNumberRange) v).getPreciseTo() >= value
					);
				}
				default -> presenceLeaf(ATTR_RANGE, null);
			};
		}

		/**
		 * Generates a leaf on the `DateTimeRange` attribute - an open end reaching infinity: the envelope of a moment
		 * or of now, the overlap with an interval (open-ended now and then, nothing when inverted).
		 *
		 * @return the leaf
		 */
		@Nonnull
		private Node validLeaf() {
			final OffsetDateTime value = pick(this.random, VALID_PROBES);
			final OffsetDateTime other = pick(this.random, VALID_PROBES);
			return switch (this.random.nextInt(4)) {
				case 0 -> {
					this.features.add("rangeAxisLeaf:" + ATTR_VALID);
					yield validLeaf(attributeInRange(ATTR_VALID, value), value, value);
				}
				case 1 -> {
					this.features.add("rangeAxisLeaf:" + ATTR_VALID);
					this.features.add("hit:dateTimeRangeNow");
					final OffsetDateTime now = OffsetDateTime.now();
					yield validLeaf(attributeInRangeNow(ATTR_VALID), now, now);
				}
				case 2 -> {
					this.features.add("rangeAxisLeaf:" + ATTR_VALID);
					final boolean inverted = this.random.nextBoolean() && !value.equals(other);
					final OffsetDateTime earlier = value.isBefore(other) ? value : other;
					final OffsetDateTime later = value.isBefore(other) ? other : value;
					final OffsetDateTime from = inverted ? later : (this.random.nextInt(4) == 0 ? null : earlier);
					final OffsetDateTime to = inverted ?
						earlier : (from != null && this.random.nextInt(4) == 0 ? null : later);
					if (inverted) {
						this.features.add("hit:invertedBetweenRange");
						yield new Node(
							attributeBetween(ATTR_VALID, from, to), (context, ownerScope, owner, row) -> false
						);
					}
					yield validLeaf(attributeBetween(ATTR_VALID, from, to), to, from);
				}
				default -> presenceLeaf(ATTR_VALID, null);
			};
		}

		/**
		 * Creates a leaf on `valid`: its range starts no later than `upTo` and ends no earlier than `from` - an open
		 * end reaching infinity, a NULL bound bounding nothing.
		 *
		 * @param constraint the constraint
		 * @param upTo       the moment the range must start by, NULL for any
		 * @param from       the moment the range must end after, NULL for any
		 * @return the leaf
		 */
		@Nonnull
		private Node validLeaf(
			@Nonnull FilterConstraint constraint,
			@Nullable OffsetDateTime upTo,
			@Nullable OffsetDateTime from
		) {
			return valueLeaf(
				constraint, ATTR_VALID, null,
				(context, v) -> {
					final DateTimeRange range = (DateTimeRange) v;
					final boolean matches = (upTo == null || range.getPreciseFrom() == null ||
						!range.getPreciseFrom().toInstant().isAfter(upTo.toInstant())) &&
						(from == null || range.getPreciseTo() == null ||
							!range.getPreciseTo().toInstant().isBefore(from.toInstant()));
					if (matches && (range.getPreciseFrom() == null || range.getPreciseTo() == null)) {
						context.hit("openEndedRange");
					}
					return matches;
				}
			);
		}

		/**
		 * Creates a value leaf read in the leaf locale where the attribute is localized.
		 *
		 * @param constraint the constraint
		 * @param attribute  the attribute
		 * @param test       the test of one value (one element of an array)
		 * @return the leaf
		 */
		@Nonnull
		private Node valueLeaf(
			@Nonnull FilterConstraint constraint,
			@Nonnull String attribute,
			@Nonnull Predicate<Serializable> test
		) {
			return valueLeaf(constraint, attribute, null, (context, value) -> test.test(value));
		}

		/**
		 * Creates a value leaf.
		 *
		 * @param constraint the constraint
		 * @param attribute  the attribute
		 * @param anyLocale  per scope, TRUE to read the localized attribute in any locale; NULL for the leaf locale
		 *                   everywhere (or a non-localized attribute)
		 * @param test       the test of one value (one element of an array)
		 * @return the leaf
		 */
		@Nonnull
		private Node valueLeaf(
			@Nonnull FilterConstraint constraint,
			@Nonnull String attribute,
			@Nullable Map<Scope, Boolean> anyLocale,
			@Nonnull Predicate<Serializable> test
		) {
			return valueLeaf(constraint, attribute, anyLocale, (context, value) -> test.test(value));
		}

		/**
		 * Creates a value leaf whose test reads the context of the evaluation.
		 *
		 * @param constraint the constraint
		 * @param attribute  the attribute
		 * @param anyLocale  per scope, TRUE to read the localized attribute in any locale; NULL for the leaf locale
		 *                   everywhere (or a non-localized attribute)
		 * @param test       the test of one value (one element of an array)
		 * @return the leaf
		 */
		@Nonnull
		private static Node valueLeaf(
			@Nonnull FilterConstraint constraint,
			@Nonnull String attribute,
			@Nullable Map<Scope, Boolean> anyLocale,
			@Nonnull BiFunction<OracleContext, Serializable, Boolean> test
		) {
			final boolean localized = ATTR_LOC.equals(attribute) || ATTR_MIXED.equals(attribute);
			return new Node(
				constraint,
				(context, ownerScope, owner, row) -> {
					for (
						Serializable value : values(context, ownerScope, row, attribute, localized, anyLocale, false)
					) {
						if (value instanceof Object[] array) {
							for (Object element : array) {
								if (test.apply(context, (Serializable) element)) {
									return true;
								}
							}
						} else if (test.apply(context, value)) {
							return true;
						}
					}
					return false;
				}
			);
		}

		/**
		 * Creates an `attributeIs` leaf - an empty array and a dropped value are no value.
		 *
		 * @param attribute the attribute
		 * @param anyLocale per scope, TRUE to read the localized attribute in any locale; NULL for the leaf locale
		 * @return the leaf
		 */
		@Nonnull
		private Node presenceLeaf(@Nonnull String attribute, @Nullable Map<Scope, Boolean> anyLocale) {
			final boolean present = this.random.nextBoolean();
			final boolean localized = ATTR_LOC.equals(attribute) || ATTR_MIXED.equals(attribute);
			return new Node(
				present ? attributeIsNotNull(attribute) : attributeIsNull(attribute),
				(context, ownerScope, owner, row) -> {
					boolean hasValue = false;
					for (Serializable value : values(context, ownerScope, row, attribute, localized, anyLocale, true)) {
						if (!(value instanceof Object[] array && array.length == 0)) {
							hasValue = true;
						}
					}
					return hasValue == present;
				}
			);
		}

		/**
		 * Reads the existing values of an attribute of a row the leaf reads in the scope of the owner.
		 *
		 * @param context    the context of the evaluation
		 * @param ownerScope the scope of the owner
		 * @param row        the row
		 * @param attribute  the attribute
		 * @param localized  TRUE for a localized attribute
		 * @param anyLocale  per scope, TRUE to read any locale; NULL for the leaf locale
		 * @param presence   TRUE for `attributeIs`, which reads any locale when no leaf locale is known
		 * @return the values
		 */
		@Nonnull
		private static List<Serializable> values(
			@Nonnull OracleContext context,
			@Nonnull Scope ownerScope,
			@Nonnull ReferenceContract row,
			@Nonnull String attribute,
			boolean localized,
			@Nullable Map<Scope, Boolean> anyLocale,
			boolean presence
		) {
			final List<Serializable> result = new ArrayList<>(2);
			if (!localized) {
				addExisting(result, row.getAttributeValue(attribute));
			} else if (
				(anyLocale != null && anyLocale.get(ownerScope)) || (presence && context.leafLocale == null)
			) {
				for (Locale locale : LOCALES) {
					addExisting(result, row.getAttributeValue(attribute, locale));
				}
			} else if (context.leafLocale != null) {
				addExisting(result, row.getAttributeValue(attribute, context.leafLocale));
			}
			return result;
		}

		/**
		 * Adds the value unless it is absent or dropped.
		 *
		 * @param values the values
		 * @param value  the value
		 */
		private static void addExisting(@Nonnull List<Serializable> values, @Nonnull Optional<AttributeValue> value) {
			if (value.isPresent() && value.get().exists() && value.get().value() != null) {
				values.add(value.get().value());
			}
		}

		/**
		 * Returns the string decomposed - the form the index compares strings in.
		 *
		 * @param value the string
		 * @return the decomposed string
		 */
		@Nonnull
		private static String nfd(@Nonnull Serializable value) {
			return Normalizer.normalize((String) value, Normalizer.Form.NFD);
		}

	}

}
