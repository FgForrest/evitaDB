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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.AssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.RemoveAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.ParentMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.SetParentMutation;
import io.evitadb.api.requestResponse.data.mutation.price.PriceMutation;
import io.evitadb.api.requestResponse.data.mutation.price.RemovePriceMutation;
import io.evitadb.api.requestResponse.data.mutation.price.SetPriceInnerRecordHandlingMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.scope.SetEntityScopeMutation;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the completeness of {@link Entity#mutateEntity(io.evitadb.api.requestResponse.schema.EntitySchemaContract,
 * Entity, java.util.Collection)}'s type dispatch against the sealed {@link LocalMutation} hierarchy.
 *
 * That dispatch is an `if / else if instanceof` chain over seven types. It used to end without a trailing `else`,
 * so a mutation matching none of the seven was silently dropped and the entity was rebuilt as though the change
 * had never been requested. The chain now ends in a throw, and `LocalMutation` is sealed so that the throw cannot
 * actually be reached by any type that can exist.
 *
 * Two independent guards are asserted here, because the throw itself is no longer reachable from a test - sealing
 * makes a foreign `LocalMutation` implementation impossible to construct in-tree, and that is the point:
 *
 * 1. **Compile time** - {@link #dispatchBranchOf(LocalMutation)} repeats the seven branches as a pattern `switch`
 *    with no `default`. javac accepts that only while the seven exhaust the sealed closure, so a new permitted
 *    subtype that the production chain would drop stops this file from compiling.
 * 2. **Run time** - the closure is walked reflectively and every concrete mutation in it is checked against the
 *    seven branch types, and every type in it is checked to be `sealed` or `final` (a `non-sealed` member would
 *    re-open the hierarchy and make the dropped-mutation path reachable again).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Entity#mutateEntity local mutation dispatch coverage")
@Tag(CONTRACT)
@Tag(SCHEMA)
class LocalMutationDispatchCoverageTest {

	/**
	 * The seven types `Entity#mutateEntity` tests with `instanceof`, in the order it tests them.
	 */
	private static final List<Class<?>> DISPATCH_BRANCHES = List.of(
		ParentMutation.class,
		AttributeMutation.class,
		AssociatedDataMutation.class,
		ReferenceMutation.class,
		PriceMutation.class,
		SetPriceInnerRecordHandlingMutation.class,
		SetEntityScopeMutation.class
	);

	@Test
	@DisplayName("should dispatch every concrete LocalMutation to one of the seven branches")
	void shouldDispatchEveryConcreteLocalMutationToOneOfTheBranches() {
		final List<Class<?>> concreteMutations = new ArrayList<>(32);
		collectClosure(LocalMutation.class, new LinkedHashSet<>(), concreteMutations);

		assertFalse(
			concreteMutations.isEmpty(),
			"the sealed closure walk returned no concrete mutation - the walk is broken, not the dispatch"
		);

		final List<Class<?>> unreachable = concreteMutations.stream()
			.filter(mutation -> DISPATCH_BRANCHES.stream().noneMatch(branch -> branch.isAssignableFrom(mutation)))
			.toList();

		assertTrue(
			unreachable.isEmpty(),
			() -> "Entity#mutateEntity has no branch for: "
				+ unreachable.stream().map(Class::getName).collect(Collectors.joining(", "))
				+ " - such a mutation would reach the trailing throw instead of being applied"
		);
	}

	@Test
	@DisplayName("should keep the whole LocalMutation closure sealed down to final leaves")
	void shouldKeepTheWholeClosureSealedDownToFinalLeaves() {
		assertTrue(LocalMutation.class.isSealed(), "LocalMutation must stay sealed");

		final Set<Class<?>> closure = new LinkedHashSet<>();
		collectClosure(LocalMutation.class, closure, new ArrayList<>(32));

		final List<Class<?>> open = closure.stream()
			.filter(type -> !type.isSealed() && !Modifier.isFinal(type.getModifiers()))
			.toList();

		assertTrue(
			open.isEmpty(),
			() -> "these members of the LocalMutation closure are neither sealed nor final: "
				+ open.stream().map(Class::getName).collect(Collectors.joining(", "))
				+ " - an open member lets a foreign subtype into Entity#mutateEntity again"
		);
	}

	@Test
	@DisplayName("should route a representative mutation of each branch to that branch")
	void shouldRouteRepresentativeMutationOfEachBranchToThatBranch() {
		assertEquals("parent", dispatchBranchOf(new SetParentMutation(1)));
		assertEquals("attribute", dispatchBranchOf(new RemoveAttributeMutation("code")));
		assertEquals("associatedData", dispatchBranchOf(new RemoveAssociatedDataMutation("labels")));
		assertEquals("reference", dispatchBranchOf(new RemoveReferenceMutation("categories", 1)));
		assertEquals(
			"price",
			dispatchBranchOf(new RemovePriceMutation(1, "basic", Currency.getInstance("EUR")))
		);
		assertEquals(
			"innerRecordHandling",
			dispatchBranchOf(new SetPriceInnerRecordHandlingMutation(PriceInnerRecordHandling.NONE))
		);
		assertEquals("scope", dispatchBranchOf(new SetEntityScopeMutation(Scope.ARCHIVED)));
	}

	/**
	 * Repeats `Entity#mutateEntity`'s seven branches as a pattern `switch` **with no `default` arm**. javac accepts
	 * this method only while the seven cover the sealed {@link LocalMutation} closure, so adding a permitted subtype
	 * the production chain does not handle breaks this file at compile time. Removing any single arm below makes
	 * javac reject the switch with "the switch expression does not cover all possible input values", which is what
	 * makes the absent `default` a proof rather than an omission.
	 */
	@Nonnull
	private static String dispatchBranchOf(@Nonnull LocalMutation<?, ?> localMutation) {
		return switch (localMutation) {
			case ParentMutation ignored -> "parent";
			case AttributeMutation ignored -> "attribute";
			case AssociatedDataMutation ignored -> "associatedData";
			case ReferenceMutation<?> ignored -> "reference";
			case PriceMutation ignored -> "price";
			case SetPriceInnerRecordHandlingMutation ignored -> "innerRecordHandling";
			case SetEntityScopeMutation ignored -> "scope";
		};
	}

	/**
	 * Walks the `permits` graph rooted at `type`, recording every reachable type in `closure` and every
	 * instantiable one in `concreteMutations`. The graph is a DAG rather than a tree - `UpsertPriceMutation` and
	 * the three schema-evolving reference mutations are reachable both through their superclass and through
	 * `SchemaEvolvingLocalMutation` - so `closure` doubles as the visited set.
	 */
	private static void collectClosure(
		@Nonnull Class<?> type,
		@Nonnull Set<Class<?>> closure,
		@Nonnull List<Class<?>> concreteMutations
	) {
		if (!closure.add(type)) {
			return;
		}
		if (!type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
			concreteMutations.add(type);
		}
		final Class<?>[] permitted = type.getPermittedSubclasses();
		if (permitted != null) {
			for (final Class<?> permittedSubType : permitted) {
				collectClosure(permittedSubType, closure, concreteMutations);
			}
		}
	}

}
