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

package io.evitadb.index.usage;

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.usage.SchemaCapabilityKey.Capability;
import io.evitadb.index.usage.SchemaCapabilityKey.ElementKind;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * All {@link SchemaCapabilityUsage} holders one owner keeps - an entity collection for the capabilities its own schema
 * declares, the catalog for the globally-unique ones. It is the thing that turns a {@link SchemaCapabilityKey} into the
 * holder that counts against it, and the thing that throws that holder away when the schema stops declaring the
 * capability.
 *
 * # Resolve once, keep the reference
 *
 * {@link #resolve(SchemaCapabilityKey)} is a **setup-time** operation - query-context creation, mutation-executor
 * creation - and the reference it hands back is what the hot path then uses directly. This is a contract rather than
 * advice: the whole design rests on an event costing one {@link java.util.concurrent.atomic.LongAdder} increment, and
 * resolving per event would put a hash, an equality comparison and a possible map write in front of every one of them.
 *
 * Concretely: **never call this per query-plan node, per index touched or per mutation applied.** Resolve when the
 * context or the executor is built, hold the holder in a field or a local, and increment it.
 *
 * # Reading it back
 *
 * {@link #listUsages()} exists for the diagnostic surface and allocates freely, because it runs when an operator asks
 * a question and never on a query or write path.
 *
 * It returns a {@link List} of {@link UsageEntry} rather than a {@link java.util.stream.Stream} of
 * {@link Entry Map.Entry} on purpose: the caller is a converter filling one row per entry, so it wants a sized,
 * re-iterable collection of a type that says what its two halves are, not a one-shot pipeline over a pair type whose
 * `getValue()` tells a reader nothing. The list is unmodifiable, which fixes **which capabilities exist**; the holders
 * inside it stay live, so the counts a caller reads are current rather than frozen at the call. Nothing needs them
 * frozen - two counters of the same row may be read a nanosecond apart and the reading is still the one the operator
 * asked for.
 *
 * # Pruning
 *
 * {@link #pruneFor(EntitySchemaContract)} implements the rule that **a capability dropped from the schema and added
 * back does not inherit the old numbers**: it takes the new schema version, and every key whose element, capability or
 * scope that schema no longer declares loses its holder. A later {@link #resolve(SchemaCapabilityKey)} of the same key
 * therefore mints a fresh holder with a fresh observation window - which is the honest reading, since the capability
 * was genuinely not maintained for the interval in between.
 *
 * Which overload applies is decided by the owner, not by the caller's convenience: a collection prunes against the
 * entity schema it has just adopted, a catalog against its {@link #pruneFor(CatalogSchemaContract) catalog schema}.
 * A registry only ever sees one of the two, because only one kind of owner ever resolves keys into it.
 *
 * # Concurrency
 *
 * A plain {@link ConcurrentHashMap}, non-transactional exactly like the holders it stores, and shared across catalog
 * versions by reference. Concurrent {@link #resolve(SchemaCapabilityKey)} of the same key from any number of threads
 * yields the same holder to all of them, so no recording can be lost to a lost-update race on the map itself.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see SchemaCapabilityKey
 * @see SchemaCapabilityUsage
 */
public final class SchemaCapabilityUsageRegistry {

	/**
	 * The holders, keyed by the capability they count. Sized for a schema rather than for data - a collection declares
	 * dozens of indexed elements, not thousands, and each of them contributes at most one entry per capability and
	 * scope it declares.
	 */
	private final ConcurrentHashMap<SchemaCapabilityKey, SchemaCapabilityUsage> usages = new ConcurrentHashMap<>(64);

	/**
	 * Hands back the holder counting the given capability, creating it on first sight.
	 *
	 * **Call this once per key per setup, and keep what it returns.** See the class documentation for why: this is the
	 * only method on the registry that may hash, compare and write, and the hot paths are built on never doing any of
	 * those.
	 *
	 * @param key the capability to count against
	 * @return the holder for that capability - the same instance for every caller resolving the same key, until a
	 * {@link #pruneFor(EntitySchemaContract)} removes it
	 */
	@Nonnull
	public SchemaCapabilityUsage resolve(@Nonnull SchemaCapabilityKey key) {
		Objects.requireNonNull(key, "Schema capability key is mandatory.");
		// the mapping function captures nothing, so it is a constant rather than an allocation per miss - and
		// computeIfAbsent is what guarantees one holder per key however many threads race to be the first
		return this.usages.computeIfAbsent(key, theKey -> new SchemaCapabilityUsage());
	}

	/**
	 * Every capability observed so far, together with its live holder - the input the diagnostic surface turns into
	 * rows.
	 *
	 * Order is unspecified: the map is a hash map, and the surface that presents these decides how to order them.
	 *
	 * @return an unmodifiable list of the registry's entries, empty when nothing has been resolved yet
	 */
	@Nonnull
	public List<UsageEntry> listUsages() {
		final List<UsageEntry> result = new ArrayList<>(this.usages.size());
		for (final Entry<SchemaCapabilityKey, SchemaCapabilityUsage> entry : this.usages.entrySet()) {
			result.add(new UsageEntry(entry.getKey(), entry.getValue()));
		}
		return Collections.unmodifiableList(result);
	}

	/**
	 * How many capabilities the registry currently holds - bounded by the schema, not by the data.
	 *
	 * @return the number of entries
	 */
	public int size() {
		return this.usages.size();
	}

	/**
	 * Drops every holder whose capability the given schema version no longer declares.
	 *
	 * Deliberately stated as *"here is the new truth, discard what disagrees with it"* rather than as a reaction to a
	 * particular schema mutation: the caller does not have to work out what changed, and no combination of mutations
	 * can leave the registry holding a key the schema does not back. The cost is a full pass over the entries, which is
	 * irrelevant - this runs when a collection adopts a new schema version, not on any path a query or a write takes.
	 *
	 * A key survives only when **all** of the following hold in the new schema:
	 *
	 * - the element is still declared, under the same container ({@link SchemaCapabilityKey#containerName()}) and of
	 *   the same kind ({@link SchemaCapabilityKey#elementKind()});
	 * - and it still declares the key's {@link SchemaCapabilityKey#capability()} in the key's
	 *   {@link SchemaCapabilityKey#scope()}, as
	 *   {@link io.evitadb.core.query.AttributeSchemaAccessor} understands that word.
	 *
	 * There is deliberately **no separate check that the container reference is still indexed** in the scope. Schema
	 * validation already refuses to let a reference that is not indexed in a scope carry a filterable, sortable or
	 * unique attribute there, so the condition can never change the outcome for a schema the engine would accept - and
	 * a branch that no valid input can reach is a branch no test can pin.
	 *
	 * @param entitySchema the schema version the owner has just adopted
	 */
	public void pruneFor(@Nonnull EntitySchemaContract entitySchema) {
		Objects.requireNonNull(entitySchema, "Entity schema is mandatory.");
		this.usages.keySet().removeIf(key -> !isDeclaredBy(entitySchema, key));
	}

	/**
	 * The catalog-owner's counterpart of {@link #pruneFor(EntitySchemaContract)} - *"here is the catalog schema version
	 * just adopted, discard what disagrees with it"*.
	 *
	 * A catalog registry counts exactly one kind of element: **an attribute the catalog schema itself declares**, which
	 * is why this overload asks the catalog schema for the attribute and nothing else. Whether the attribute still
	 * carries the key's capability in the key's scope is then decided by the very same rule an entity attribute is
	 * judged by, deliberately: a global attribute's `filterable()`, `sortable()` and `unique()` flags are declared on
	 * the catalog schema and inherited by every collection using it, so *"does the schema still declare this flag"* is
	 * the same question here as there. Global uniqueness is not tested separately for that reason - it already implies
	 * uniqueness within the collection, so the `UNIQUE` and `FILTER` entries of a globally-unique attribute survive.
	 *
	 * @param catalogSchema the catalog schema version the catalog has just adopted
	 * @throws GenericEvitaInternalError when the registry holds a key no catalog schema could ever back - a reference
	 *                                   attribute or a sortable compound, neither of which the catalog declares
	 */
	public void pruneFor(@Nonnull CatalogSchemaContract catalogSchema) {
		Objects.requireNonNull(catalogSchema, "Catalog schema is mandatory.");
		this.usages.keySet().removeIf(key -> !isDeclaredBy(catalogSchema, key));
	}

	/**
	 * Decides whether the given catalog schema version still backs the capability the key names.
	 *
	 * @param catalogSchema the schema version to check against
	 * @param key           the capability in question
	 * @return true when the catalog schema still declares it and the entry may stay
	 */
	private static boolean isDeclaredBy(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull SchemaCapabilityKey key
	) {
		if (key.elementKind() != ElementKind.ATTRIBUTE || key.containerName() != null) {
			// dropping such a key silently would hide whoever minted it: a catalog declares no references and no
			// compounds, so this key could never have matched any catalog schema and is a bug at its resolve site
			throw new GenericEvitaInternalError(
				"A catalog usage registry cannot hold " + key.elementKind() + " `" + key.elementName() + "`" +
					(key.containerName() == null ? "" : " of container `" + key.containerName() + "`") +
					" - only attributes the catalog schema declares itself."
			);
		}
		return declaresCapability(catalogSchema.getAttribute(key.elementName()).orElse(null), key);
	}

	/**
	 * Decides whether the given schema version still backs the capability the key names.
	 *
	 * @param entitySchema the schema version to check against
	 * @param key          the capability in question
	 * @return true when the schema still declares it and the entry may stay
	 */
	private static boolean isDeclaredBy(@Nonnull EntitySchemaContract entitySchema, @Nonnull SchemaCapabilityKey key) {
		final String containerName = key.containerName();
		if (containerName == null) {
			return switch (key.elementKind()) {
				case ATTRIBUTE -> declaresCapability(
					entitySchema.getAttribute(key.elementName()).orElse(null), key
				);
				case SORTABLE_COMPOUND -> declaresCapability(
					entitySchema.getSortableAttributeCompound(key.elementName()).orElse(null), key
				);
			};
		}

		final ReferenceSchemaContract reference = entitySchema.getReference(containerName).orElse(null);
		if (reference == null) {
			return false;
		}
		return switch (key.elementKind()) {
			case ATTRIBUTE -> declaresCapability(reference.getAttribute(key.elementName()).orElse(null), key);
			case SORTABLE_COMPOUND -> declaresCapability(
				reference.getSortableAttributeCompound(key.elementName()).orElse(null), key
			);
		};
	}

	/**
	 * Decides whether an attribute schema still carries the key's capability in the key's scope.
	 *
	 * @param attributeSchema the attribute as the new schema version declares it, or null when it declares none
	 * @param key             the capability in question
	 * @return true when the attribute exists and carries the capability in that scope
	 */
	private static boolean declaresCapability(
		@Nullable AttributeSchemaContract attributeSchema,
		@Nonnull SchemaCapabilityKey key
	) {
		if (attributeSchema == null) {
			return false;
		}
		final Scope scope = key.scope();
		// no `default` branch on purpose: an exhaustive switch over Capability makes a future value a compile error
		// here, which catches the omission earlier and more loudly than any runtime throw could
		return switch (key.capability()) {
			// `unique()` implies `filterable()` - the schema even refuses to let both be declared explicitly, and
			// AttributeSchemaAccessor lets a filter reach a unique-only attribute on exactly that basis. Testing only
			// the `filterable()` flag here would therefore prune a live capability the moment a schema was re-adopted
			case FILTER -> attributeSchema.isFilterableInScope(scope) || attributeSchema.isUniqueInScope(scope);
			case SORT -> attributeSchema.isSortableInScope(scope);
			// covers both uniqueness flavours - within the collection and within a locale - because both cost a
			// uniqueness index, which is what the entry measures
			case UNIQUE -> attributeSchema.isUniqueInScope(scope);
		};
	}

	/**
	 * Decides whether a sortable attribute compound still exists and is still indexed in the key's scope.
	 *
	 * @param compoundSchema the compound as the new schema version declares it, or null when it declares none
	 * @param key            the capability in question - always {@link Capability#SORT}, a compound has no other
	 * @return true when the compound exists and is indexed in that scope
	 */
	private static boolean declaresCapability(
		@Nullable SortableAttributeCompoundSchemaContract compoundSchema,
		@Nonnull SchemaCapabilityKey key
	) {
		if (key.capability() != Capability.SORT) {
			// unreachable through SchemaCapabilityKey#sortableCompound, which is the only way to name a compound - a
			// compound has nothing to filter or to be unique by, so such a key could never match any schema at all and
			// silently dropping it would hide whoever minted it
			throw new GenericEvitaInternalError(
				"Sortable attribute compound `" + key.elementName() + "` cannot carry capability " +
					key.capability() + "."
			);
		}
		return compoundSchema != null && compoundSchema.isIndexedInScope(key.scope());
	}

	/**
	 * One row of {@link #listUsages()} - a capability and the holder counting it.
	 *
	 * The holder is the live one, not a copy: a caller reads whichever counts are current when it asks. See the class
	 * documentation for why nothing here needs to be frozen.
	 *
	 * @param key   which capability the counts belong to
	 * @param usage the holder counting it
	 */
	public record UsageEntry(
		@Nonnull SchemaCapabilityKey key,
		@Nonnull SchemaCapabilityUsage usage
	) {
	}

}
