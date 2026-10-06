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
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.Functions;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * A hand-built catalog in which one representative reference key is held by partitions of both scopes - the shapes in
 * which the rows of one key must be united across representations and scopes before the key is restricted:
 *
 * - `items` (no duplicates, filterable `weight`): target {@link #TARGET_TWO_SINGLETONS} is referenced by live owner 1
 *   and archived owner 2 - two single-owner partitions, one per scope, under one key; target
 *   {@link #TARGET_SHARED_AND_SINGLETON} by live owners 3 and 4 (a shared partition) and archived owner 5 (a
 *   single-owner partition); target {@link #TARGET_WEIGHTLESS} by owner 3 alone with weight 0;
 * - `media` (duplicates told apart by the representative `role`, filterable `weight`): target
 *   {@link #MEDIA_BOTH_SCOPES} in role `a` by live owner 1 and archived owner 2 and in role `b` by owner 1; target
 *   {@link #MEDIA_DUPLICATES} in role `a` by live owner 6 and archived owner 7 and in role `b` by owner 6 with weight 0;
 * - `solo` (no duplicates): owner `n` references target `100 + n` alone - every partition is single-owner.
 *
 * Owners 2, 5 and 7 are archived once the catalog is live; every reference is indexed for filtering in both scopes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class CrossScopeSingletonFixture {
	static final String ENTITY_OWNER = "owner";
	static final String ENTITY_TARGET = "target";
	static final Set<String> ENTITY_TYPES = Set.of(ENTITY_OWNER, ENTITY_TARGET);
	static final String REFERENCE_ITEMS = "items";
	static final String REFERENCE_MEDIA = "media";
	static final String REFERENCE_SOLO = "solo";
	static final String ATTRIBUTE_WEIGHT = "weight";
	static final String ATTRIBUTE_ROLE = "role";
	static final String ROLE_A = "a";
	static final String ROLE_B = "b";
	static final int TARGET_TWO_SINGLETONS = 10;
	static final int TARGET_WEIGHTLESS = 11;
	static final int TARGET_SHARED_AND_SINGLETON = 20;
	static final int MEDIA_BOTH_SCOPES = 30;
	static final int MEDIA_DUPLICATES = 32;
	static final int OWNER_COUNT = 7;
	/**
	 * The owners archived once the catalog is live.
	 */
	static final int[] ARCHIVED_OWNERS = {2, 5, 7};
	private static final Scope[] BOTH_SCOPES = {Scope.LIVE, Scope.ARCHIVED};

	private CrossScopeSingletonFixture() {
	}

	/**
	 * Defines the schema and writes the entities - meant to run in the warm-up session of a fresh catalog whose
	 * collections are maintained from the first write.
	 *
	 * @param session the session to write in
	 */
	static void setUp(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_TARGET).updateVia(session);
		session.defineEntitySchema(ENTITY_OWNER)
			.withReferenceToEntity(
				REFERENCE_ITEMS, ENTITY_TARGET, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringInScope(BOTH_SCOPES)
					.withAttribute(ATTRIBUTE_WEIGHT, Integer.class, thatIs -> thatIs.filterableInScope(BOTH_SCOPES))
			)
			.withReferenceToEntity(
				REFERENCE_MEDIA, ENTITY_TARGET, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				whichIs -> whichIs
					.indexedForFilteringInScope(BOTH_SCOPES)
					.withAttribute(
						ATTRIBUTE_ROLE, String.class, thatIs -> thatIs.filterableInScope(BOTH_SCOPES).representative()
					)
					.withAttribute(ATTRIBUTE_WEIGHT, Integer.class, thatIs -> thatIs.filterableInScope(BOTH_SCOPES))
			)
			.withReferenceToEntity(
				REFERENCE_SOLO, ENTITY_TARGET, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.indexedForFilteringInScope(BOTH_SCOPES)
			)
			.updateVia(session);
		for (final int target : new int[]{
			TARGET_TWO_SINGLETONS, TARGET_WEIGHTLESS, TARGET_SHARED_AND_SINGLETON, MEDIA_BOTH_SCOPES, MEDIA_DUPLICATES
		}) {
			session.upsertEntity(session.createNewEntity(ENTITY_TARGET, target));
		}
		for (int owner = 1; owner <= OWNER_COUNT; owner++) {
			session.upsertEntity(session.createNewEntity(ENTITY_TARGET, 100 + owner));
		}
		for (int owner = 1; owner <= OWNER_COUNT; owner++) {
			final EntityBuilder builder = session.createNewEntity(ENTITY_OWNER, owner)
				.setReference(REFERENCE_SOLO, 100 + owner);
			switch (owner) {
				case 1 -> {
					item(builder, TARGET_TWO_SINGLETONS, 5);
					media(builder, MEDIA_BOTH_SCOPES, ROLE_A, 5);
					media(builder, MEDIA_BOTH_SCOPES, ROLE_B, 5);
				}
				case 2 -> {
					item(builder, TARGET_TWO_SINGLETONS, 5);
					media(builder, MEDIA_BOTH_SCOPES, ROLE_A, 5);
				}
				case 3 -> {
					item(builder, TARGET_SHARED_AND_SINGLETON, 5);
					item(builder, TARGET_WEIGHTLESS, 0);
				}
				case 4, 5 -> item(builder, TARGET_SHARED_AND_SINGLETON, 5);
				case 6 -> {
					media(builder, MEDIA_DUPLICATES, ROLE_A, 5);
					media(builder, MEDIA_DUPLICATES, ROLE_B, 0);
				}
				case 7 -> media(builder, MEDIA_DUPLICATES, ROLE_A, 5);
				default -> throw new IllegalStateException("Unexpected owner " + owner);
			}
			session.upsertEntity(builder);
		}
	}

	/**
	 * Archives {@link #ARCHIVED_OWNERS} - meant to run once the catalog is live.
	 *
	 * @param session the session to write in
	 */
	static void archive(@Nonnull EvitaSessionContract session) {
		for (final int owner : ARCHIVED_OWNERS) {
			session.archiveEntity(ENTITY_OWNER, owner);
		}
	}

	/**
	 * Adds an `items` reference.
	 *
	 * @param builder the owner
	 * @param target  the target
	 * @param weight  the weight of the row
	 */
	private static void item(@Nonnull EntityBuilder builder, int target, int weight) {
		builder.setReference(REFERENCE_ITEMS, target, whichIs -> whichIs.setAttribute(ATTRIBUTE_WEIGHT, weight));
	}

	/**
	 * Adds a `media` reference in a role.
	 *
	 * @param builder the owner
	 * @param target  the target
	 * @param role    the role telling the duplicates apart
	 * @param weight  the weight of the row
	 */
	private static void media(@Nonnull EntityBuilder builder, int target, @Nonnull String role, int weight) {
		builder.setOrUpdateReference(
			REFERENCE_MEDIA, target, Functions.alwaysFalse(),
			whichIs -> whichIs.setAttribute(ATTRIBUTE_ROLE, role).setAttribute(ATTRIBUTE_WEIGHT, weight)
		);
	}

}
