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

package io.evitadb.api.functional;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.core.Evita;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.HistogramCapableEntityIndex;
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks writes of an entity that references itself, so the reflected reference work a write generates returns to
 * the entity being written. Three families cover it: the primary reference, its mirror and the mirror's histogram
 * owner through insertion, update and removal; reference rows and histogram owners through lifecycles that include
 * other owners and deletion; and scope changes and ranked writes together with their reference partitions. Every
 * observation is repeated after a restart and must match the state seen before it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Primary reference storage during reflected reference writes")
@Tag(CONTRACT)
@Tag(REFERENCE)
@Tag(HISTOGRAM)
class SelfReflectedReferenceWriteFunctionalTest implements EvitaTestSupport {
	/**
	 * The only entity type; its members reference each other and themselves.
	 */
	private static final String MEMBER = "member";
	/**
	 * Primary reference from a member to a member.
	 */
	private static final String PRIMARY = "primary";
	/**
	 * Reflected counterpart of {@link #PRIMARY}, which the engine maintains on the referenced member.
	 */
	private static final String REFLECTED = "reflected";
	/**
	 * Name of the histogram that buckets the referenced member's size.
	 */
	private static final String VALUES = "values";
	/**
	 * Scopes every read covers, so an archived member stays visible.
	 */
	private static final Scope[] BOTH_SCOPES = {Scope.LIVE, Scope.ARCHIVED};

	/**
	 * Supplies the self-reflected update sequences in both catalog states.
	 *
	 * @return update sequences to exercise
	 */
	@Nonnull
	static Stream<Arguments> writes() {
		return catalogStates().flatMap(state -> Stream.of(
			Arguments.of(state, true, true),
			Arguments.of(state, false, false),
			Arguments.of(state, false, true)
		));
	}

	/**
	 * Checks insertion, update and removal of the primary and its mirror.
	 *
	 * @param state     catalog lifecycle state
	 * @param histogram whether the reflected reference has a conditional histogram
	 * @param split     whether source and level are updated separately
	 */
	@ParameterizedTest(name = "{0} histogram={1} split={2}")
	@MethodSource("writes")
	@DisplayName("retains the primary reference and its updated attribute")
	void shouldRetainPrimaryReferenceWhenReflectionWritesSameEntity(
		@Nonnull CatalogState state,
		boolean histogram,
		boolean split
	) {
		try (final SelfReferenceFixture fixture = new SelfReferenceFixture(state, histogram, split)) {
			fixture.run();
			assertAll(fixture.checks);
		}
	}

	/**
	 * Exercises a self-reference whose inherited level changes alongside a value source.
	 */
	private final class SelfReferenceFixture implements AutoCloseable {
		private final CatalogState state;
		/**
		 * Whether the reflected reference has a histogram admitted by the reference level.
		 */
		private final boolean histogram;
		/**
		 * Whether the size and the reference level are updated by separate writes.
		 */
		private final boolean split;
		private final TestPaths paths;
		private final EvitaConfiguration configuration;
		/**
		 * Deferred assertions, run together once the scenario ends so that one failure does not hide the others.
		 */
		private final List<Executable> checks = new ArrayList<>();
		private Evita evita;

		/**
		 * Creates a fresh catalog fixture for the chosen update sequence.
		 *
		 * @param state     catalog lifecycle state
		 * @param histogram whether the reflected reference has a conditional histogram
		 * @param split     whether source and level are updated separately
		 */
		private SelfReferenceFixture(@Nonnull CatalogState state, boolean histogram, boolean split) {
			this.state = state;
			this.histogram = histogram;
			this.split = split;
			this.paths = createTestPaths("selfReflected_" + state + "_" + histogram + "_" + split);
			this.configuration = newTestEvitaConfigurationBuilder(this.paths).build();
			this.evita = new Evita(this.configuration);
		}

