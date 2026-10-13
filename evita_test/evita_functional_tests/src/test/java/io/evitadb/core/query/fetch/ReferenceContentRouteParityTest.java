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
import io.evitadb.api.exception.EntityLocaleMissingException;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.require.ManagedReferencesBehaviour;
import io.evitadb.api.query.require.ReferenceContent;
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
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.test.EvitaTestSupport;
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
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiPredicate;
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
 * meaning every constraint has on a single reference row: it reads the raw owners (`entityFetchAllContent()`, the
 * delegate of the decorator), answers `entityHaving` and `groupHaving` by public queries over the target and group
 * collections in each scope the node is evaluated in, the existence of a target by a public query over the examined
 * scopes, compares a `BigDecimal` scaled half-up to the indexed decimal places, a string decomposed (NFD), an array by
 * any element and a range by the overlap or the envelope of its bounds - and reads a localized attribute in each owner
 * scope by that scope's declaration: in the locale of the leaf where the attribute is bound to a locale, in any locale
 * for `attributeEquals`, `attributeInSet` and `attributeIs` where it is unique across locales.
 *
 * ## The fixture
 *
 * `rpOwner` (12 owners, 9 to 12 archived; locales EN and DE) references `rpTarget` (targets 1 to 10, 9 and 10
 * archived, 11 and 12 missing; titles in EN, odd targets in DE too) grouped by `rpGroup` (groups 1 to 4, 5 archived, 6
 * missing) through
 *
 * - `liveOnly` - indexed in LIVE: `int`, `dec` (`BigDecimal`, two places) and `str` filterable, `intRaw`, `decRaw`
 *   (beyond the int range too) and `arr` (an array, also empty) not, `loc` localized and filterable, `range` an
 *   `IntegerNumberRange`,
 * - `both` - indexed in both scopes: `int`, `loc`, `mixed` (localized, unique across locales in LIVE and within
 *   a locale in ARCHIVED) and `filtOne` (filterable in LIVE only),
 * - `dups` - duplicates told apart by `role`, indexed in LIVE, with `int`,
 * - `nowhere` - indexed in no scope, with `int` and `str`,
 * - `reflected` - the reflection of `rpTarget.ownerLinks`, with the inherited `weight`.
 *
 * The rows, their groups and attribute values are generated from a fixed seed; some groups and attribute values are
 * dropped afterwards. The filters are generated from a seed that is logged and can be set by the system property
 * {@link #SEED_PROPERTY}; {@link #ITERATIONS_PROPERTY} sets their number.
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
	private static final int DEFAULT_ITERATIONS = 400;
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
	private static final String ATTR_CODE = "code";
	private static final String ATTR_NUMBER = "number";
	private static final String ATTR_TITLE = "title";
	private static final String ATTR_NAME = "name";
	private static final String ATTR_INT = "int";
	private static final String ATTR_INT_RAW = "intRaw";
	private static final String ATTR_DEC = "dec";
	private static final String ATTR_DEC_RAW = "decRaw";
	private static final String ATTR_STR = "str";
	private static final String ATTR_LOC = "loc";
	private static final String ATTR_ARR = "arr";
	private static final String ATTR_RANGE = "range";
	private static final String ATTR_MIXED = "mixed";
	private static final String ATTR_FILT_ONE = "filtOne";
	private static final String ATTR_ROLE = "role";
	private static final String ATTR_WEIGHT = "weight";
	private static final Locale[] LOCALES = {Locale.ENGLISH, Locale.GERMAN};
	private static final Scope[] LIVE = {Scope.LIVE};
	private static final Scope[] ARCHIVED = {Scope.ARCHIVED};
	private static final Scope[] BOTH = {Scope.LIVE, Scope.ARCHIVED};
	private static final Scope[][] SCOPE_SETS = {LIVE, ARCHIVED, BOTH};
	private static final int OWNER_COUNT = 12;
	private static final int[] ARCHIVED_OWNERS = {9, 10, 11, 12};
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
	private static final String[] STR_VALUES = {"a", "ab", "b", "ba"};
	private static final String[] LOC_VALUES = {"x", "xy", "y"};
	private static final String[] ARR_ELEMENTS = {"p", "q", "r"};
	private static final String[] FILT_ONE_VALUES = {"f1", "f2"};
	private static final String[] ROLES = {"a", "b", "c"};
	private static final String[] CODES = {"a", "b", "c"};

	private TestPaths paths;
	private Evita evita;
	/**
	 * The raw owners - the delegates of the decorators, carrying every row, every attribute of every locale.
	 */
	private Map<Integer, Entity> owners;
	/**
	 * Every value of `mixed` the fixture holds, so that the generated filters hit some.
	 */
	private final List<String> mixedValues = new ArrayList<>(64);
	/**
	 * The targets whose `liveOnly` rows ever indexed in LIVE carry two different `range` values sharing a border -
	 * the shape of the known issue #1699 (see {@link #explain}). Every row of the fixture was written in LIVE, so the
	 * values of archived owners and the dropped values count as well: their removal strips the shared border from
	 * the surviving range.
	 */
	private final Set<Integer> sharedBorderTargets = CollectionUtils.createHashSet(16);

	/**
	 * Builds the fixture described on the class and reads the raw owners for the oracle.
	 */
	@BeforeAll
	void setUp() {
		this.paths = createTestPaths("ReferenceContentRouteParityTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		this.evita.updateCatalog(CATALOG, this::createFixture);
		this.evita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
		this.evita.updateCatalog(CATALOG, ReferenceContentRouteParityTest::dropSomeData);
		this.owners = this.evita.queryCatalog(
			CATALOG,
			session -> {
				final Map<Integer, Entity> result = new TreeMap<>();
				for (int pk = 1; pk <= OWNER_COUNT; pk++) {
					final SealedEntity owner = session.getEntity(OWNER, pk, BOTH, entityFetchAllContent())
						.orElseThrow();
					result.put(pk, ((EntityDecorator) owner).getDelegate());
				}
				return result;
			}
		);
		final Map<Integer, Set<IntegerNumberRange>> rangesByTarget = CollectionUtils.createHashMap(16);
		for (Entity owner : this.owners.values()) {
			for (ReferenceContract row : owner.getReferences(REF_LIVE_ONLY)) {
				final Optional<AttributeValue> range = row.getAttributeValue(ATTR_RANGE);
				if (range.isPresent() && range.get().value() instanceof IntegerNumberRange value) {
					rangesByTarget
						.computeIfAbsent(row.getReferencedPrimaryKey(), it -> CollectionUtils.createHashSet(4))
						.add(value);
				}
			}
		}
		rangesByTarget.forEach(
			(target, ranges) -> {
				if (shareBorder(ranges)) {
					this.sharedBorderTargets.add(target);
				}
			}
		);
	}

	/**
	 * Tells whether two different ranges of the set share their start or their end.
	 *
	 * @param ranges the distinct ranges
	 * @return TRUE when two of them share a border
	 */
	private static boolean shareBorder(@Nonnull Set<IntegerNumberRange> ranges) {
		for (IntegerNumberRange first : ranges) {
			for (IntegerNumberRange second : ranges) {
				if (
					!first.equals(second) &&
						(first.getFrom() == second.getFrom() || first.getTo() == second.getTo())
				) {
					return true;
				}
			}
		}
		return false;
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
			.withAttribute(ATTR_CODE, String.class, thatIs -> thatIs.filterableInScope(BOTH))
			.updateVia(session);
		for (int pk = 1; pk <= CREATED_GROUP_COUNT; pk++) {
			session.upsertEntity(session.createNewEntity(GROUP, pk).setAttribute(ATTR_CODE, "g" + pk));
		}
		session.archiveEntity(GROUP, CREATED_GROUP_COUNT);

		session.defineEntitySchema(TARGET)
			.withoutGeneratedPrimaryKey()
			.withLocale(LOCALES)
			.withAttribute(ATTR_CODE, String.class, thatIs -> thatIs.filterableInScope(BOTH))
			.withAttribute(ATTR_NUMBER, Integer.class, thatIs -> thatIs.filterableInScope(BOTH))
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
					}
				);
			}
			final Set<String> duplicates = CollectionUtils.createHashSet(8);
			for (int i = random.nextInt(5); i > 0; i--) {
				final int target = 1 + random.nextInt(TARGET_COUNT);
				final String role = pick(random, ROLES);
				if (duplicates.add(target + "/" + role)) {
					owner.setOrUpdateReference(
						REF_DUPS, target, Functions.alwaysFalse(),
						whichIs -> {
							setGroup(random, whichIs);
							whichIs.setAttribute(ATTR_ROLE, role);
							setMaybe(random, () -> whichIs.setAttribute(ATTR_INT, pick(random, INT_VALUES)));
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
		final Set<Integer> targets = new java.util.TreeSet<>();
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
		final long seed = Long.getLong(SEED_PROPERTY, System.nanoTime());
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
		assertTrue(statistics.rejectedRows > 0, "The premise: the body route rejects some rows");
		assertTrue(statistics.differences > 0, "The premise: the routes differ somewhere");
	}

	/**
	 * Runs one case through the query (one page over every owner in the scopes) and through `getEntity` (one owner,
	 * looked up in both scopes, no locale), on both routes, and checks the outcomes.
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
		final FilterBy filterBy = theCase.filterBy();
		final FilterBy withoutNotApplicable = theCase.withoutNotApplicable();
		final Outcome bodyQuery = onRoute(ReferenceContentRoute.BODY, () -> fetchByQuery(theCase, filterBy));
		final Outcome indexQuery = onRoute(ReferenceContentRoute.INDEX, () -> fetchByQuery(theCase, filterBy));
		if (bodyQuery.failure() != null && bodyQuery.matches(indexQuery)) {
			// both routes share the planning check, which compiles the filter of the body route - so a refusal of
			// the check is a refusal of both, and like any other answer of the body route it must be the oracle's
			statistics.refusedAlikeByBoth++;
		}
		final Outcome expectedOfQuery = oracle(
			theCase, theCase.requestScopes(), theCase.queryLocale(), bodyQuery.ownerPks()
		);
		check(
			theCase, "query", theCase.requestScopes(), theCase.queryLocale(), expectedOfQuery, bodyQuery, indexQuery,
			withoutNotApplicable == null ?
				null :
				() -> onRoute(ReferenceContentRoute.INDEX, () -> fetchByQuery(theCase, withoutNotApplicable)),
			existingRowCount(theCase.reference(), bodyQuery.ownerPks()), this::rawRows, statistics, failures
		);
		final int owner = 1 + random.nextInt(OWNER_COUNT);
		final Outcome bodyEntity = onRoute(
			ReferenceContentRoute.BODY, () -> fetchByGetEntity(theCase, filterBy, owner)
		);
		final Outcome indexEntity = onRoute(
			ReferenceContentRoute.INDEX, () -> fetchByGetEntity(theCase, filterBy, owner)
		);
		final Outcome expected = oracle(theCase, BOTH, null, Set.of(owner));
		check(
			theCase, "getEntity of " + owner, BOTH, null, expected, bodyEntity, indexEntity,
			withoutNotApplicable == null ?
				null :
				() -> onRoute(
					ReferenceContentRoute.INDEX, () -> fetchByGetEntity(theCase, withoutNotApplicable, owner)
				),
			existingRowCount(theCase.reference(), Set.of(owner)), this::rawRows, statistics, failures
		);
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
	 * Returns the number of existing rows of the reference the owners hold.
	 *
	 * @param reference the reference
	 * @param ownerPks  the owners
	 * @return the number of rows
	 */
	private int existingRowCount(@Nonnull String reference, @Nonnull Set<Integer> ownerPks) {
		int count = 0;
		for (int ownerPk : ownerPks) {
			for (ReferenceContract row : this.owners.get(ownerPk).getReferences(reference)) {
				if (row.exists()) {
					count++;
				}
			}
		}
		return count;
	}

	/**
	 * Compares the body route with the oracle, and the index route with the body route.
	 *
	 * @param theCase       the case
	 * @param path          names the path in the failure messages
	 * @param requestScopes the scopes of the request
	 * @param queryLocale   the locale of the request, NULL for none
	 * @param expected      the outcome the oracle expects
	 * @param body          the outcome of the body route
	 * @param index         the outcome of the index route
	 * @param indexWithoutNotApplicable runs the index route without the container the query model drops as not
	 *                      applicable, NULL when the filter holds none
	 * @param existingRows  the number of existing rows of the owners
	 * @param rawRows       describes the raw rows of the owners for the failure messages
	 * @param statistics    the statistics to update
	 * @param failures      the failures to add to
	 */
	private void check(
		@Nonnull Case theCase,
		@Nonnull String path,
		@Nonnull Scope[] requestScopes,
		@Nullable Locale queryLocale,
		@Nonnull Outcome expected,
		@Nonnull Outcome body,
		@Nonnull Outcome index,
		@Nullable Supplier<Outcome> indexWithoutNotApplicable,
		int existingRows,
		@Nonnull java.util.function.BiFunction<String, Set<Integer>, String> rawRows,
		@Nonnull Statistics statistics,
		@Nonnull List<String> failures
	) {
		if (!expected.matches(body)) {
			failures.add(theCase + " / " + path + ": the body route answered " + body + ", the oracle " + expected);
			return;
		}
		statistics.count(body, existingRows);
		if (!body.matches(index)) {
			final Set<String> classes = indexWithoutNotApplicable != null &&
				indexWithoutNotApplicable.get().matches(body) ?
				// the index route reads an empty container not normalized away as "nothing", the row filter skips it
				Set.of("1.2 not applicable container skipped") :
				explain(theCase, requestScopes, queryLocale, body, index);
			if (classes.isEmpty()) {
				failures.add(
					theCase + " / " + path + ": the index route answered " + index + ", the body route " + body +
						" - in no decided class; rows " + rawRows.apply(theCase.reference(), body.ownerPks())
				);
			} else {
				statistics.differences++;
				for (String decidedClass : classes) {
					statistics.differencesByClass.merge(decidedClass, 1, Integer::sum);
				}
			}
		}
	}

	/**
	 * Returns the decided classes that may explain a difference between the routes - the features of the case that
	 * the routes were decided to treat differently.
	 *
	 * @param theCase       the case
	 * @param requestScopes the scopes of the request
	 * @param queryLocale   the locale of the request, NULL for none
	 * @param body          the outcome of the body route
	 * @param index         the outcome of the index route
	 * @return the classes, empty when nothing explains the difference
	 */
	@Nonnull
	private Set<String> explain(
		@Nonnull Case theCase,
		@Nonnull Scope[] requestScopes,
		@Nullable Locale queryLocale,
		@Nonnull Outcome body,
		@Nonnull Outcome index
	) {
		final Set<String> classes = new java.util.TreeSet<>();
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
		if (!theCase.indexedInAll(theCase.examinedScopes(requestScopes))) {
			classes.add("U1/D3 scope not indexing the reference");
		}
		if (theCase.rootGroupHaving()) {
			classes.add("D1/R5 root groupHaving per row");
		}
		if (theCase.features().contains("rangeAxisLeaf") && differInSharedBorderTargetsOnly(body, index)) {
			// the known issue #1699, outside this pull request: the reference type index keys a range by the
			// partition, so two owners' ranges of one target sharing a border miscount - and a removal strips the
			// border from the survivor; `referenceHaving` of the main query answers wrong the same way
			classes.add("known #1699: ranges of one partition sharing a border");
		}
		if (theCase.filterLocale() != null && theCase.localizedLeaf() &&
			!theCase.filterLocale().equals(queryLocale)) {
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
	 * Tells whether every row the routes disagree on references a target whose ranges share a border - see
	 * {@link #sharedBorderTargets}.
	 *
	 * @param body  the outcome of the body route
	 * @param index the outcome of the index route
	 * @return TRUE when the differing rows all reference such a target
	 */
	private boolean differInSharedBorderTargetsOnly(@Nonnull Outcome body, @Nonnull Outcome index) {
		if (body.rows() == null || index.rows() == null || !body.rows().keySet().equals(index.rows().keySet())) {
			return false;
		}
		for (Entry<Integer, List<String>> entry : body.rows().entrySet()) {
			final Set<String> differing = new HashSet<>(entry.getValue());
			final List<String> indexRows = index.rows().get(entry.getKey());
			differing.addAll(indexRows);
			final Set<String> common = new HashSet<>(entry.getValue());
			common.retainAll(indexRows);
			differing.removeAll(common);
			for (String row : differing) {
				if (!this.sharedBorderTargets.contains(Integer.parseInt(row.split("/")[0]))) {
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
	 * Fetches every owner in the scopes of the case, in its locale, with the references the filter keeps.
	 *
	 * @param theCase  the case
	 * @param filterBy the filter of the references
	 * @return the kept rows by owner
	 */
	@Nonnull
	private Outcome fetchByQuery(@Nonnull Case theCase, @Nonnull FilterBy filterBy) {
		return this.evita.queryCatalog(
			CATALOG,
			session -> {
				final FilterConstraint[] ownerFilter = theCase.queryLocale() == null ?
					new FilterConstraint[]{scope(theCase.requestScopes())} :
					new FilterConstraint[]{scope(theCase.requestScopes()), entityLocaleEquals(theCase.queryLocale())};
				final Map<Integer, List<String>> rows = new TreeMap<>();
				for (SealedEntity owner : session.queryListOfSealedEntities(
					query(
						collection(OWNER),
						filterBy(ownerFilter),
						require(page(1, 100), entityFetch(theCase.referenceContent(filterBy)))
					)
				)) {
					rows.put(owner.getPrimaryKeyOrThrowException(), describe(owner.getReferences(theCase.reference())));
				}
				return Outcome.of(rows);
			}
		);
	}

	/**
	 * Fetches one owner, looked up in both scopes, with the references the filter keeps.
	 *
	 * @param theCase  the case
	 * @param filterBy the filter of the references
	 * @param owner    the primary key of the owner
	 * @return the kept rows
	 */
	@Nonnull
	private Outcome fetchByGetEntity(@Nonnull Case theCase, @Nonnull FilterBy filterBy, int owner) {
		return this.evita.queryCatalog(
			CATALOG,
			session -> {
				final SealedEntity entity = session.getEntity(OWNER, owner, BOTH, theCase.referenceContent(filterBy))
					.orElseThrow();
				final Map<Integer, List<String>> rows = new TreeMap<>();
				rows.put(owner, describe(entity.getReferences(theCase.reference())));
				return Outcome.of(rows);
			}
		);
	}

	/**
	 * Returns what the oracle expects for the owners.
	 *
	 * @param theCase       the case
	 * @param requestScopes the scopes of the request
	 * @param queryLocale   the locale of the request, NULL for none
	 * @param ownerPks      the primary keys of the owners the request returns
	 * @return the expected outcome
	 */
	@Nonnull
	private Outcome oracle(
		@Nonnull Case theCase,
		@Nonnull Scope[] requestScopes,
		@Nullable Locale queryLocale,
		@Nonnull Set<Integer> ownerPks
	) {
		final Locale leafLocale = theCase.filterLocale() == null ? queryLocale : theCase.filterLocale();
		if (theCase.refusedWithoutLocale().test(leafLocale, EnumSet.copyOf(Arrays.asList(requestScopes)))) {
			return Outcome.failed(new EntityLocaleMissingException(theCase.localizedAttribute()));
		}
		final Set<Scope> examinedScopes = theCase.examinedScopes(requestScopes);
		return this.evita.queryCatalog(
			CATALOG,
			session -> {
				final OracleContext context = new OracleContext(session, leafLocale, examinedScopes);
				// a missing or archived target cannot answer a condition - only the ones existing in the examined
				// scopes are judged, in the locale of the bodies under EXISTING
				final Locale requiredLocale = theCase.managedReferences() == ManagedReferencesBehaviour.EXISTING ?
					leafLocale : null;
				final Set<Integer> existing = context.existingTargets(requiredLocale);
				final Map<Integer, List<String>> rows = new TreeMap<>();
				for (int ownerPk : ownerPks) {
					final Entity owner = this.owners.get(ownerPk);
					final List<ReferenceContract> kept = new ArrayList<>(8);
					for (ReferenceContract row : owner.getReferences(theCase.reference())) {
						if (
							row.exists() && existing.contains(row.getReferencedPrimaryKey()) &&
								theCase.root().oracle().test(context, owner.getScope(), owner, row)
						) {
							kept.add(row);
						}
					}
					rows.put(ownerPk, describe(kept));
				}
				return Outcome.of(rows);
			}
		);
	}

	/**
	 * Describes the rows, sorted: `target`, or `target/role` for `dups`.
	 *
	 * @param references the rows
	 * @return the descriptions
	 */
	@Nonnull
	private static List<String> describe(@Nonnull Iterable<ReferenceContract> references) {
		final List<String> result = new ArrayList<>(8);
		for (ReferenceContract reference : references) {
			result.add(
				REF_DUPS.equals(reference.getReferenceName()) ?
					reference.getReferencedPrimaryKey() + "/" + reference.getAttribute(ATTR_ROLE) :
					String.valueOf(reference.getReferencedPrimaryKey())
			);
		}
		result.sort(null);
		return result;
	}

	/**
	 * The outcome of a fetch: the kept rows by owner, or the failure.
	 *
	 * @param rows    the kept rows by owner, NULL when the fetch failed
	 * @param failure the simple name of the exception class followed by its message, NULL when the fetch succeeded
	 */
	private record Outcome(@Nullable Map<Integer, List<String>> rows, @Nullable String failure) {

		/**
		 * Creates a successful outcome.
		 *
		 * @param rows the kept rows by owner
		 * @return the outcome
		 */
		@Nonnull
		static Outcome of(@Nonnull Map<Integer, List<String>> rows) {
			return new Outcome(rows, null);
		}

		/**
		 * Creates a failed outcome.
		 *
		 * @param exception the exception
		 * @return the outcome
		 */
		@Nonnull
		static Outcome failed(@Nonnull Throwable exception) {
			return new Outcome(null, exception.getClass().getSimpleName() + ": " + exception.getMessage());
		}

		/**
		 * Tells whether the other outcome is the same - the same rows, or a failure of the same exception class (the
		 * messages of the routes and of the oracle differ).
		 *
		 * @param other the other outcome
		 * @return TRUE when they are the same
		 */
		boolean matches(@Nonnull Outcome other) {
			if (this.failure == null || other.failure == null) {
				return this.failure == null && other.failure == null && this.rows.equals(other.rows);
			}
			return failureClass(this.failure).equals(failureClass(other.failure));
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

	}

	/**
	 * Counts what the sweep saw, to prove it is not vacuous.
	 */
	private static final class Statistics {
		int cases;
		int failedAlike;
		int refusedAlikeByBoth;
		int admittedRows;
		int rejectedRows;
		int differences;
		final Map<String, Integer> differencesByClass = new TreeMap<>();

		/**
		 * Counts an outcome of the body route equal to the oracle.
		 *
		 * @param body         the outcome
		 * @param existingRows the number of existing rows of the owners
		 */
		void count(@Nonnull Outcome body, int existingRows) {
			this.cases++;
			if (body.rows() == null) {
				this.failedAlike++;
			} else {
				int admitted = 0;
				for (List<String> rows : body.rows().values()) {
					admitted += rows.size();
				}
				this.admittedRows += admitted;
				this.rejectedRows += existingRows - admitted;
			}
		}

		@Override
		public String toString() {
			return "checked " + this.cases + " outcomes (" + this.failedAlike + " refusals predicted), " +
				this.refusedAlikeByBoth + " queries refused alike by both routes, " +
				this.admittedRows + " rows admitted, " + this.rejectedRows + " rows rejected, " +
				this.differences + " differences from the index route: " + this.differencesByClass;
		}
	}

	/**
	 * A generated case - one filter of one reference, run in some scopes, locale and managed-references behaviour.
	 *
	 * @param index                the number of the case
	 * @param reference            the reference
	 * @param requestScopes        the scopes of the query
	 * @param queryLocale          the locale of the query, NULL for none
	 * @param managedReferences    ANY or EXISTING
	 * @param root                 the root of the filter and its oracle
	 * @param rootScope            the scopes of the root `scope(...)`, NULL when there is none
	 * @param filterLocale         the locale of the filter's own `entityLocaleEquals`, NULL when there is none
	 * @param features             the features the generator put in the filter
	 * @param localizedAttribute   the localized attribute a leaf of the filter reads, NULL when there is none
	 * @param withoutNotApplicable the filter without the container the query model drops as not applicable, NULL when
	 *                             it holds none
	 * @param refusedWithoutLocale tells whether the filter is refused for the leaf locale and the request scopes
	 */
	private record Case(
		int index,
		@Nonnull String reference,
		@Nonnull Scope[] requestScopes,
		@Nullable Locale queryLocale,
		@Nonnull ManagedReferencesBehaviour managedReferences,
		@Nonnull Node root,
		@Nullable Scope[] rootScope,
		@Nullable Locale filterLocale,
		@Nonnull Set<String> features,
		@Nullable String localizedAttribute,
		@Nullable FilterBy withoutNotApplicable,
		@Nonnull BiPredicate<Locale, Set<Scope>> refusedWithoutLocale
	) {

		/**
		 * Returns the filter of the case.
		 *
		 * @return the filter
		 */
		@Nonnull
		FilterBy filterBy() {
			return (FilterBy) java.util.Objects.requireNonNull(this.root.constraint());
		}

		/**
		 * Returns the requirement fetching the reference with the filter.
		 *
		 * @param filterBy the filter
		 * @return the requirement
		 */
		@Nonnull
		ReferenceContent referenceContent(@Nonnull FilterBy filterBy) {
			return new ReferenceContent(
				null, this.managedReferences, new String[]{this.reference},
				new RequireConstraint[]{attributeContentAll()},
				new Constraint<?>[]{filterBy}
			);
		}

		/**
		 * Returns the scopes the references are looked up in.
		 *
		 * @param requestScopes the scopes of the request
		 * @return the scopes of the request and of the root `scope(...)`
		 */
		@Nonnull
		Set<Scope> examinedScopes(@Nonnull Scope[] requestScopes) {
			final EnumSet<Scope> result = EnumSet.copyOf(Arrays.asList(requestScopes));
			if (this.rootScope != null) {
				result.addAll(Arrays.asList(this.rootScope));
			}
			return result;
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

		@Nonnull
		@Override
		public String toString() {
			return "case " + this.index + " [" + this.reference + " in " + Arrays.toString(this.requestScopes) +
				", locale " + this.queryLocale + ", " + this.managedReferences + "]: " + this.root.constraint();
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
	 * and the examined scopes, and the memo of the nested queries.
	 */
	private static final class OracleContext {
		private final EvitaSessionContract session;
		@Nullable private final Locale leafLocale;
		private final Set<Scope> examinedScopes;
		private final Map<String, Set<Integer>> nestedMemo = CollectionUtils.createHashMap(16);

		/**
		 * Creates the context of one evaluation.
		 *
		 * @param session        the session the nested queries run in
		 * @param leafLocale     the locale the localized attribute leaves read, NULL when none is known
		 * @param examinedScopes the scopes the references are looked up in
		 */
		OracleContext(
			@Nonnull EvitaSessionContract session,
			@Nullable Locale leafLocale,
			@Nonnull Set<Scope> examinedScopes
		) {
			this.session = session;
			this.leafLocale = leafLocale;
			this.examinedScopes = examinedScopes;
		}

		/**
		 * Returns the targets existing in the examined scopes, in the locale when one is passed.
		 *
		 * @param locale the locale the targets must have data in, NULL for any
		 * @return the primary keys
		 */
		@Nonnull
		Set<Integer> existingTargets(@Nullable Locale locale) {
			final Scope[] scopes = this.examinedScopes.toArray(Scope[]::new);
			return nested(
				TARGET,
				locale == null ? filterBy(scope(scopes)) : filterBy(scope(scopes), entityLocaleEquals(locale)),
				"existing " + locale + " " + this.examinedScopes
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
	 * Generates one case. Every filter holds at most one `entityLocaleEquals` outside `groupHaving`, placed bare in the
	 * root, in an `inScope` of the root, in an `entityHaving` or under an `or`.
	 */
	private static final class CaseGenerator {
		private final Random random;
		private final List<String> mixedValues;
		private String reference;
		private Scope[] requestScopes;
		@Nullable private Locale filterLocale;
		@Nullable private Locale leafLocale;
		private final Set<String> features = CollectionUtils.createHashSet(8);
		@Nullable private String localizedAttribute;
		/**
		 * The localized leaves of the filter - the attribute and the scopes it is compiled for, NULL for the examined
		 * scopes of the request.
		 */
		private final List<Entry<String, Set<Scope>>> localizedLeaves = new ArrayList<>(4);
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
			this.reference = pick(this.random, REFERENCES);
			this.requestScopes = pick(this.random, SCOPE_SETS);
			final Locale queryLocale = this.random.nextInt(3) == 0 ? null : pick(this.random, LOCALES);
			final ManagedReferencesBehaviour managedReferences = this.random.nextInt(4) == 0 ?
				ManagedReferencesBehaviour.EXISTING : ManagedReferencesBehaviour.ANY;
			final int localePlacement = this.random.nextInt(10);
			this.filterLocale = localePlacement < 4 ? pick(this.random, LOCALES) : null;
			this.leafLocale = this.filterLocale == null ? queryLocale : this.filterLocale;

			final Scope[] rootScope = this.random.nextInt(6) == 0 ? pick(this.random, SCOPE_SETS) : null;
			final List<FilterConstraint> rootConstraints = new ArrayList<>(4);
			final List<RowOracle> rootOracles = new ArrayList<>(4);
			if (rootScope != null) {
				rootConstraints.add(scope(rootScope));
			}
			if (this.filterLocale != null) {
				final Node localeNode = switch (localePlacement) {
					case 0 -> ownerLocale(this.filterLocale);
					case 1 -> {
						final Scope inScope = pick(this.random, this.requestScopes);
						yield inScope(inScope, List.of(ownerLocale(this.filterLocale)));
					}
					case 2 -> entityHaving(null, true);
					default -> or(List.of(ownerLocale(this.filterLocale), node(2, null)));
				};
				rootConstraints.add(localeNode.constraint());
				rootOracles.add(localeNode.oracle());
			}
			final int rowNodes = 1 + this.random.nextInt(2);
			boolean groupHaving = false;
			for (int i = 0; i < rowNodes; i++) {
				final Node node = node(1, null);
				if (node.constraint() instanceof io.evitadb.api.query.filter.GroupHaving) {
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
			final FilterBy withoutNotApplicable;
			if (this.random.nextInt(20) == 0) {
				// a container the query model drops as not applicable
				withoutNotApplicable = new FilterBy(rootConstraints.toArray(FilterConstraint[]::new));
				rootConstraints.add(io.evitadb.api.query.QueryConstraints.and());
			} else {
				withoutNotApplicable = null;
			}
			final Node root = new Node(
				new FilterBy(rootConstraints.toArray(FilterConstraint[]::new)),
				(context, ownerScope, owner, row) -> {
					for (RowOracle oracle : rootOracles) {
						if (!oracle.test(context, ownerScope, owner, row)) {
							return false;
						}
					}
					return true;
				}
			);
			final List<Entry<String, Set<Scope>>> localizedLeaves = new ArrayList<>(this.localizedLeaves);
			final String localizedAttribute = this.localizedAttribute;
			return new Case(
				index, this.reference, this.requestScopes, queryLocale, managedReferences, root, rootScope,
				this.filterLocale, Set.copyOf(this.features), localizedAttribute,
				withoutNotApplicable,
				(leafLocale, requestScopes) -> {
					if (leafLocale != null || localizedAttribute == null) {
						return false;
					}
					for (Entry<String, Set<Scope>> leaf : localizedLeaves) {
						// a localized leaf without a locale is admitted only when its attribute is unique across
						// locales in a scope it is compiled for - `mixed` in LIVE; NULL stands for the examined scopes
						final Set<Scope> compiledFor = leaf.getValue() == null ?
							examinedOf(requestScopes, rootScope) : leaf.getValue();
						if (!(ATTR_MIXED.equals(leaf.getKey()) && compiledFor.contains(Scope.LIVE))) {
							return true;
						}
					}
					return false;
				}
			);
		}

		/**
		 * Returns the examined scopes of a request.
		 *
		 * @param requestScopes the scopes of the request
		 * @param rootScope     the scopes of the root `scope(...)`, NULL for none
		 * @return the scopes
		 */
		@Nonnull
		private static Set<Scope> examinedOf(@Nonnull Set<Scope> requestScopes, @Nullable Scope[] rootScope) {
			final EnumSet<Scope> result = EnumSet.copyOf(requestScopes);
			if (rootScope != null) {
				result.addAll(Arrays.asList(rootScope));
			}
			return result;
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
			if (depth >= 3 || choice < 50) {
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
				return inScope(inScope, children(depth, EnumSet.of(inScope)));
			} else if (choice < 93 || REF_NOWHERE.equals(this.reference) || REF_REFLECTED.equals(this.reference)) {
				return entityHaving(scopes, false);
			} else {
				return groupHaving(scopes);
			}
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
		 * Creates an `entityHaving` node over the target collection.
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
			return new Node(
				io.evitadb.api.query.QueryConstraints.entityHaving(filter),
				(context, ownerScope, owner, row) -> context.nested(
					TARGET, filter, scopes == null ? context.examinedScopes : scopes
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
			final FilterConstraint filter = switch (this.random.nextInt(3)) {
				case 0 -> attributeEquals(ATTR_CODE, "g" + (1 + this.random.nextInt(GROUP_COUNT)));
				case 1 -> entityPrimaryKeyInSet(randomKeys(GROUP_COUNT));
				default -> not(attributeInSet(ATTR_CODE, "g1", "g2"));
			};
			return new Node(
				io.evitadb.api.query.QueryConstraints.groupHaving(filter),
				(context, ownerScope, owner, row) -> {
					final Optional<GroupEntityReference> group = row.getGroup();
					return group.isPresent() && group.get().exists() &&
						context.nested(GROUP, filter, scopes == null ? context.examinedScopes : scopes)
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
			if (this.random.nextInt(6) == 0) {
				final Integer[] keys = randomKeys(TARGET_COUNT);
				final Set<Integer> keySet = Set.of(Arrays.stream(keys).distinct().toArray(Integer[]::new));
				return new Node(
					entityPrimaryKeyInSet(keys),
					(context, ownerScope, owner, row) -> keySet.contains(row.getReferencedPrimaryKey())
				);
			}
			return switch (this.reference) {
				case REF_LIVE_ONLY -> switch (this.random.nextInt(8)) {
					case 0 -> integerLeaf(ATTR_INT);
					case 1 -> integerLeaf(ATTR_INT_RAW);
					case 2 -> decimalLeaf(ATTR_DEC);
					case 3 -> decimalLeaf(ATTR_DEC_RAW);
					case 4 -> stringLeaf(ATTR_STR, STR_VALUES, false);
					case 5 -> localizedLeaf(ATTR_LOC, LOC_VALUES, scopes);
					case 6 -> arrayLeaf();
					default -> rangeLeaf();
				};
				case REF_BOTH -> switch (this.random.nextInt(4)) {
					case 0 -> integerLeaf(ATTR_INT);
					case 1 -> localizedLeaf(ATTR_LOC, LOC_VALUES, scopes);
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
		 * Generates a leaf on an `Integer` attribute.
		 *
		 * @param attribute the attribute
		 * @return the leaf
		 */
		@Nonnull
		private Node integerLeaf(@Nonnull String attribute) {
			final int value = this.random.nextInt(5);
			final int other = this.random.nextInt(5);
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
		 * Generates a leaf on a non-localized string attribute.
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
			return switch (this.random.nextInt(6)) {
				case 0 -> valueLeaf(
					attributeEquals(attribute, value), attribute, anyLocale, v -> nfd(v).equals(nfd(value))
				);
				case 1 -> valueLeaf(
					attributeInSet(attribute, value, other), attribute, anyLocale,
					v -> nfd(v).equals(nfd(value)) || nfd(v).equals(nfd(other))
				);
				case 2 -> valueLeaf(attributeStartsWith(attribute, prefix), attribute, v -> nfd(v).startsWith(prefix));
				case 3 -> valueLeaf(attributeEndsWith(attribute, prefix), attribute, v -> nfd(v).endsWith(prefix));
				case 4 -> valueLeaf(attributeContains(attribute, prefix), attribute, v -> nfd(v).contains(prefix));
				default -> presenceLeaf(attribute, anyLocale);
			};
		}

		/**
		 * Generates a leaf on a localized string attribute - only where the case has a leaf locale, unless the
		 * attribute is unique across locales in LIVE.
		 *
		 * @param attribute the attribute
		 * @param values    the values to probe with
		 * @param scopes    the scopes the leaf is compiled for, NULL for the examined scopes of the request
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
			this.localizedLeaves.add(new java.util.AbstractMap.SimpleEntry<>(attribute, scopes));
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
		 * Generates a leaf on the range attribute - the overlap for `attributeBetween`, the envelope for
		 * `attributeInRange`.
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
					this.features.add("rangeAxisLeaf");
					final int from = Math.min(value, other);
					final int to = Math.max(value, other);
					yield valueLeaf(
						attributeBetween(ATTR_RANGE, from, to), ATTR_RANGE,
						v -> ((IntegerNumberRange) v).getPreciseFrom() <= to &&
							((IntegerNumberRange) v).getPreciseTo() >= from
					);
				}
				case 1 -> {
					this.features.add("rangeAxisLeaf");
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
			@Nonnull java.util.function.Predicate<Serializable> test
		) {
			return valueLeaf(constraint, attribute, null, test);
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
			@Nonnull java.util.function.Predicate<Serializable> test
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
								if (test.test((Serializable) element)) {
									return true;
								}
							}
						} else if (test.test(value)) {
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
