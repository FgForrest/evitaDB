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

import io.evitadb.api.query.require.HierarchyContent;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.exception.GenericEvitaInternalError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Optional;

/**
 * Terminator stored in the parent slot of an {@link EntityDecorator} to state that the parent chain has been fully
 * resolved and that there is nothing above the entity holding it. It covers a genuine hierarchy root, a chain cut
 * short by {@link HierarchyContent#getStopAt()} and a chain cut short because an ancestor body could not be
 * materialized - all three are indistinguishable to a client, which simply sees no further ancestor.
 *
 * Its reason for existing is the delegate fallback: an {@link EntityDecorator} whose parent slot is empty cannot
 * otherwise tell "nobody resolved my parent, so show the one my delegate knows about" from "my parent was resolved
 * and there is none". Together with the two value-carrying states, the slot therefore encodes four outcomes:
 *
 * **Parent slot of {@link EntityDecorator}**
 *
 * | slot value | meaning |
 * |---|---|
 * | `null` | the parent was never resolved - the decorator falls back to its delegate's parent pointer |
 * | {@link SealedEntity} | the parent was resolved and its body is present |
 * | {@link EntityReferenceWithParent} | the parent was resolved as a bodyless pointer |
 * | {@link #INSTANCE} | the parent chain was resolved and ends here |
 *
 * A bodyless pointer is stored when the requested body could not be materialized. The chain may continue above such
 * a pointer, so an ancestor carrying a body may well sit above one.
 *
 * The terminator belongs to the decorator slot only. It must never be stored in
 * {@link EntityReferenceWithParent#parentEntity()}, which is null-terminated - a plain reference has no delegate to
 * fall back to, so {@code null} is unambiguous there. It also never reaches a client: the decorator translates it to
 * an empty {@link EntityDecorator#getParentEntity()} result, which is why {@link #getType()} and
 * {@link #getPrimaryKey()} treat a call as a programming error rather than inventing a value.
 *
 * Class is immutable and stateless on purpose - a single shared instance is used everywhere, and Java
 * de-serialization is resolved back to it so that identity comparison keeps working across a serialization round trip.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see EntityDecorator#getParentEntity()
 */
@Immutable
@ThreadSafe
public final class ParentChainEnd implements EntityClassifierWithParent {
	@Serial private static final long serialVersionUID = 5980484923845073141L;
	/**
	 * The one and only instance of the terminator - compare against it by identity, or better, via
	 * {@link #isChainEnd(EntityClassifierWithParent)}.
	 */
	public static final ParentChainEnd INSTANCE = new ParentChainEnd();

	private ParentChainEnd() {
	}

	/**
	 * Returns TRUE when the passed parent slot value marks a fully resolved parent chain that ends at the entity
	 * holding the slot. The check also accepts the deprecated {@link EntityClassifierWithParent#CONCEALED_ENTITY},
	 * which used to play this role and may still be produced by code that has not been migrated yet.
	 *
	 * @param parentEntity the raw value of the parent slot, may be NULL when the parent was never resolved
	 * @return TRUE when the value marks the end of a resolved parent chain
	 */
	@SuppressWarnings("deprecation")
	public static boolean isChainEnd(@Nullable EntityClassifierWithParent parentEntity) {
		return parentEntity == INSTANCE || parentEntity == EntityClassifierWithParent.CONCEALED_ENTITY;
	}

	/**
	 * The terminator closes the chain, so there is never an ancestor above it.
	 *
	 * @return always an empty result - this is what turns the decorator slot into an empty
	 *         {@link EntityDecorator#getParentEntity()} for the client
	 */
	@Nonnull
	@Override
	public Optional<EntityClassifierWithParent> getParentEntity() {
		return Optional.empty();
	}

	/**
	 * The terminator is not an entity and therefore carries no entity type. Callers must recognize it with
	 * {@link #isChainEnd(EntityClassifierWithParent)} before reading anything out of a parent slot.
	 *
	 * @return never returns - the call is always a programming error
	 * @throws GenericEvitaInternalError always, because the terminator has no type to report
	 */
	@Nonnull
	@Override
	public String getType() {
		throw new GenericEvitaInternalError(
			"Parent chain terminator carries no entity type - it must be recognized by " +
				"ParentChainEnd#isChainEnd before its contents are read."
		);
	}

	/**
	 * The terminator is not an entity and therefore carries no primary key. Callers must recognize it with
	 * {@link #isChainEnd(EntityClassifierWithParent)} before reading anything out of a parent slot.
	 *
	 * @return never returns - the call is always a programming error
	 * @throws GenericEvitaInternalError always, because the terminator has no primary key to report
	 */
	@Nullable
	@Override
	public Integer getPrimaryKey() {
		throw new GenericEvitaInternalError(
			"Parent chain terminator carries no primary key - it must be recognized by " +
				"ParentChainEnd#isChainEnd before its contents are read."
		);
	}

	/**
	 * Renders the terminator in a form safe to print - unlike {@link #getType()} and {@link #getPrimaryKey()} this
	 * method never throws, so a parent slot can be logged without being classified first.
	 *
	 * @return a constant human-readable marker of the chain end
	 */
	@Nonnull
	@Override
	public String toString() {
		return "parent chain end";
	}

	/**
	 * Keeps the singleton property across a Java de-serialization round trip so that identity comparison against
	 * {@link #INSTANCE} stays valid.
	 *
	 * @return the shared {@link #INSTANCE}
	 */
	@Serial
	@Nonnull
	private Object readResolve() {
		return INSTANCE;
	}

}