		/**
		 * Inserts, updates and removes a self-reference, checking the body before and after restart.
		 */
		private void run() {
			this.evita.defineCatalog(TEST_CATALOG);
			this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.defineEntitySchema(MEMBER).withoutGeneratedPrimaryKey()
					.withAttribute("size", Integer.class, attr -> attr.filterable().nullable())
					.withReferenceToEntity(PRIMARY, MEMBER, Cardinality.ZERO_OR_MORE, ref -> ref
						.indexedForFiltering()
						.withAttribute("level", Integer.class, attr -> attr.filterable().nullable())
					).updateVia(session);
				session.defineEntitySchema(MEMBER)
					.withReflectedReferenceToEntity(REFLECTED, MEMBER, PRIMARY, ref -> {
						ref.withCardinality(Cardinality.ZERO_OR_MORE)
							.withAttributesInherited().indexedForFiltering().faceted();
						if (this.histogram) {
							ref.bucketed(
								VALUES, ExpressionFactory.parse("$reference.referencedEntity.attributes['size']")
							).bucketedPartially(ExpressionFactory.parse("$reference.attributes['level'] > 0"));
						}
					}).updateVia(session);
				if (this.state == CatalogState.ALIVE) {
					session.goLiveAndClose();
				}
			});
			this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.createNewEntity(MEMBER, 1).setAttribute("size", 30).upsertVia(session);
			});
			this.evita.updateCatalog(TEST_CATALOG, SelfReflectedReferenceWriteFunctionalTest::setPrimary);
			final SelfReferenceState inserted = record(30, 1, "insert");
			// Reassertion keeps the follow-up checks independent of a failed initial insert.
			this.evita.updateCatalog(TEST_CATALOG, SelfReflectedReferenceWriteFunctionalTest::setPrimary);
			final SelfReferenceState reasserted = record(30, 1, "reasserted");
			this.checks.add(() -> assertEquals(inserted, reasserted, "reassertion preserves reference IDs"));
			this.evita = restart(this.evita, this.configuration);
			final SelfReferenceState reloaded = record(30, 1, "reasserted and restarted");
			this.checks.add(() -> assertEquals(reasserted, reloaded, "insert durability"));

			final UpsertAttributeMutation sizeUpdate = new UpsertAttributeMutation("size", 5);
			final ReferenceAttributeMutation levelUpdate = level(PRIMARY, 1, 2);
			if (this.split) {
				apply(this.evita, "size update", session -> upsert(session, 1, sizeUpdate), this.checks);
				final SelfReferenceState sized = record(5, 1, "size updated");
				this.evita = restart(this.evita, this.configuration);
				final SelfReferenceState sizedRestarted = record(5, 1, "size updated and restarted");
				this.checks.add(() -> assertEquals(sized, sizedRestarted, "size update durability"));
			}
			apply(this.evita, "primary level update", session -> {
				if (this.split) {
					upsert(session, 1, levelUpdate);
				} else {
					upsert(session, 1, sizeUpdate, levelUpdate);
				}
			}, this.checks);
			final SelfReferenceState updated = record(5, 2, "updated");
			this.checks.add(() -> assertEquals(reloaded.primaryId(), updated.primaryId(), "updated primary ID"));
			this.checks.add(() -> assertEquals(reloaded.reflectedId(), updated.reflectedId(), "updated mirror ID"));
			this.evita = restart(this.evita, this.configuration);
			final SelfReferenceState updatedRestarted = record(5, 2, "updated and restarted");
			this.checks.add(() -> assertEquals(updated, updatedRestarted, "update durability"));

			apply(this.evita, "primary removal", session -> upsert(
				session, 1, new RemoveReferenceMutation(new ReferenceKey(PRIMARY, 1))
			), this.checks);
			final SelfReferenceState removed = record(5, null, "removed");
			this.evita = restart(this.evita, this.configuration);
			final SelfReferenceState removedRestarted = record(5, null, "removed and restarted");
			this.checks.add(() -> assertEquals(removed, removedRestarted, "removal durability"));
		}

		/**
		 * Captures the body, both reference IDs and the reflected histogram, and checks them against the expected
		 * state.
		 *
		 * @param size  expected member size
		 * @param level expected reference level, or null when the rows should be absent
		 * @param stage operation being checked
		 * @return current stored state
		 */
		@Nonnull
		private SelfReferenceState record(int size, @Nullable Integer level, @Nonnull String stage) {
			final SelfReferenceState actual = this.evita.queryCatalog(TEST_CATALOG, session -> {
				final SealedEntity source = member(session, 1);
				final ReferenceContract primary = source.getReference(PRIMARY, 1).orElse(null);
				final ReferenceContract mirror = source.getReference(REFLECTED, 1).orElse(null);
				return new SelfReferenceState(
					source.getScope(), source.getAttribute("size"),
					primary == null ? null : primary.getAttribute("level"),
					mirror == null ? null : mirror.getAttribute("level"),
					primary == null ? null : primary.getReferenceKey().internalPrimaryKey(),
					mirror == null ? null : mirror.getReferenceKey().internalPrimaryKey()
				);
			});
			this.checks.add(() -> assertEquals(Scope.LIVE, actual.scope(), stage + " body scope"));
			this.checks.add(() -> assertEquals(size, actual.size(), stage + " size"));
			this.checks.add(() -> assertEquals(level, actual.primaryLevel(), stage + " primary level"));
			this.checks.add(() -> assertEquals(level, actual.reflectedLevel(), stage + " mirror level"));
			if (level == null) {
				this.checks.add(() -> assertNull(actual.primaryId(), stage + " primary absent"));
				this.checks.add(() -> assertNull(actual.reflectedId(), stage + " mirror absent"));
			} else {
				this.checks.add(() -> assertTrue(
					actual.primaryId() != null && actual.primaryId() > 0, stage + " primary ID"
				));
				this.checks.add(() -> assertEquals(
					actual.primaryId(), actual.reflectedId(), stage + " counterpart ID"
				));
			}
			// while the rows exist, the mirror counts its owner in the bucket of the owner's own size
			final Map<Scope, Map<Integer, Set<Integer>>> expectedHistogram = emptyHistograms();
			if (this.histogram && level != null) {
				expectedHistogram.get(Scope.LIVE).put(size, Set.of(1));
			}
			final Map<Scope, Map<Integer, Set<Integer>>> histogram = readHistograms(this.evita, REFLECTED);
			this.checks.add(() -> assertEquals(expectedHistogram, histogram, stage + " histogram"));
			return actual;
		}

		/**
		 * Closes the database and releases its test paths.
		 */
		@Override
		public void close() {
			closeAndCleanUp(this.evita, this.paths);
		}
	}

	/**
	 * Inserts or reasserts the primary reference with its initial level.
	 *
	 * @param session catalog session
	 */
	private static void setPrimary(@Nonnull EvitaSessionContract session) {
		insertWithLevel(session, 1);
	}

	/**
	 * Inserts a primary reference of the first member with its initial level.
	 *
	 * @param session catalog session
	 * @param target  referenced member primary key
	 * @return upsert result
	 */
	@Nonnull
	private static Object insertWithLevel(@Nonnull EvitaSessionContract session, int target) {
		return member(session, 1).openForWrite()
			.setReference(PRIMARY, target, ref -> ref.setAttribute("level", 1)).upsertVia(session);
	}

	/**
	 * Records a refused write and lets the remaining storage checks run.
	 *
	 * @param evita  database instance
	 * @param stage  operation being checked
	 * @param write  write to execute
	 * @param checks deferred assertions
	 */
	private static void apply(
		@Nonnull Evita evita,
		@Nonnull String stage,
		@Nonnull Consumer<EvitaSessionContract> write,
		@Nonnull List<Executable> checks
	) {
		try {
			evita.updateCatalog(TEST_CATALOG, write);
		} catch (final RuntimeException ex) {
			checks.add(() -> fail(stage + " must succeed", ex));
		}
	}

	/**
	 * Reopens the database and waits for its catalog to be readable.
	 *
	 * @param evita         open database instance
	 * @param configuration configuration for the reopened database
	 * @return reopened database
	 */
	@Nonnull
	private static Evita restart(@Nonnull Evita evita, @Nonnull EvitaConfiguration configuration) {
		evita.close();
		final Evita reopened = new Evita(configuration);
		reopened.waitUntilFullyInitialized();
		return reopened;
	}

	/**
	 * Stored owner scope, source value and reference rows at one observation point.
	 */
	private record SelfReferenceState(
		@Nonnull Scope scope,
		@Nullable Integer size,
		@Nullable Integer primaryLevel,
		@Nullable Integer reflectedLevel,
		@Nullable Integer primaryId,
		@Nullable Integer reflectedId
	) {
	}

	/**
	 * Reference mutations whose implicit work returns to the same entity.
	 */
	private enum Scenario {
		INSERT_REMOVE_REINSERT,
		REPRESENTATIVE_ATTRIBUTE,
		DELETE_WHILE_LIVE,
		ARRAY_THROUGH_OTHER_OWNER,
		TWO_UPSERTS
	}

	/**
	 * Supplies the reflected lifecycle cases in both catalog states.
	 *
	 * @return parameterized lifecycle cases
	 */
	@Nonnull
	static Stream<Arguments> lifecycleWrites() {
		return catalogStates().flatMap(state ->
			Arrays.stream(Scenario.values()).flatMap(scenario ->
				switch (scenario) {
					case INSERT_REMOVE_REINSERT, DELETE_WHILE_LIVE ->
						Stream.of(Arguments.of(state, scenario, true), Arguments.of(state, scenario, false));
					case REPRESENTATIVE_ATTRIBUTE, ARRAY_THROUGH_OTHER_OWNER, TWO_UPSERTS ->
						Stream.of(Arguments.of(state, scenario, true));
				}
			)
		);
	}

	/**
	 * Checks stored references and histogram owners throughout a lifecycle.
	 *
	 * @param state     catalog lifecycle state
	 * @param scenario  write sequence to exercise
	 * @param histogram whether the reflected reference is bucketed
	 */
	@ParameterizedTest(name = "{0} {1} histogram={2}")
	@MethodSource("lifecycleWrites")
	@DisplayName("preserves reference bodies and histogram owners across writes and restart")
	void shouldPreserveReferenceBodiesAndHistogramOwners(
		@Nonnull CatalogState state,
		@Nonnull Scenario scenario,
		boolean histogram
	) {
		try (final ReferenceFixture fixture = new ReferenceFixture(state, scenario, histogram)) {
			fixture.run();
			assertAll(fixture.checks);
		}
	}

	/**
	 * Primary edge with the level expected on both sides.
	 */
	private record Link(int source, int target, int level) {}

	/**
	 * Stored reference, including its assigned internal primary key.
	 */
	private record ReferenceBody(String name, int target, int id, Integer level) {
		/**
		 * Formats the reference or entity values used in semantic assertions.
		 *
		 * @return formatted comparison values
		 */
		@Nonnull
		private String content() {
			return this.name + "->" + this.target + " level=" + this.level;
		}
	}

	/**
	 * Stored scope, value source and reference rows for one member.
	 */
	private record MemberBody(Scope scope, Integer size, List<Integer> sizes, List<ReferenceBody> references) {}

	/**
	 * Member bodies and histogram owners at one observation point.
	 */
	private record Observation(Map<Integer, MemberBody> bodies, Map<Scope, Map<Integer, Set<Integer>>> histograms) {}

	/**
	 * Keeps storage and index observations for every operation, including refused operations. Expected links describe
	 * primary edges and generate the corresponding reflected edges and histogram owners independently of stored data.
	 */
	private final class ReferenceFixture implements AutoCloseable {
		private final CatalogState state;
		private final Scenario scenario;
		/**
		 * Whether the reflected reference is bucketed.
		 */
		private final boolean histogram;
		/**
		 * Number of members the scenario creates, numbered from one.
		 */
		private final int memberCount;
		private final TestPaths paths;
		private final EvitaConfiguration configuration;
		/**
		 * Deferred assertions, run together once the scenario ends so that one failure does not hide the others.
		 */
		private final List<Executable> checks = new ArrayList<>();
		/**
		 * Expected scope of every member that still exists; it decides the expected bodies and which links reflect.
		 */
		private final Map<Integer, Scope> scopes = new TreeMap<>();
		/**
		 * Expected histogram source values of every member.
		 */
		private final Map<Integer, List<Integer>> values = new TreeMap<>();
		/**
		 * Self-reference of the first member that most scenarios seed.
		 */
		private final Link firstLink;
		private Evita evita;
		/**
		 * Observation of the current step before the restart, compared with the one after it; null until it is taken.
		 */
		@Nullable private Observation stored;

		/**
		 * Creates a catalog fixture for a reference lifecycle.
		 *
		 * @param state     catalog lifecycle state
		 * @param scenario  write sequence to exercise
		 * @param histogram whether the reflected reference is bucketed
		 */
		private ReferenceFixture(@Nonnull CatalogState state, @Nonnull Scenario scenario, boolean histogram) {
			this.state = state;
			this.scenario = scenario;
			this.histogram = histogram;
			this.memberCount = scenario == Scenario.ARRAY_THROUGH_OTHER_OWNER ? 3 : 2;
			this.firstLink = new Link(1, 1, 1);
			this.paths = createTestPaths("reflectedWrites_" + state + "_" + scenario + "_" + histogram);
			this.configuration = newTestEvitaConfigurationBuilder(this.paths).build();
			this.evita = new Evita(this.configuration);
		}

		/**
		 * Runs the lifecycle scenario and records each stored and restarted state.
		 */
		private void run() {
			this.evita.defineCatalog(TEST_CATALOG);
			step("define schema", session -> {
				defineSchema(session);
				return "defined";
			});
			for (int id = 1; id <= this.memberCount; id++) {
				this.scopes.put(id, Scope.LIVE);
				this.values.put(id,
					this.scenario == Scenario.ARRAY_THROUGH_OTHER_OWNER ? List.of(10, 20) : List.of(30));
			}
			step("create members", session -> {
				for (final Map.Entry<Integer, Scope> member : this.scopes.entrySet()) {
					createMember(session, member.getKey()).upsertVia(session);
				}
				return this.scopes.keySet().toString();
			});

			switch (this.scenario) {
				case INSERT_REMOVE_REINSERT -> {
					seed();
					step("remove primary", session -> upsert(session, 1,
						new RemoveReferenceMutation(new ReferenceKey(PRIMARY, 1))));
					insert("reinsert primary", new Link(1, 1, 2));
				}
				case REPRESENTATIVE_ATTRIBUTE -> {
					seed();
					step("change representative level", session -> upsert(session, 1,
						new ReferenceAttributeMutation(
							member(session, 1).getReferences(PRIMARY, 1).stream().findFirst()
								.map(ReferenceContract::getReferenceKey)
								.orElseGet(() -> new ReferenceKey(PRIMARY, 1)),
							new UpsertAttributeMutation("level", 2)
						)), new Link(1, 1, 2));
				}
				case DELETE_WHILE_LIVE -> {
					seed();
					this.scopes.remove(1);
					step("delete live member", session -> session.deleteEntity(MEMBER, 1));
				}
				case ARRAY_THROUGH_OTHER_OWNER -> {
					final Link otherOwner = new Link(1, 2, 1);
					step("add other histogram owner", session -> writePrimary(session, otherOwner), otherOwner);
					step("add second histogram owner", session -> writePrimary(session, this.firstLink),
						otherOwner, this.firstLink);
					step("reassert second histogram owner", session -> writePrimary(session, this.firstLink),
						otherOwner, this.firstLink);
					this.values.put(1, List.of(20, 40));
					step("change array source and reference level", session -> upsert(session, 1,
						new UpsertAttributeMutation("sizes", new Integer[]{20, 40}), level(PRIMARY, 1, 2)),
						otherOwner, new Link(1, 1, 2));
					this.values.put(1, List.of(40, 50));
					step("change array source alone", session -> upsert(session, 1,
						new UpsertAttributeMutation("sizes", new Integer[]{40, 50})),
						otherOwner, new Link(1, 1, 2));
				}
				case TWO_UPSERTS -> {
					seed();
					twoUpserts();
				}
			}
		}

		/**
		 * Defines the schemas and indexes needed for the scenario.
		 *
		 * @param session catalog session
		 */
		private void defineSchema(@Nonnull EvitaSessionContract session) {
			final Cardinality cardinality = this.scenario == Scenario.REPRESENTATIVE_ATTRIBUTE ?
				Cardinality.ZERO_OR_MORE_WITH_DUPLICATES : Cardinality.ZERO_OR_MORE;
			session.defineEntitySchema(MEMBER).withoutGeneratedPrimaryKey()
				.withAttribute("size", Integer.class, attr -> attr.filterable().nullable())
				.withAttribute("sizes", Integer[].class, attr -> attr.filterable().nullable())
				.withReferenceToEntity(PRIMARY, MEMBER, cardinality, ref -> {
					ref.indexedForFilteringInScope(BOTH_SCOPES).withAttribute("level", Integer.class, attr -> {
						attr.filterable();
						if (this.scenario == Scenario.REPRESENTATIVE_ATTRIBUTE) {
							attr.representative();
						} else {
							attr.nullable();
						}
					});
				}).updateVia(session);
			final String source = this.scenario == Scenario.ARRAY_THROUGH_OTHER_OWNER ? "sizes" : "size";
			session.defineEntitySchema(MEMBER)
				.withReflectedReferenceToEntity(REFLECTED, MEMBER, PRIMARY, ref -> {
					ref.withCardinality(cardinality).withAttributesInherited()
						.indexedForFilteringInScope(Scope.LIVE).faceted();
					if (this.histogram) {
						ref.bucketed(VALUES,
							ExpressionFactory.parse("$reference.referencedEntity.attributes['" + source + "']"));
					}
				}).updateVia(session);
			if (this.state == CatalogState.ALIVE) {
				session.goLiveAndClose();
			}
		}

		/**
		 * Creates a builder carrying the member’s initial histogram source.
		 *
		 * @param session catalog session
		 * @param id      member primary key
		 * @return builder for the member
		 */
		@Nonnull
		private EntityBuilder createMember(@Nonnull EvitaSessionContract session, int id) {
			final EntityBuilder builder = session.createNewEntity(MEMBER, id);
			final List<Integer> memberValues = this.values.get(id);
			if (this.scenario == Scenario.ARRAY_THROUGH_OTHER_OWNER) {
				return builder.setAttribute("sizes", memberValues.toArray(Integer[]::new));
			}
			return builder.setAttribute("size", memberValues.getFirst());
		}

		/**
		 * Writes a primary reference and checks its expected counterpart.
		 *
		 * @param step operation being observed
		 * @param link logical primary reference and its level
		 */
		private void insert(@Nonnull String step, @Nonnull Link link) {
			step(step, session -> writePrimary(session, link), link);
		}

		/**
		 * Seeds the primary reference and checks that it can be reasserted.
		 */
		private void seed() {
			insert("insert primary", this.firstLink);
			ensurePrimary("ensure primary before follow-up");
		}

		/**
		 * Makes the primary reference available for a follow-up probe.
		 *
		 * @param step operation being observed
		 */
		private void ensurePrimary(@Nonnull String step) {
			step(step, session -> {
				if (member(session, 1).getReferences(PRIMARY, 1).isEmpty()) {
					return writePrimary(session, this.firstLink);
				}
				return "already stored";
			}, this.firstLink);
		}

		/**
		 * Writes the reference and level specified by the link.
		 *
		 * @param session catalog session
		 * @param link    logical primary reference and its level
		 * @return upsert result
		 */
		@Nonnull
		private Object writePrimary(@Nonnull EvitaSessionContract session, @Nonnull Link link) {
			final EntityBuilder builder = member(session, link.source()).openForWrite();
			if (this.scenario == Scenario.REPRESENTATIVE_ATTRIBUTE) {
				builder.setOrUpdateReference(PRIMARY, link.target(), reference -> false,
					ref -> ref.setAttribute("level", link.level()));
			} else {
				builder.setReference(PRIMARY, link.target(), ref -> ref.setAttribute("level", link.level()));
			}
			return builder.upsertVia(session);
		}

		/**
		 * Checks the result of each upsert inside a single session and after restart.
		 */
		private void twoUpserts() {
			final Link afterFirst = new Link(1, 1, 2);
			final Link afterSecond = new Link(1, 1, 3);
			attemptWrite("commit both upserts", () -> {
				this.evita.updateCatalog(TEST_CATALOG, session -> {
					attemptWrite("first upsert", () -> upsert(session, 1, level(PRIMARY, 1, 2)));
					observe(session, "first upsert", "inside session", afterFirst);
					attemptWrite("second upsert", () -> upsert(session, 1, level(PRIMARY, 1, 3)));
					observe(session, "second upsert", "inside session", afterSecond);
				});
				return "session closed";
			});
			this.stored = null;
			observe("second upsert", "stored", afterSecond);
			restartAndObserve("second upsert", afterSecond);
		}

		/**
		 * Runs one public operation and checks its immediate and restarted state.
		 *
		 * @param step  operation being observed
		 * @param write write to execute
		 * @param links expected logical primary references
		 */
		private void step(
			@Nonnull String step,
			@Nonnull Function<EvitaSessionContract, Object> write,
			@Nonnull Link... links
		) {
			attemptWrite(step, () -> this.evita.updateCatalog(TEST_CATALOG, write));
			this.stored = null;
			observe(step, "stored", links);
			restartAndObserve(step, links);
		}

		/**
		 * Captures refusal details without skipping the storage observations.
		 *
		 * @param step  operation being observed
		 * @param write write to execute
		 */
		private void attemptWrite(@Nonnull String step, @Nonnull Supplier<Object> write) {
			try {
				final Object result = write.get();
				if (Boolean.FALSE.equals(result)) {
					this.checks.add(() -> fail(step + " returned false"));
				}
			} catch (final RuntimeException ex) {
				this.checks.add(() -> fail(step + " must succeed", ex));
			}
		}

		/**
		 * Reopens the database and reads the state produced by the step.
		 *
		 * @param step  operation being observed
		 * @param links expected logical primary references
		 */
		private void restartAndObserve(@Nonnull String step, @Nonnull Link... links) {
			try {
				this.evita.close();
				this.evita = new Evita(this.configuration);
				this.evita.waitUntilFullyInitialized();
				observe(step, "restarted", links);
			} catch (final RuntimeException ex) {
				this.checks.add(() -> fail(step + " restart must succeed", ex));
			}
		}

		/**
		 * Records and checks a snapshot of stored rows and indexes.
		 *
		 * @param step  operation being observed
		 * @param phase observation phase
		 * @param links expected logical primary references
		 */
		private void observe(@Nonnull String step, @Nonnull String phase, @Nonnull Link... links) {
			try {
				this.evita.queryCatalog(TEST_CATALOG, session -> {
					observe(session, step, phase, links);
					return null;
				});
			} catch (final RuntimeException ex) {
				this.checks.add(() -> fail(step + " " + phase + " must be readable", ex));
			}
		}

		/**
		 * Records and checks a snapshot of stored rows and indexes.
		 *
		 * @param session catalog session
		 * @param step    operation being observed
		 * @param phase   observation phase
		 * @param links   expected logical primary references
		 */
		private void observe(
			@Nonnull EvitaSessionContract session,
			@Nonnull String step,
			@Nonnull String phase,
			@Nonnull Link... links
		) {
			final Map<Integer, MemberBody> bodies = readBodies(session);
			final Map<Scope, Map<Integer, Set<Integer>>> histograms = readHistograms(this.evita, REFLECTED);
			final String description = step + " " + phase;
			final CatalogState observedState = session.getCatalogState();
			this.checks.add(() -> assertEquals(this.state, observedState, description + " catalog state"));
			final Observation current = new Observation(bodies, histograms);
			if (phase.equals("stored")) {
				this.stored = current;
			} else if (phase.equals("restarted") && this.stored != null) {
				final Observation beforeRestart = this.stored;
				this.checks.add(() -> assertEquals(beforeRestart, current, description + " durability"));
			}
			final Map<Integer, String> expectedBody = expectedContent(links);
			final Map<Scope, Map<Integer, Set<Integer>>> expectedOwners = expectedHistograms(links);
			this.checks.add(() -> assertEquals(expectedBody, content(bodies), description));
			this.checks.add(() -> assertEquals(expectedOwners, histograms, description + " histogram"));
			for (final Link link : links) {
				if (reflects(link)) {
					final List<Integer> primaryIds = referenceIds(bodies, link.source(), PRIMARY, link.target());
					final List<Integer> reflectedIds = referenceIds(bodies, link.target(), REFLECTED, link.source());
					this.checks.add(() -> assertEquals(
						primaryIds, reflectedIds, description + " counterpart ids for " + link
					));
					this.checks.add(() -> assertTrue(primaryIds.stream().allMatch(id -> id > 0),
						description + " stored reference ids for " + link));
				}
			}
		}

		/**
		 * Reads the complete stored member and reference rows.
		 *
		 * @param session catalog session
		 * @return member bodies by primary key
		 */
		@Nonnull
		private Map<Integer, MemberBody> readBodies(@Nonnull EvitaSessionContract session) {
			final Map<Integer, MemberBody> bodies = new TreeMap<>();
			for (int id = 1; id <= this.memberCount; id++) {
				final int memberId = id;
				session.getEntity(MEMBER, id, BOTH_SCOPES, entityFetchAllContent()).ifPresent(entity -> {
					final List<ReferenceBody> references = entity.getReferences().stream().map(ref -> {
						final ReferenceKey key = ref.getReferenceKey();
						return new ReferenceBody(key.referenceName(), key.primaryKey(), key.internalPrimaryKey(),
							ref.getAttribute("level"));
					}).sorted(Comparator.comparing(ReferenceBody::content).thenComparingInt(ReferenceBody::id))
						.toList();
					final Integer[] sizes = entity.getAttribute("sizes");
					bodies.put(memberId, new MemberBody(entity.getScope(), entity.getAttribute("size"),
						sizes == null ? List.of() : List.of(sizes), references));
				});
			}
			return bodies;
		}

		/**
		 * Builds the expected member bodies from the logical reference links.
		 *
		 * @param links expected logical primary references
		 * @return expected body descriptions by primary key
		 */
		@Nonnull
		private Map<Integer, String> expectedContent(@Nonnull Link[] links) {
			final Map<Integer, MemberBody> expected = new TreeMap<>();
			for (final Map.Entry<Integer, Scope> member : this.scopes.entrySet()) {
				final int id = member.getKey();
				final List<ReferenceBody> refs = new ArrayList<>();
				for (final Link link : links) {
					if (link.source() == id) {
						refs.add(new ReferenceBody(PRIMARY, link.target(), 0, link.level()));
					}
					if (link.target() == id && reflects(link)) {
						refs.add(new ReferenceBody(REFLECTED, link.source(), 0, link.level()));
					}
				}
				final boolean arraySource = this.scenario == Scenario.ARRAY_THROUGH_OTHER_OWNER;
				expected.put(id, new MemberBody(member.getValue(), arraySource ? null : this.values.get(id).getFirst(),
					arraySource ? this.values.get(id) : List.of(), refs));
			}
			return content(expected);
		}

		/**
		 * Builds the expected histogram owners from the visible reference links.
		 *
		 * @param links expected logical primary references
		 * @return expected owners by scope and bucket value
		 */
		@Nonnull
		private Map<Scope, Map<Integer, Set<Integer>>> expectedHistograms(@Nonnull Link[] links) {
			final Map<Scope, Map<Integer, Set<Integer>>> histograms = emptyHistograms();
			if (!this.histogram) {
				return histograms;
			}
			for (final Link link : links) {
				if (reflects(link)) {
					for (final int value : this.values.get(link.source())) {
						histograms.get(Scope.LIVE).computeIfAbsent(value, ignored -> new TreeSet<>())
							.add(link.target());
					}
				}
			}
			return histograms;
		}

		/**
		 * Checks whether both ends of a link participate in reflection.
		 *
		 * @param link logical primary reference and its level
		 * @return true if the link has a reflected counterpart
		 */
		private boolean reflects(@Nonnull Link link) {
			return this.scopes.get(link.source()) == Scope.LIVE && this.scopes.get(link.target()) == Scope.LIVE;
		}

		/**
		 * Closes the database and removes the fixture storage.
		 */
		@Override
		public void close() {
			closeAndCleanUp(this.evita, this.paths);
		}
	}

	/**
	 * Reads the owners counted in each bucket of a reference's histogram, in each scope. A bucket left without owners
	 * is kept as an empty set, so a stale bucket is visible rather than dropped.
	 *
	 * @param evita         database instance
	 * @param referenceName name of the bucketed reference
	 * @return owners by scope and bucket value
	 */
	@Nonnull
	private static Map<Scope, Map<Integer, Set<Integer>>> readHistograms(
		@Nonnull Evita evita,
		@Nonnull String referenceName
	) {
		final Map<Scope, Map<Integer, Set<Integer>>> histograms = emptyHistograms();
		final CatalogContract catalog = evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		catalog.getCollectionForEntity(MEMBER).ifPresent(collection -> {
			for (final Scope scope : BOTH_SCOPES) {
				final EntityIndex index = ((EntityCollection) collection).getIndexByKeyIfExists(
					new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, referenceName)
				);
				if (index instanceof HistogramCapableEntityIndex capable) {
					final FilterIndex filter = capable.getHistogramFilterIndex(VALUES, null);
					if (filter != null) {
						for (final ValueToRecord bucket : filter.getHistogramOfAllRecords().getBuckets()) {
							final Set<Integer> owners = histograms.get(scope).computeIfAbsent(
								((Number) bucket.getValue()).intValue(), ignored -> new TreeSet<>());
							for (final int owner : bucket.getRecordIds().getArray()) {
								owners.add(owner);
							}
						}
					}
				}
			}
		});
		return histograms;
	}

	/**
	 * Closes the database of a fixture and removes its storage, also when closing fails.
	 *
	 * @param evita database instance the fixture holds when it closes
	 * @param paths storage paths of the fixture
	 */
	private void closeAndCleanUp(@Nonnull Evita evita, @Nonnull TestPaths paths) {
		try {
			evita.close();
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/**
	 * Creates an empty histogram result for all measured scopes.
	 *
	 * @return mutable empty histograms by scope
	 */
	@Nonnull
	private static Map<Scope, Map<Integer, Set<Integer>>> emptyHistograms() {
		final Map<Scope, Map<Integer, Set<Integer>>> histograms = new EnumMap<>(Scope.class);
		for (final Scope scope : BOTH_SCOPES) {
			histograms.put(scope, new TreeMap<>());
		}
		return histograms;
	}

	/**
	 * Formats the reference or entity values used in semantic assertions.
	 *
	 * @param bodies observed member bodies
	 * @return formatted comparison values
	 */
	@Nonnull
	private static Map<Integer, String> content(@Nonnull Map<Integer, MemberBody> bodies) {
		final Map<Integer, String> result = new TreeMap<>();
		bodies.forEach((id, body) -> result.put(id, body.scope() + " size=" + body.size() + " sizes=" + body.sizes() +
			" references=" + body.references().stream().map(ReferenceBody::content).sorted().toList()));
		return result;
	}

	/**
	 * Finds all stored IDs for a reference name and target.
	 *
	 * @param bodies observed member bodies
	 * @param owner  primary key of the reference owner
	 * @param name   reference or attribute name
	 * @param target referenced member primary key
	 * @return sorted internal reference IDs
	 */
	@Nonnull
	private static List<Integer> referenceIds(
		@Nonnull Map<Integer, MemberBody> bodies,
		int owner,
		@Nonnull String name,
		int target
	) {
		final MemberBody body = bodies.get(owner);
		return body == null ? List.of() : body.references().stream()
			.filter(ref -> ref.name().equals(name) && ref.target() == target).map(ReferenceBody::id).sorted().toList();
	}

	/**
	 * Loads a member with its entire stored body in either scope.
	 *
	 * @param session catalog session
	 * @param id      member primary key
	 * @return fully fetched member
	 */
	@Nonnull
	private static SealedEntity member(@Nonnull EvitaSessionContract session, int id) {
		return session.getEntity(MEMBER, id, BOTH_SCOPES, entityFetchAllContent()).orElseThrow();
	}

	/**
	 * Submits a set of local mutations on an existing member.
	 *
	 * @param session   catalog session
	 * @param id        member primary key
	 * @param mutations local mutations to apply
	 * @return upsert result
	 */
	@Nonnull
	private static Object upsert(
		@Nonnull EvitaSessionContract session,
		int id,
		@Nonnull LocalMutation<?, ?>... mutations
	) {
		return session.upsertEntity(new EntityUpsertMutation(MEMBER, id, EntityExistence.MUST_EXIST, mutations));
	}

	/**
	 * Builds a local mutation for the reference level attribute.
	 *
	 * @param name   reference or attribute name
	 * @param target referenced member primary key
	 * @param level  value of the reference level attribute
	 * @return reference attribute mutation
	 */
	@Nonnull
	private static ReferenceAttributeMutation level(@Nonnull String name, int target, int level) {
		return new ReferenceAttributeMutation(
			new ReferenceKey(name, target), new UpsertAttributeMutation("level", level)
		);
	}

	/**
	 * Write sequences covering restore, insertion and representative attributes.
	 */
	private enum WriteScenario {
		ARCHIVE_WITH_ASYMMETRIC_INDEXING,
		ARCHIVE_WITH_ASYMMETRIC_INDEXING_AND_HISTOGRAM,
		RESTORE_REPRESENTATIVE_RANK,
		RESTORE_TWO_REPRESENTATIVE_RANKS,
		RESTORE_WITH_SECOND_TARGET,
		ADD_SELF_REFERENCE,
		ADD_SELF_REFERENCE_WITH_HISTOGRAM,
		INSERT_REPRESENTATIVE_RANK,
		INSERT_SINGLE_RANK,
		UPDATE_REPRESENTATIVE_RANK,
		UPDATE_SINGLE_RANK
	}

	/**
	 * Supplies the bounded storage and partition scenarios in both catalog states.
	 *
	 * @return parameterized reference-write cases
	 */
	@Nonnull
	static Stream<Arguments> referenceWriteScenarios() {
		return catalogStates().flatMap(state ->
			Arrays.stream(WriteScenario.values()).map(scenario -> Arguments.of(state, scenario))
		);
	}

	/**
	 * Supplies the two catalog lifecycle states used by the tests.
	 *
	 * @return catalog states to exercise
	 */
	@Nonnull
	static Stream<CatalogState> catalogStates() {
		return Stream.of(CatalogState.WARMING_UP, CatalogState.ALIVE);
	}

	/**
	 * Runs a scenario that checks persisted rows and representative partitions.
	 *
	 * @param state    catalog lifecycle state
	 * @param scenario write sequence to exercise
	 */
	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("referenceWriteScenarios")
	@DisplayName("preserves references and index contents across writes and restart")
	void shouldPreserveReferencesAndIndexesAcrossScopeChangesAndRankedWrites(
		@Nonnull CatalogState state,
		@Nonnull WriteScenario scenario
	) {
		try (final WriteFixture fixture = new WriteFixture(state, scenario.name())) {
			switch (scenario) {
				case ARCHIVE_WITH_ASYMMETRIC_INDEXING -> fixture.archiveWithAsymmetricIndexing(false);
				case ARCHIVE_WITH_ASYMMETRIC_INDEXING_AND_HISTOGRAM -> fixture.archiveWithAsymmetricIndexing(true);
				case RESTORE_REPRESENTATIVE_RANK -> fixture.restoreRepresentativeRank();
				case RESTORE_TWO_REPRESENTATIVE_RANKS -> fixture.restoreTwoRepresentativeRanks();
				case RESTORE_WITH_SECOND_TARGET -> fixture.restoreWithSecondTarget();
				case ADD_SELF_REFERENCE -> fixture.addSelfReference(false);
				case ADD_SELF_REFERENCE_WITH_HISTOGRAM -> fixture.addSelfReference(true);
				case INSERT_REPRESENTATIVE_RANK -> fixture.writeRank(true, false);
				case INSERT_SINGLE_RANK -> fixture.writeRank(false, false);
				case UPDATE_REPRESENTATIVE_RANK -> fixture.writeRank(true, true);
				case UPDATE_SINGLE_RANK -> fixture.writeRank(false, true);
			}
			assertAll(fixture.checks);
		}
	}

	/**
	 * Reference attributes used for the body comparisons.
	 */
	private record ReferenceValues(String name, int target, Integer level, Integer rank, String note) {}

	/**
	 * Stored reference attributes and their assigned internal primary key.
	 */
	private record StoredReference(ReferenceValues values, int id) {}

	/**
	 * Expected values in a member body.
	 */
	private record WriteBody(Scope scope, int size, List<ReferenceValues> references) {}

	/**
	 * Actual values and reference identities loaded from storage.
	 */
	private record StoredBody(Scope scope, Integer size, List<StoredReference> references) {}

	/**
	 * Indexed reference partition and the values it exposes.
	 */
	private record IndexedPartition(
		int id, List<String> key, List<Integer> owners, boolean registered, List<String> ranks, List<String> notes
	) {}

	/**
	 * Body, histogram and partition observations for one write.
	 */
	private record WriteSnapshot(
		CatalogState state,
		Map<Integer, StoredBody> bodies,
		Map<Scope, Map<Integer, Set<Integer>>> histogram,
		Map<String, List<IndexedPartition>> partitions
	) {}

	/**
	 * Verifies persisted references and the indexes that depend on their values.
	 */
	private final class WriteFixture implements AutoCloseable {
		private final CatalogState state;
		private final TestPaths paths;
		private final EvitaConfiguration configuration;
		/**
		 * Deferred assertions, run together once the scenario ends so that one failure does not hide the others.
		 */
		private final List<Executable> checks = new ArrayList<>();
		private Evita evita;
		/**
		 * Reference whose histogram the snapshot reads, or null when the scenario has none.
		 */
		@Nullable private String histogramReference;
		/**
		 * Whether the schema has a rank attribute, so reference partitions and their indexed ranks and notes are read.
		 */
		private boolean inspectPartitions;
		/**
		 * Whether the reference allows duplicates, so its partitions are keyed by rank.
		 */
		private boolean duplicates;

		/**
		 * Creates a catalog fixture for detailed body and index snapshots.
		 *
		 * @param state    catalog lifecycle state
		 * @param scenario write sequence to exercise
		 */
		private WriteFixture(@Nonnull CatalogState state, @Nonnull String scenario) {
			this.state = state;
			this.paths = createTestPaths("referenceWrites_" + state + "_" + scenario);
			this.configuration = newTestEvitaConfigurationBuilder(this.paths).build();
			this.evita = new Evita(this.configuration);
			this.evita.defineCatalog(TEST_CATALOG);
		}

		/**
		 * Archives a member whose self mirror is partitioned in both scopes.
		 *
		 * @param histogram whether the self mirror carries the histogram under test
		 */
		private void archiveWithAsymmetricIndexing(boolean histogram) {
			this.histogramReference = histogram ? REFLECTED : null;
			step("define scope indexes", session -> {
				session.defineEntitySchema(MEMBER).withoutGeneratedPrimaryKey()
					.withAttribute("size", Integer.class, attr -> attr.filterable().nullable())
					.withReferenceToEntity(PRIMARY, MEMBER, Cardinality.ZERO_OR_MORE,
						ref -> ref.indexedForFilteringAndPartitioningInScope(Scope.LIVE, Scope.ARCHIVED))
					.updateVia(session);
				session.defineEntitySchema(MEMBER)
					.withReflectedReferenceToEntity(REFLECTED, MEMBER, PRIMARY, ref -> {
						ref.withCardinality(Cardinality.ZERO_OR_MORE)
							.indexedForFilteringAndPartitioningInScope(Scope.LIVE, Scope.ARCHIVED)
							.indexedWithComponentsInScope(Scope.ARCHIVED, ReferenceIndexedComponents.REFERENCED_ENTITY);
						if (histogram) {
							ref.bucketed(VALUES,
								ExpressionFactory.parse("$reference.referencedEntity.attributes['size']"));
						}
					})
					.updateVia(session);
				if (this.state == CatalogState.ALIVE) {
					session.goLiveAndClose();
				}
				return "defined";
			}, Map.of());
			step("store live member", session -> create(session, 1, Scope.LIVE, 30),
				Map.of(1, body(Scope.LIVE, 30)));
			final ReferenceValues primary = reference(PRIMARY, 1, null);
			final ReferenceValues mirror = reference(REFLECTED, 1, null);
			step("seed matching self rows", session -> upsert(session, 1,
					new InsertReferenceMutation(new ReferenceKey(PRIMARY, 1, -1)),
					new InsertReferenceMutation(new ReferenceKey(REFLECTED, 1, 1))),
				Map.of(1, body(Scope.LIVE, 30, primary, mirror)),
				histogram ? Map.of(30, Set.of(1)) : Map.of());
			step("archive member with partitioned mirror", session -> {
				// Keep the published schema valid. Narrow the primary index for the archive write itself.
				session.defineEntitySchema(MEMBER)
					.withReferenceToEntity(PRIMARY, MEMBER, Cardinality.ZERO_OR_MORE,
						ref -> ref.nonIndexed(Scope.ARCHIVED))
					.updateVia(session);
				final SealedEntity member = member(session, 1);
				final boolean primaryIndexed =
					member.getSchema().getReferenceOrThrowException(PRIMARY).isIndexedInScope(Scope.ARCHIVED);
				final boolean mirrorIndexed =
					member.getSchema().getReferenceOrThrowException(REFLECTED).isIndexedInScope(Scope.ARCHIVED);
				this.checks.add(() -> assertFalse(primaryIndexed, "primary not indexed in archive during the write"));
				this.checks.add(() -> assertTrue(mirrorIndexed, "mirror indexed in archive during the write"));
				try {
					return session.archiveEntity(MEMBER, 1);
				} finally {
					session.defineEntitySchema(MEMBER)
						.withReferenceToEntity(PRIMARY, MEMBER, Cardinality.ZERO_OR_MORE,
							ref -> ref.indexedForFilteringAndPartitioningInScope(Scope.LIVE, Scope.ARCHIVED))
						.updateVia(session);
				}
			},
				Map.of(1, body(Scope.ARCHIVED, 30, primary)));
		}

		/**
		 * Restores a ranked self mirror into its virtual partition using a scope-only write.
		 */
		private void restoreRepresentativeRank() {
			defineSchema(null, true, true, false);
			step("store archived member", session -> create(session, 1, Scope.ARCHIVED, 30),
				Map.of(1, body(Scope.ARCHIVED, 30)));
			step("store archived primary at rank two", this::insertAtRankTwo,
				Map.of(1, body(Scope.ARCHIVED, 30, new ReferenceValues(PRIMARY, 1, null, 2, null))));
			step("restore member with ranked mirror", session -> session.restoreEntity(MEMBER, 1), rankedBody(2, null));
		}

		/**
		 * Restores two ranked self rows to the same target in one scope-only write, then removes one of them.
		 */
		private void restoreTwoRepresentativeRanks() {
			defineSchema(null, true, true, false);
			final ReferenceValues primaryAtTwo = new ReferenceValues(PRIMARY, 1, null, 2, null);
			final ReferenceValues primaryAtThree = new ReferenceValues(PRIMARY, 1, null, 3, null);
			step("store archived member", session -> create(session, 1, Scope.ARCHIVED, 30),
				Map.of(1, body(Scope.ARCHIVED, 30)));
			step("store archived primaries at ranks two and three", session -> member(session, 1).openForWrite()
					.setOrUpdateReference(PRIMARY, 1, reference -> false, ref -> ref.setAttribute("rank", 2))
					.setOrUpdateReference(PRIMARY, 1, reference -> false, ref -> ref.setAttribute("rank", 3))
					.upsertVia(session),
				Map.of(1, body(Scope.ARCHIVED, 30, primaryAtTwo, primaryAtThree)));
			step("restore member with both ranked mirrors", session -> session.restoreEntity(MEMBER, 1),
				Map.of(1, body(Scope.LIVE, 30, primaryAtTwo, primaryAtThree,
					new ReferenceValues(REFLECTED, 1, null, 2, null),
					new ReferenceValues(REFLECTED, 1, null, 3, null))));
			step("remove primary at rank two", session -> upsert(session, 1, new RemoveReferenceMutation(
					member(session, 1).getReferences(PRIMARY, 1).stream()
						.filter(ref -> Integer.valueOf(2).equals(ref.getAttribute("rank")))
						.findFirst().orElseThrow().getReferenceKey()
				)),
				rankedBody(3, null));
		}

		/**
		 * Checks that restore recreates both mirrors and deletion removes the surviving mirror.
		 */
		private void restoreWithSecondTarget() {
			defineSchema(null, false, false, true);
			step("store archived member", session -> create(session, 1, Scope.ARCHIVED, 30),
				Map.of(1, body(Scope.ARCHIVED, 30)));
			step("store live member", session -> create(session, 2, Scope.LIVE, 40),
				Map.of(1, body(Scope.ARCHIVED, 30), 2, body(Scope.LIVE, 40)));
			final ReferenceValues self = reference(PRIMARY, 1, null);
			final ReferenceValues other = reference(PRIMARY, 2, null);
			step("add both primary references", session -> member(session, 1).openForWrite()
					.setReference(PRIMARY, 1).setReference(PRIMARY, 2).upsertVia(session),
				Map.of(1, body(Scope.ARCHIVED, 30, self, other), 2, body(Scope.LIVE, 40)));
			final ReferenceValues mirror = reference(REFLECTED, 1, null);
			step("restore archived member", session -> session.restoreEntity(MEMBER, 1),
				Map.of(1, body(Scope.LIVE, 30, self, other, mirror), 2, body(Scope.LIVE, 40, mirror)));
			step("delete restored member", session -> session.deleteEntity(MEMBER, 1),
				Map.of(2, body(Scope.LIVE, 40)));
		}

		/**
		 * Adds a self-reference to an existing member and checks both rows.
		 *
		 * @param histogram whether the self-reference carries the histogram under test
		 */
		private void addSelfReference(boolean histogram) {
			defineSchema(histogram ? REFLECTED : null, false, false, false);
			step("store member without references", session -> create(session, 1, Scope.LIVE, 30),
				Map.of(1, body(Scope.LIVE, 30)));
			step("add primary self-reference", session -> insertWithLevel(session, 1),
				Map.of(1, body(Scope.LIVE, 30, reference(PRIMARY, 1, 1), reference(REFLECTED, 1, 1))),
				histogram ? Map.of(30, Set.of(1)) : Map.of());
		}

		/**
		 * Checks ranked insertion or migration, removal, and reinsertion.
		 *
		 * @param allowDuplicates whether the reference accepts duplicate rows
		 * @param update          whether to test rank migration
		 */
		private void writeRank(boolean allowDuplicates, boolean update) {
			defineSchema(null, true, allowDuplicates, true);
			step("store member without references", session -> create(session, 1, Scope.LIVE, 30),
				Map.of(1, body(Scope.LIVE, 30)));
			if (update) {
				// The primary receives the first generated ID. Give the mirror that ID and use the rank default
				// on both rows before writing attributes addressed by the stored ID.
				final ReferenceKey newPrimary = new ReferenceKey(PRIMARY, 1, -1);
				final ReferenceKey primaryKey = new ReferenceKey(PRIMARY, 1, 1);
				final ReferenceKey mirrorKey = new ReferenceKey(REFLECTED, 1, 1);
				step("seed matching primary and mirror at rank one", session -> upsert(session, 1,
						new InsertReferenceMutation(newPrimary), new InsertReferenceMutation(mirrorKey)),
					rankedBody(1, null));
				step("seed mirror note", session -> upsert(session, 1, attribute(mirrorKey, "note", "before")),
					rankedBody(1, "before"));
				step("change mirror note and primary rank", session -> upsert(session, 1,
						attribute(mirrorKey, "note", "after"), attribute(primaryKey, "rank", 2)),
					rankedBody(2, "after"));
			} else {
				step("insert primary at rank two", this::insertAtRankTwo, rankedBody(2, null));
			}
			step("remove primary", session -> upsert(session, 1, new RemoveReferenceMutation(
					member(session, 1).getReferences(PRIMARY, 1).stream().findFirst()
						.map(ReferenceContract::getReferenceKey).orElseGet(() -> new ReferenceKey(PRIMARY, 1))
				)),
				Map.of(1, body(Scope.LIVE, 30)));
			step("reinsert primary at rank two", this::insertAtRankTwo, rankedBody(2, null));
		}

		/**
		 * Inserts a self-reference with a rank distinct from the schema default.
		 *
		 * @param session catalog session
		 * @return upsert result
		 */
		@Nonnull
		private Object insertAtRankTwo(@Nonnull EvitaSessionContract session) {
			final EntityBuilder builder = member(session, 1).openForWrite();
			if (this.duplicates) {
				builder.setOrUpdateReference(PRIMARY, 1, reference -> false, ref -> ref.setAttribute("rank", 2));
			} else {
				builder.setReference(PRIMARY, 1, ref -> ref.setAttribute("rank", 2));
			}
			return builder.upsertVia(session);
		}

		/**
		 * Defines the schemas and indexes needed for the scenario.
		 *
		 * @param histogram       histogram under test, if enabled
		 * @param ranked          whether the schema has a rank attribute
		 * @param allowDuplicates whether the reference accepts duplicate rows
		 * @param partitioned     whether to index reference partitions
		 */
		private void defineSchema(
			@Nullable String histogram,
			boolean ranked,
			boolean allowDuplicates,
			boolean partitioned
		) {
			this.histogramReference = histogram;
			this.inspectPartitions = ranked;
			this.duplicates = allowDuplicates;
			final Cardinality cardinality = allowDuplicates ?
				Cardinality.ZERO_OR_MORE_WITH_DUPLICATES : Cardinality.ZERO_OR_MORE;
			step("define schema", session -> {
				session.defineEntitySchema(MEMBER).withoutGeneratedPrimaryKey()
					.withAttribute("size", Integer.class, attr -> attr.filterable().nullable())
					.withReferenceToEntity(PRIMARY, MEMBER, cardinality, ref -> {
						if (partitioned) {
							ref.indexedForFilteringAndPartitioningInScope(Scope.LIVE);
						} else {
							ref.indexedForFilteringInScope(Scope.LIVE);
						}
						if (ranked) {
							ref.withAttribute("rank", Integer.class, attr -> {
								attr.filterable().withDefaultValue(1);
								if (allowDuplicates) {
									attr.representative();
								}
							});
						} else {
							ref.withAttribute("level", Integer.class, attr -> attr.filterable().nullable());
						}
					}).updateVia(session);
				session.defineEntitySchema(MEMBER)
					.withReflectedReferenceToEntity(REFLECTED, MEMBER, PRIMARY, ref -> {
						ref.withCardinality(cardinality).withAttributesInherited();
						if (partitioned) {
							ref.indexedForFilteringAndPartitioningInScope(Scope.LIVE);
						} else {
							ref.indexedForFilteringInScope(Scope.LIVE);
						}
						if (ranked) {
							ref.withAttribute("note", String.class, attr -> attr.filterable().nullable());
						}
						if (REFLECTED.equals(histogram)) {
							ref.bucketed(VALUES,
								ExpressionFactory.parse("$reference.referencedEntity.attributes['size']"));
						}
					}).updateVia(session);
				if (this.state == CatalogState.ALIVE) {
					session.goLiveAndClose();
				}
				return "defined";
			}, Map.of());
		}

		/**
		 * Runs one public operation and checks its immediate and restarted state.
		 *
		 * @param step     operation being observed
		 * @param write    write to execute
		 * @param expected expected member bodies
		 */
		private void step(
			@Nonnull String step,
			@Nonnull Function<EvitaSessionContract, Object> write,
			@Nonnull Map<Integer, WriteBody> expected
		) {
			step(step, write, expected, Map.of());
		}

		/**
		 * Runs one public operation and checks its immediate and restarted state.
		 *
		 * @param step      operation being observed
		 * @param write     write to execute
		 * @param expected  expected member bodies
		 * @param histogram expected LIVE histogram buckets, each mapped to the owners counted in it
		 */
		private void step(
			@Nonnull String step,
			@Nonnull Function<EvitaSessionContract, Object> write,
			@Nonnull Map<Integer, WriteBody> expected,
			@Nonnull Map<Integer, Set<Integer>> histogram
		) {
			try {
				final Object result = this.evita.updateCatalog(TEST_CATALOG, write);
				if (Boolean.FALSE.equals(result)) {
					this.checks.add(() -> fail(step + " returned false"));
				}
			} catch (final RuntimeException ex) {
				this.checks.add(() -> fail(step + " must succeed", ex));
			}
			final WriteSnapshot stored = observe(step, "stored", expected, histogram);
			this.evita.close();
			this.evita = new Evita(this.configuration);
			this.evita.waitUntilFullyInitialized();
			final WriteSnapshot restarted = observe(step, "restarted", expected, histogram);
			this.checks.add(() -> assertEquals(stored, restarted, step + " restart"));
		}

		/**
		 * Records and checks a snapshot of stored rows and indexes.
		 *
		 * @param step      operation being observed
		 * @param phase     observation phase
		 * @param expected  expected member bodies
		 * @param histogram expected LIVE histogram buckets, each mapped to the owners counted in it
		 * @return captured snapshot, or null when the state could not be read
		 */
		@Nullable
		private WriteSnapshot observe(
			@Nonnull String step,
			@Nonnull String phase,
			@Nonnull Map<Integer, WriteBody> expected,
			@Nonnull Map<Integer, Set<Integer>> histogram
		) {
			try {
				final WriteSnapshot snapshot = this.evita.queryCatalog(TEST_CATALOG, this::snapshot);
				checkSnapshot(step + " " + phase, expected, histogram, snapshot);
				return snapshot;
			} catch (final RuntimeException ex) {
				this.checks.add(() -> fail(step + " " + phase + " must be readable", ex));
				return null;
			}
		}

		/**
		 * Reads all measured body, histogram and partition state.
		 *
		 * @param session catalog session
		 * @return snapshot of the observed catalog state
		 */
		@Nonnull
		private WriteSnapshot snapshot(@Nonnull EvitaSessionContract session) {
			final Map<Integer, StoredBody> bodies = new TreeMap<>();
			for (final int id : new int[]{1, 2}) {
				session.getEntity(MEMBER, id, BOTH_SCOPES, entityFetchAllContent()).ifPresent(entity -> {
					final List<StoredReference> refs = entity.getReferences().stream().map(ref -> {
						final ReferenceKey key = ref.getReferenceKey();
						final Integer level = ref.getReferenceSchemaOrThrow().getAttribute("level").isPresent() ?
							ref.getAttribute("level") : null;
						final Integer rank = this.inspectPartitions ? ref.getAttribute("rank") : null;
						final String note = this.inspectPartitions && REFLECTED.equals(key.referenceName()) ?
							ref.getAttribute("note") : null;
						return new StoredReference(new ReferenceValues(key.referenceName(), key.primaryKey(),
							level, rank, note), key.internalPrimaryKey());
					}).sorted(Comparator.comparing((StoredReference ref) -> ref.values().toString())
						.thenComparingInt(StoredReference::id)).toList();
					bodies.put(id, new StoredBody(entity.getScope(), entity.getAttribute("size"), refs));
				});
			}
			final Map<Scope, Map<Integer, Set<Integer>>> histogram = this.histogramReference == null ?
				emptyHistograms() : readHistograms(this.evita, this.histogramReference);
			final Map<String, List<IndexedPartition>> partitions = new TreeMap<>();
			if (this.inspectPartitions) {
				final CatalogContract catalog = this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
				catalog.getCollectionForEntity(MEMBER).ifPresent(collection -> {
					for (final String name : List.of(PRIMARY, REFLECTED)) {
						partitions.put(name, readPartitions((EntityCollection) collection, name));
					}
				});
			}
			return new WriteSnapshot(session.getCatalogState(), bodies, histogram, partitions);
		}

		/**
		 * Reads advertised partitions, member IDs and indexed attributes for a reference.
		 *
		 * @param collection member collection
		 * @param name       reference or attribute name
		 * @return reference partitions observed in the LIVE scope, ordered by their representative values
		 */
		@Nonnull
		private List<IndexedPartition> readPartitions(@Nonnull EntityCollection collection, @Nonnull String name) {
			final EntityIndex index = collection.getIndexByKeyIfExists(
				new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, name));
			if (!(index instanceof ReferencedTypeEntityIndex typeIndex)) {
				return List.of();
			}
			final List<IndexedPartition> partitions = new ArrayList<>();
			for (final int id : typeIndex.getAllReferenceIndexes(1)) {
				final boolean registered =
					typeIndex.getVirtualReferencePartitionIndex().getPartitionOwner(id).isPresent();
				final List<String> ranks = filterValues(typeIndex, "rank", id);
				final List<String> notes = REFLECTED.equals(name) ? filterValues(typeIndex, "note", id) : List.of();
				final ReferencePartition partition = ReferencePartitions.resolveForOwnerReadIfExists(
					collection::resolvePartitionIfExists, typeIndex, id);
				if (partition == null) {
					partitions.add(new IndexedPartition(
						id, List.of("unresolved"), List.of(), registered, ranks, notes
					));
				} else {
					final RepresentativeReferenceKey key = partition.getRepresentativeReferenceKey();
					partitions.add(new IndexedPartition(id,
						Arrays.stream(key.representativeAttributeValues()).map(String::valueOf).toList(),
						Arrays.stream(partition.getAllPrimaryKeys().getArray()).boxed().toList(),
						registered, ranks, notes
					));
				}
			}
			// partition IDs follow assignment order, which differs from the order of the representative values
			partitions.sort(Comparator.comparing((IndexedPartition partition) -> partition.key().toString())
				.thenComparingInt(IndexedPartition::id));
			return partitions;
		}

		/**
		 * Gets the filter values contributing to a partition for one attribute.
		 *
		 * @param index       index for the referenced type
		 * @param attribute   name of the indexed attribute
		 * @param partitionId internal partition ID
		 * @return sorted values recorded for the partition
		 */
		@Nonnull
		private List<String> filterValues(
			@Nonnull ReferencedTypeEntityIndex index,
			@Nonnull String attribute,
			int partitionId
		) {
			final AttributeIndexKey key = index.getFilterIndexes().stream()
				.filter(candidate -> candidate.attributeName().equals(attribute) && candidate.locale() == null)
				.findFirst().orElse(null);
			final FilterIndex filter = key == null ? null : index.getFilterIndex(key);
			if (filter == null) {
				return List.of();
			}
			return Arrays.stream(filter.getHistogramOfAllRecords().getBuckets())
				.filter(bucket -> bucket.getRecordIds().contains(partitionId))
				.map(bucket -> String.valueOf(bucket.getValue())).sorted().toList();
		}

		/**
		 * Checks the body, counterpart IDs and measured indexes independently.
		 *
		 * @param phase     observation phase
		 * @param expected  expected member bodies
		 * @param histogram expected LIVE histogram buckets, each mapped to the owners counted in it
		 * @param snapshot  observed body and index state
		 */
		private void checkSnapshot(
			@Nonnull String phase,
			@Nonnull Map<Integer, WriteBody> expected,
			@Nonnull Map<Integer, Set<Integer>> histogram,
			@Nonnull WriteSnapshot snapshot
		) {
			this.checks.add(() -> assertEquals(this.state, snapshot.state(), phase + " catalog state"));
			final Map<Integer, WriteBody> bodyValues = new TreeMap<>();
			snapshot.bodies().forEach((id, body) -> bodyValues.put(id, new WriteBody(body.scope(), body.size(),
				body.references().stream().map(StoredReference::values).toList())));
			this.checks.add(() -> assertEquals(expected, bodyValues, phase + " bodies"));
			this.checks.add(() -> assertEquals(
				Map.of(Scope.LIVE, histogram, Scope.ARCHIVED, Map.of()), snapshot.histogram(), phase + " histogram"
			));
			if (this.inspectPartitions) {
				for (final String name : List.of(PRIMARY, REFLECTED)) {
					final WriteBody owner = expected.get(1);
					final List<ReferenceValues> refs = owner == null || owner.scope() != Scope.LIVE ?
						List.of() : owner.references().stream()
						.filter(ref -> name.equals(ref.name())).toList();
					final List<List<String>> keys = refs.stream()
						.map(ref -> this.duplicates ? List.of(String.valueOf(ref.rank())) : List.<String>of())
						.toList();
					final List<IndexedPartition> partitions = snapshot.partitions().getOrDefault(name, List.of());
					this.checks.add(() -> assertEquals(keys, partitions.stream().map(IndexedPartition::key).toList(),
						phase + " " + name + " partition keys"));
					this.checks.add(() -> assertTrue(
						partitions.stream().allMatch(partition -> partition.owners().equals(List.of(1))),
						phase + " " + name + " partition owners"
					));
					this.checks.add(() -> assertEquals(refs.stream().map(ref -> String.valueOf(ref.rank())).toList(),
						partitions.stream().flatMap(partition -> partition.ranks().stream()).toList(),
						phase + " " + name + " indexed rank"));
					this.checks.add(() -> assertEquals(
						refs.stream().map(ReferenceValues::note).filter(note -> note != null).toList(),
						partitions.stream().flatMap(partition -> partition.notes().stream()).toList(),
						phase + " " + name + " indexed note"
					));
				}
			}
			expected.forEach((owner, body) -> {
				for (final ReferenceValues primary : body.references()) {
					if (PRIMARY.equals(primary.name()) && expected.containsKey(primary.target()) &&
						expected.get(primary.target()).references().stream()
							.anyMatch(ref -> REFLECTED.equals(ref.name()) && ref.target() == owner)) {
						final List<Integer> primaryIds = ids(snapshot, owner, PRIMARY, primary.target());
						final List<Integer> mirrorIds = ids(snapshot, primary.target(), REFLECTED, owner);
						// duplicate rows to one target each keep a distinct persistent ID
						final long rows = body.references().stream()
							.filter(ref -> PRIMARY.equals(ref.name()) && ref.target() == primary.target()).count();
						this.checks.add(() -> assertEquals(primaryIds, mirrorIds, phase + " counterpart IDs"));
						this.checks.add(() -> assertTrue(
							primaryIds.size() == rows && primaryIds.stream().distinct().count() == rows
								&& primaryIds.stream().allMatch(id -> id > 0),
							phase + " persistent reference ID"
						));
					}
				}
			});
		}

		/**
		 * Reads internal IDs for the named reference in the observed body.
		 *
		 * @param snapshot observed body and index state
		 * @param owner    primary key of the reference owner
		 * @param name     reference or attribute name
		 * @param target   referenced member primary key
		 * @return stored internal reference IDs
		 */
		@Nonnull
		private List<Integer> ids(@Nonnull WriteSnapshot snapshot, int owner, @Nonnull String name, int target) {
			final StoredBody body = snapshot.bodies().get(owner);
			return body == null ? List.of() : body.references().stream()
				.filter(ref -> name.equals(ref.values().name()) && ref.values().target() == target)
				.map(StoredReference::id).toList();
		}

		/**
		 * Closes the database and removes the fixture storage.
		 */
		@Override
		public void close() {
			closeAndCleanUp(this.evita, this.paths);
		}
	}

	/**
	 * Stores a new member in the requested scope.
	 *
	 * @param session catalog session
	 * @param id      member primary key
	 * @param scope   member scope
	 * @param size    value of the member size attribute
	 * @return member upsert result
	 */
	@Nonnull
	private static Object create(@Nonnull EvitaSessionContract session, int id, @Nonnull Scope scope, int size) {
		return session.createNewEntity(MEMBER, id).setScope(scope).setAttribute("size", size).upsertVia(session);
	}

	/**
	 * Describes an expected reference with an optional level.
	 *
	 * @param name   reference or attribute name
	 * @param target referenced member primary key
	 * @param level  value of the reference level attribute
	 * @return expected reference values
	 */
	@Nonnull
	private static ReferenceValues reference(@Nonnull String name, int target, @Nullable Integer level) {
		return new ReferenceValues(name, target, level, null, null);
	}

	/**
	 * Describes an expected member body and orders its references for comparison.
	 *
	 * @param scope      member scope
	 * @param size       value of the member size attribute
	 * @param references expected reference rows
	 * @return expected stored body
	 */
	@Nonnull
	private static WriteBody body(@Nonnull Scope scope, int size, @Nonnull ReferenceValues... references) {
		return new WriteBody(
			scope, size, List.of(references).stream().sorted(Comparator.comparing(ReferenceValues::toString)).toList()
		);
	}

	/**
	 * Describes a self-reference pair with an optional local mirror note.
	 *
	 * @param rank representative rank attribute
	 * @param note local attribute of the mirror, or null
	 * @return expected bodies keyed by member ID
	 */
	@Nonnull
	private static Map<Integer, WriteBody> rankedBody(int rank, @Nullable String note) {
		return Map.of(1, body(Scope.LIVE, 30,
			new ReferenceValues(PRIMARY, 1, null, rank, null), new ReferenceValues(REFLECTED, 1, null, rank, note)));
	}

	/**
	 * Creates an upsert mutation for one reference attribute.
	 *
	 * @param key   identifier of the reference row
	 * @param name  reference or attribute name
	 * @param value attribute value to store
	 * @return reference attribute mutation
	 */
	@Nonnull
	private static ReferenceAttributeMutation attribute(
		@Nonnull ReferenceKey key,
		@Nonnull String name,
		@Nonnull Serializable value
	) {
		return new ReferenceAttributeMutation(key, new UpsertAttributeMutation(name, value));
	}
}
