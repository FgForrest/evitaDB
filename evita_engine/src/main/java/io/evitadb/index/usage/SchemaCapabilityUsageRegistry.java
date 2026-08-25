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
import io.evitadb.api.requestResponse.schema.FilterIndexCapability;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.HistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics.Capability;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics.ElementKind;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

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
 * # Alignment
 *
 * {@link #alignWith(EntitySchemaContract)} makes the registry **agree with a schema version**: it mints a holder for
 * every capability that schema declares and this owner's recording sites can report, and drops every key the schema no
 * longer backs. It runs when the owner comes into existence and again whenever the owner adopts a new schema version.
 *
 * Two properties follow, and both are contractual:
 *
 * - **A declared capability has a row from the moment it is declared.** That is what makes
 *   {@link SchemaCapabilityUsage#getObservedSinceMillis()} literally true - the window opens at catalog load for a
 *   capability the schema already declared, and at the schema mutation for one declared later, rather than at whenever
 *   somebody first happened to query it. It is also what lets a capability with no traffic at all be *reported* with
 *   honest zeros instead of being invisible, so that "idle" and "not declared" stop looking the same to an operator.
 * - **A capability dropped from the schema and added back does not inherit the old numbers.** The drop takes the
 *   holder; the re-declaration mints a fresh one with a fresh window - the honest reading, since the capability was
 *   genuinely not maintained in between.
 *
 * Surviving keys keep the holder they already had, counters and window included: alignment resolves rather than
 * replaces, so re-aligning on every schema change costs nothing but a map lookup per declared capability.
 *
 * Which overload applies is decided by the owner, not by the caller's convenience: a collection aligns against the
 * entity schema it has just adopted, a catalog against its {@link #alignWith(CatalogSchemaContract) catalog schema}.
 * A registry only ever sees one of the two, because only one kind of owner ever resolves keys into it.
 *
 * # Seeding is narrower than dropping, deliberately
 *
 * Dropping only ever removes, so a rule that is too permissive there costs a stale row until the next adoption.
 * Seeding *creates*, and a row nothing can ever increment is worse than the gap it fills: permanently `0 / 0` reads to
 * an operator as *"nothing uses this flag, drop it"*, which is the one action this whole surface exists to prevent.
 * The enumerations below therefore mint exactly what this owner's recording sites can file, which for the catalog is
 * strictly less than its own survival rule would let stand. Each exclusion carries its reason at the site.
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
	 * The flags an **attribute** can declare, which is the domain of
	 * {@link #declaresCapability(AttributeSchemaContract, Capability, Scope)}.
	 *
	 * Enumerated here rather than by walking {@link Capability#values()} on purpose. That enum also carries the flags
	 * of a reference and of the entity, and asking an attribute about one of those is a programming error the
	 * predicate is right to throw on - so the seeding loop must never put the question. Adding a capability to some
	 * other element's vocabulary therefore leaves this loop alone, which is the point.
	 */
	private static final Capability[] ATTRIBUTE_CAPABILITIES = {
		Capability.FILTERABLE, Capability.SUBSTRING_FILTERABLE, Capability.SORTABLE, Capability.UNIQUE
	};

	/**
	 * Hands back the holder counting the given capability, creating it on first sight.
	 *
	 * **Call this once per key per setup, and keep what it returns.** See the class documentation for why: this is the
	 * only method on the registry that may hash, compare and write, and the hot paths are built on never doing any of
	 * those.
	 *
	 * @param key the capability to count against
	 * @return the holder for that capability - the same instance for every caller resolving the same key, until an
	 * {@link #alignWith(EntitySchemaContract)} removes it
	 */
	@Nonnull
	public SchemaCapabilityUsage resolve(@Nonnull SchemaCapabilityKey key) {
		Objects.requireNonNull(key, "Schema capability key is mandatory.");
		// the mapping function captures nothing, so it is a constant rather than an allocation per miss - and
		// computeIfAbsent is what guarantees one holder per key however many threads race to be the first
		return this.usages.computeIfAbsent(key, theKey -> new SchemaCapabilityUsage());
	}

	/**
	 * Every capability the owner's schema declares, together with its live holder - the input the diagnostic surface
	 * turns into rows. A capability nothing has ever queried or written is in here too, with zero counts and no stamps;
	 * that is the point of aligning eagerly rather than resolving lazily.
	 *
	 * Order is unspecified: the map is a hash map, and the surface that presents these decides how to order them.
	 *
	 * @return an unmodifiable list of the registry's entries, empty only while the owner's schema declares no
	 * capability at all
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
	 * How many capabilities the registry currently holds - bounded by the schema, not by the data, and after an
	 * {@link #alignWith(EntitySchemaContract)} equal to the number the schema declares.
	 *
	 * @return the number of entries
	 */
	public int size() {
		return this.usages.size();
	}

	/**
	 * Makes the registry agree with the given entity schema version: **every capability that schema declares gets a
	 * holder, and every key it no longer backs loses one**.
	 *
	 * Deliberately stated as *"here is the new truth, hold exactly what it declares"* rather than as a reaction to a
	 * particular schema mutation: the caller does not have to work out what changed, and no combination of mutations
	 * can leave the registry disagreeing with the schema. The cost is a full pass over the entries plus a map lookup
	 * per declared capability, which is irrelevant - this runs when a collection is created and when it adopts a new
	 * schema version, not on any path a query or a write takes.
	 *
	 * # Which keys survive
	 *
	 * A key survives only when **all** of the following hold in the new schema:
	 *
	 * - the element is still declared, under the same container ({@link SchemaCapabilityKey#containerName()}) and of
	 *   the same kind ({@link SchemaCapabilityKey#elementKind()});
	 * - and it still declares the key's {@link SchemaCapabilityKey#capability()} in the key's
	 *   {@link SchemaCapabilityKey#scope()}, as
	 *   {@link io.evitadb.core.query.AttributeSchemaAccessor} understands that word.
	 *
	 * # Which keys are minted
	 *
	 * The same predicate - {@link #declaresCapability} - applied to every element the schema enumerates, so that
	 * seeding and dropping cannot disagree about what a *declared* capability is. Everything the enumeration yields is
	 * reachable from a recording site: an entity or reference attribute's `FILTERABLE`, `SORTABLE` and `UNIQUE` are
	 * filed by {@link io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor#reportAttributeTouched} on any
	 * write that touches it and requested through `AttributeSchemaAccessor`, and a compound's `SORTABLE` by
	 * `reportSortableCompoundTouched`. The one condition the enumeration adds on top of the predicate is on the
	 * *container* rather than on the element - see {@link #maintainsElementsIn} for why a reference has to be asked
	 * separately, and why a reflected reference that is not attached yet is skipped.
	 *
	 * A **surviving key keeps the holder it already had**, because the mint goes through
	 * {@link #resolve(SchemaCapabilityKey)}. Replacing holders here instead would reset every counter in the collection
	 * on every schema change, which is to say on precisely the collections worth measuring.
	 *
	 * @param entitySchema the schema version the owner has just adopted, or the one it was created with
	 */
	public void alignWith(@Nonnull EntitySchemaContract entitySchema) {
		Objects.requireNonNull(entitySchema, "Entity schema is mandatory.");
		this.usages.keySet().removeIf(key -> !isDeclaredBy(entitySchema, key));
		final String entityType = entitySchema.getName();
		for (final Scope scope : Scope.values()) {
			seedAttributes(entitySchema.getAttributes().values(), null, scope);
			seedCompounds(entitySchema.getSortableAttributeCompounds().values(), null, scope);
			seedEntityCapabilities(entitySchema, entityType, scope);
			for (final ReferenceSchemaContract reference : entitySchema.getReferences().values()) {
				if (maintainsElementsIn(reference, scope)) {
					final String referenceName = reference.getName();
					seedAttributes(reference.getAttributes().values(), referenceName, scope);
					seedCompounds(reference.getSortableAttributeCompounds().values(), referenceName, scope);
					seedReferenceCapabilities(reference, referenceName, scope);
				}
			}
		}
	}

	/**
	 * The catalog-owner's counterpart of {@link #alignWith(EntitySchemaContract)} - *"here is the catalog schema
	 * version just adopted, hold exactly what it declares"*.
	 *
	 * A catalog registry counts exactly one kind of element: **an attribute the catalog schema itself declares**, which
	 * is why this overload asks the catalog schema for the attribute and nothing else. Whether an existing key survives
	 * is then decided by the very same rule an entity attribute is judged by, deliberately: a global attribute's
	 * `filterable()`, `sortable()` and `unique()` flags are declared on the catalog schema and inherited by every
	 * collection using it, so *"does the schema still declare this flag"* is the same question here as there. Global
	 * uniqueness is not tested separately for that reason - it already implies uniqueness within the collection, so the
	 * `UNIQUE` and `FILTERABLE` entries of a globally-unique attribute survive.
	 *
	 * # What is minted, and what is deliberately not
	 *
	 * Only **`FILTERABLE` and `UNIQUE` of an attribute that is `uniqueGloballyInScope`**, which is exactly the pair
	 * {@link io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor#reportAttributeTouched} files into this
	 * registry, and exactly the capabilities the catalog's own
	 * {@link io.evitadb.index.attribute.GlobalUniqueIndex} maintains. Two things the survival rule above would tolerate
	 * are therefore **not** seeded, because nothing could ever increment them:
	 *
	 * - **`SORTABLE` of a global attribute.** Nothing can file it from either side. No write does - a global
	 *   attribute's sort index lives in each collection that declares the attribute, never in the catalog - and the
	 *   request side drops it deliberately too, in
	 *   {@link io.evitadb.core.query.AttributeSchemaAccessor#recordRequestedTraits}, so that a collection-less
	 *   `orderBy` cannot mint a row whose maintenance count is zero by construction. This registry and the catalog's
	 *   physical indexes therefore describe the same set.
	 * - **`FILTERABLE` / `UNIQUE` of a global attribute that is not globally unique.** The catalog keeps no index
	 *   for it; its filter and uniqueness indexes belong to the collections declaring it, and are seeded in *their*
	 *   registries by {@link #alignWith(EntitySchemaContract)} - a global attribute is a member of the entity schema
	 *   of every collection that uses it.
	 *
	 * Unlike `SORTABLE`, that second one stays *reachable* rather than forbidden: a collection-less filter naming
	 * such an attribute still resolves a holder lazily, and the survival rule keeps it, which costs a late-opened
	 * observation window on a row an operator asked for by issuing that query. Seeding it instead would trade that
	 * transient cost for a permanently-zero row on every catalog that never issues such a query.
	 *
	 * @param catalogSchema the catalog schema version the catalog has just adopted, or the one it was created with
	 * @throws GenericEvitaInternalError when the registry holds a key no catalog schema could ever back - a reference
	 *                                   attribute or a sortable compound, neither of which the catalog declares
	 */
	public void alignWith(@Nonnull CatalogSchemaContract catalogSchema) {
		Objects.requireNonNull(catalogSchema, "Catalog schema is mandatory.");
		this.usages.keySet().removeIf(key -> !isDeclaredBy(catalogSchema, key));
		for (final GlobalAttributeSchemaContract attributeSchema : catalogSchema.getAttributes().values()) {
			final String attributeName = attributeSchema.getName();
			for (final Scope scope : Scope.values()) {
				if (attributeSchema.isUniqueGloballyInScope(scope)) {
					resolve(
						new SchemaCapabilityKey(
							ElementKind.ATTRIBUTE, null, attributeName, Capability.FILTERABLE, scope
						)
					);
					resolve(
						new SchemaCapabilityKey(
							ElementKind.ATTRIBUTE, null, attributeName, Capability.UNIQUE, scope
						)
					);
				}
			}
		}
	}

	/**
	 * Whether one flag of the given reference can be read at all right now.
	 *
	 * A {@link ReflectedReferenceSchemaContract} inherits *some* of its flags from the reference it mirrors, and
	 * asking for an inherited one before that reference is attached throws instead of answering. Only the inherited
	 * ones, though - which is the whole reason this is asked per flag rather than per reference. A reflected
	 * reference that states its own `faceted()` answers perfectly well while detached, and skipping it wholesale
	 * would drop a row the schema really does declare, leaving a live capability invisible.
	 *
	 * The seeding enumeration is deliberately blunter - see {@link #maintainsElementsIn}, which skips an unattached
	 * reflection whole. That asymmetry is intentional and runs the safe way round: seeding too little costs a row
	 * that appears at the next adoption, while dropping too much would lose counters that had been accumulating.
	 *
	 * @param referenceSchema  the reference whose flag is about to be read
	 * @param inheritanceTest  which flag's inheritance to test - a method reference such as
	 *                         {@link ReflectedReferenceSchemaContract#isFacetedInherited()}
	 * @return true when the flag may be read without throwing
	 */
	public static boolean isReadable(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Predicate<ReflectedReferenceSchemaContract> inheritanceTest
	) {
		if (referenceSchema instanceof ReflectedReferenceSchemaContract reflectedReference) {
			return reflectedReference.isReflectedReferenceAvailable() || !inheritanceTest.test(reflectedReference);
		}
		return true;
	}

	/**
	 * Whether the reference's `bucketed()` flag actually costs any maintenance in the given scope.
	 *
	 * **`isBucketedInScope` is not enough on its own, and the gap is a trap.** That flag only says a histogram is
	 * *declared* for the scope; it says nothing about whether anything maintains it. A histogram declared without a
	 * `valueExpression` - a **count histogram**, which the public builder allows via `bucketed(name, null)` - produces
	 * no {@link io.evitadb.core.expression.trigger.HistogramExpressionTrigger} at all
	 * (`HistogramExpressionTriggerFactory` skips it explicitly), and every maintenance site in `ReferenceIndexMutator`
	 * is gated on the trigger collection being non-empty. Such a histogram is therefore never added to, removed from
	 * or re-evaluated on any entity mutation.
	 *
	 * Seeding a row for it would put a permanently-zero update count on the surface whose entire purpose is to make a
	 * zero readable - it would say *"nothing maintains this flag, drop it"* about a schema that is perfectly valid.
	 * The row is minted only when at least one histogram in the scope carries a value expression, because that is what
	 * guarantees a trigger exists and the counters can move.
	 *
	 * **All three sites consult this one method** - the seeding enumeration, the survival rule, and the update-side
	 * {@link io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor#reportReferenceTouched}. Two of them
	 * agreeing is not enough: a recording site using the bare flag would lazily mint the very row the other two
	 * refuse to seed, and it would carry an update count for maintenance that never happened.
	 *
	 * @param referenceSchema the reference to examine
	 * @param scope           the scope in question
	 * @return true when at least one histogram declared in that scope can actually be maintained
	 */
	public static boolean maintainsHistogramIn(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Scope scope
	) {
		if (!referenceSchema.isBucketedInScope(scope)) {
			return false;
		}
		for (final HistogramIndexDefinition definition : referenceSchema.getHistogramIndexDefinitions(scope).values()) {
			if (definition.valueExpression() != null) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Decides whether a reference's own elements may be seeded in the given scope - the enumeration's counterpart of
	 * the check {@link #declaresCapability} performs on each element.
	 *
	 * # Why seeding tests this and the survival rule does not
	 *
	 * A reference the scope does not index has no reduced index there, so nothing can ever file one of its elements:
	 * a write never reaches {@link io.evitadb.index.mutation.local.AttributeIndexMutator} for an index that does not
	 * exist, and a query is refused by `AttributeSchemaAccessor#verifyAndReturn` with a
	 * {@link io.evitadb.core.exception.ReferenceNotIndexedException}. For an ordinary reference schema validation
	 * already guarantees this, by rejecting a filterable, sortable or unique attribute on a reference that is not
	 * indexed in that scope - which is why the survival rule can omit the condition without ever changing its own
	 * outcome. It deliberately exempts the **inherited** attributes of a reflected reference, though
	 * (`ReflectedReferenceSchema#shouldValidate`), and those are exactly the elements that would otherwise be seeded
	 * as rows nothing could ever increment.
	 *
	 * # An unattached reflected reference is skipped whole
	 *
	 * A {@link ReflectedReferenceSchemaContract} is a description of a reference on *another* entity schema, and until
	 * it has been attached to that reference it can answer nothing: `isIndexedInScope` throws outright when the
	 * inherited flag is asked for and the mirrored reference is not there. That state is ordinary rather than
	 * exceptional - a schema mutation may declare the reflection before the reference it mirrors exists - so the
	 * enumeration skips such a reference instead of guessing at it.
	 *
	 * Nothing is lost by waiting: every path that attaches one goes through `EntityCollection#exchangeSchema`
	 * (`initSchema` on catalog load, `notifyAboutExternalReferenceUpdate` when the mirrored reference changes), and
	 * that is the same hook which realigns this registry. A reflection whose target never appears stays unseeded,
	 * which is the honest outcome: it has no reduced index either.
	 *
	 * @param referenceSchema the reference whose elements are candidates for seeding
	 * @param scope           the scope being seeded
	 * @return true when the reference is known to be indexed in that scope
	 */
	private static boolean maintainsElementsIn(@Nonnull ReferenceSchemaContract referenceSchema, @Nonnull Scope scope) {
		if (referenceSchema instanceof ReflectedReferenceSchemaContract reflectedReference
			&& !reflectedReference.isReflectedReferenceAvailable()
		) {
			return false;
		}
		return referenceSchema.isIndexedInScope(scope);
	}

	/**
	 * Mints a holder for every capability the given attributes declare in the given scope, leaving the ones that
	 * already exist - and their counters - exactly as they were.
	 *
	 * @param attributeSchemas the attributes one container declares
	 * @param containerName    name of the reference declaring them, or null when the entity declares them directly
	 * @param scope            the scope whose declarations are being seeded
	 */
	private void seedAttributes(
		@Nonnull Collection<? extends AttributeSchemaContract> attributeSchemas,
		@Nullable String containerName,
		@Nonnull Scope scope
	) {
		for (final AttributeSchemaContract attributeSchema : attributeSchemas) {
			for (final Capability capability : ATTRIBUTE_CAPABILITIES) {
				// tested before the key is built, so the enumeration allocates one key per capability the attribute
				// really carries rather than one per capability that exists
				if (declaresCapability(attributeSchema, capability, scope)) {
					resolve(
						new SchemaCapabilityKey(
							ElementKind.ATTRIBUTE, containerName, attributeSchema.getName(), capability, scope
						)
					);
				}
			}
		}
	}

	/**
	 * Mints a holder for every compound indexed in the given scope - one key each, since
	 * {@link SchemaCapabilityKey#sortableCompound} fixes the capability at {@link Capability#SORTABLE}.
	 *
	 * @param compoundSchemas the compounds one container declares
	 * @param containerName   name of the reference declaring them, or null when the entity declares them directly
	 * @param scope           the scope whose declarations are being seeded
	 */
	private void seedCompounds(
		@Nonnull Collection<? extends SortableAttributeCompoundSchemaContract> compoundSchemas,
		@Nullable String containerName,
		@Nonnull Scope scope
	) {
		for (final SortableAttributeCompoundSchemaContract compoundSchema : compoundSchemas) {
			final SchemaCapabilityKey key = SchemaCapabilityKey.sortableCompound(
				containerName, compoundSchema.getName(), scope
			);
			if (declaresCapability(compoundSchema, key)) {
				resolve(key);
			}
		}
	}

	/**
	 * Mints a holder for each flag the reference itself declares in the given scope - the reference-level counterpart
	 * of {@link #seedAttributes}, for the element that *is* the reference rather than something inside it.
	 *
	 * **Only ever called for a reference {@link #maintainsElementsIn} has already admitted**, which is what makes
	 * {@link Capability#INDEXED} unconditional here: reaching this method already means the reference is indexed in
	 * this scope. It is also what keeps the other two narrower than the survival rule - a reference declared
	 * `faceted()` but not indexed maintains no facet index, so seeding its row would state a maintenance cost nothing
	 * can ever pay.
	 *
	 * @param referenceSchema the reference as the schema version declares it
	 * @param referenceName   its name, already resolved by the caller
	 * @param scope           the scope whose declarations are being seeded
	 */
	private void seedReferenceCapabilities(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String referenceName,
		@Nonnull Scope scope
	) {
		resolve(SchemaCapabilityKey.reference(referenceName, Capability.INDEXED, scope));
		if (referenceSchema.isFacetedInScope(scope)) {
			resolve(SchemaCapabilityKey.reference(referenceName, Capability.FACETED, scope));
		}
		if (maintainsHistogramIn(referenceSchema, scope)) {
			resolve(SchemaCapabilityKey.reference(referenceName, Capability.BUCKETED, scope));
		}
	}

	/**
	 * Mints a holder for each flag the entity declares on itself in the given scope - its hierarchy and its prices.
	 *
	 * Both are tested against their *indexed* form rather than their bare `withHierarchy()` / `withPrice()` form: an
	 * entity may declare prices it never indexes in a given scope, and an unindexed one costs no maintenance, so a row
	 * for it would read as a flag nothing uses when in truth nothing was ever asked to keep it.
	 *
	 * @param entitySchema the schema version being aligned to
	 * @param entityType   its name, already resolved by the caller
	 * @param scope        the scope whose declarations are being seeded
	 */
	private void seedEntityCapabilities(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull String entityType,
		@Nonnull Scope scope
	) {
		if (entitySchema.isHierarchyIndexedInScope(scope)) {
			resolve(SchemaCapabilityKey.entity(entityType, Capability.HIERARCHICAL, scope));
		}
		if (entitySchema.isPriceIndexedInScope(scope)) {
			resolve(SchemaCapabilityKey.entity(entityType, Capability.PRICED, scope));
		}
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
		return declaresCapability(
			catalogSchema.getAttribute(key.elementName()).orElse(null), key.capability(), key.scope()
		);
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
					entitySchema.getAttribute(key.elementName()).orElse(null), key.capability(), key.scope()
				);
				case SORTABLE_COMPOUND -> declaresCapability(
					entitySchema.getSortableAttributeCompound(key.elementName()).orElse(null), key
				);
				case REFERENCE -> declaresCapability(
					entitySchema.getReference(key.elementName()).orElse(null), key.capability(), key.scope()
				);
				// the entity is the only element that can be renamed out from under its own rows, so the name is
				// checked rather than assumed - a row naming a different entity belongs to no schema at all
				case ENTITY -> key.elementName().equals(entitySchema.getName())
					&& declaresCapability(entitySchema, key.capability(), key.scope());
			};
		}

		final ReferenceSchemaContract reference = entitySchema.getReference(containerName).orElse(null);
		if (reference == null) {
			return false;
		}
		return switch (key.elementKind()) {
			case ATTRIBUTE -> declaresCapability(
				reference.getAttribute(key.elementName()).orElse(null), key.capability(), key.scope()
			);
			case SORTABLE_COMPOUND -> declaresCapability(
				reference.getSortableAttributeCompound(key.elementName()).orElse(null), key
			);
			// a reference declares neither references nor entities, so such a key describes an element no schema can
			// correspond to - dropping it silently would hide whoever minted it
			case REFERENCE, ENTITY -> throw new GenericEvitaInternalError(
				"A " + key.elementKind() + " cannot be declared by reference `" + containerName + "`."
			);
		};
	}

	/**
	 * Decides whether an attribute schema carries the given capability in the given scope - **the single source of
	 * truth** both halves of {@link #alignWith(EntitySchemaContract)} consult, so that a capability the enumeration
	 * seeds can never be one the survival rule would immediately drop.
	 *
	 * Stated over a loose `(capability, scope)` pair rather than over a {@link SchemaCapabilityKey} for that reason:
	 * the seeding side asks the question *before* it has a key to ask it with, and building one per candidate would
	 * allocate an object per capability an attribute does not carry.
	 *
	 * @param attributeSchema the attribute as the schema version declares it, or null when it declares none
	 * @param capability      the capability in question
	 * @param scope           the scope the capability would be maintained in
	 * @return true when the attribute exists and carries the capability in that scope
	 */
	private static boolean declaresCapability(
		@Nullable AttributeSchemaContract attributeSchema,
		@Nonnull Capability capability,
		@Nonnull Scope scope
	) {
		if (attributeSchema == null) {
			return false;
		}
		// no `default` branch on purpose: an exhaustive switch over Capability makes a future value a compile error
		// here, which catches the omission earlier and more loudly than any runtime throw could
		return switch (capability) {
			// `unique()` implies `filterable()` - the schema even refuses to let both be declared explicitly, and
			// AttributeSchemaAccessor lets a filter reach a unique-only attribute on exactly that basis. Testing only
			// the `filterable()` flag here would therefore prune a live capability the moment a schema was re-adopted
			case FILTERABLE -> attributeSchema.isFilterableInScope(scope) || attributeSchema.isUniqueInScope(scope);
			// the bare declaration, deliberately not conjoined with FILTERABLE: the two are separate rows because
			// they are separately droppable, and the schema already refuses a capability without filterability, so
			// re-testing it here would only hide a corrupt schema rather than report one
			case SUBSTRING_FILTERABLE -> attributeSchema.getFilterCapabilitiesInScope(scope)
				.contains(FilterIndexCapability.SUBSTRING);
			case SORTABLE -> attributeSchema.isSortableInScope(scope);
			// covers both uniqueness flavours - within the collection and within a locale - because both cost a
			// uniqueness index, which is what the entry measures
			case UNIQUE -> attributeSchema.isUniqueInScope(scope);
			// an attribute declares none of these - they belong to a reference or to the entity itself - so such a
			// key could never match any schema and silently dropping it would hide whoever minted it
			case FACETED, INDEXED, BUCKETED, HIERARCHICAL, PRICED -> throw new GenericEvitaInternalError(
				"Attribute `" + attributeSchema.getName() + "` cannot carry capability " + capability + "."
			);
		};
	}

	/**
	 * Decides whether a reference schema carries the given capability in the given scope - the reference-level
	 * counterpart of the attribute predicate above, and consulted by both halves of
	 * {@link #alignWith(EntitySchemaContract)} for the same reason.
	 *
	 * A reflected reference that is not attached yet answers `false` to everything rather than being asked: its flags
	 * are inherited from the reference it reflects and reading them before attachment throws. That mirrors
	 * {@link #maintainsElementsIn}, which skips the same reference when seeding what it declares.
	 *
	 * @param referenceSchema the reference as the schema version declares it, or null when it declares none
	 * @param capability      the capability in question
	 * @param scope           the scope the capability would be maintained in
	 * @return true when the reference exists and carries the capability in that scope
	 */
	private static boolean declaresCapability(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull Capability capability,
		@Nonnull Scope scope
	) {
		if (referenceSchema == null) {
			return false;
		}
		// no `default` branch on purpose: an exhaustive switch over Capability makes a future value a compile error
		// here, which catches the omission earlier and more loudly than any runtime throw could
		return switch (capability) {
			case INDEXED -> isReadable(referenceSchema, ReflectedReferenceSchemaContract::isIndexedInherited)
				&& referenceSchema.isIndexedInScope(scope);
			// deliberately the bare flag, not conjoined with `indexed()`: this predicate is the *survival* rule, and
			// it has to keep anything a recording site could file. The narrowing that stops an unmaintained row from
			// being *minted* lives in the seeding enumeration - see `maintainsElementsIn` - which is the same
			// seed-narrower-than-survive asymmetry the catalog registry uses
			case FACETED -> isReadable(referenceSchema, ReflectedReferenceSchemaContract::isFacetedInherited)
				&& referenceSchema.isFacetedInScope(scope);
			// `bucketed()` needs no readability test - ReflectedReferenceSchema does not override it, so it answers
			// from the reflected reference's own definition whether the target is attached or not - but it does need
			// more than the bare flag, which is only "a histogram is declared here". See `maintainsHistogramIn`
			case BUCKETED -> maintainsHistogramIn(referenceSchema, scope);
			// a reference declares none of these - the first three belong to its attributes, the last two to the
			// entity - so such a key could never match any schema and dropping it silently would hide its author
			case FILTERABLE, SUBSTRING_FILTERABLE, SORTABLE, UNIQUE, HIERARCHICAL, PRICED ->
				throw new GenericEvitaInternalError(
					"Reference `" + referenceSchema.getName() + "` cannot carry capability " + capability + "."
				);
		};
	}

	/**
	 * Decides whether the entity schema itself carries the given capability in the given scope - the entity-level
	 * counterpart of the two predicates above, for the two flags an entity declares on its own rather than on
	 * anything inside it.
	 *
	 * @param entitySchema the schema version to check against
	 * @param capability   the capability in question
	 * @param scope        the scope the capability would be maintained in
	 * @return true when the entity carries the capability in that scope
	 */
	private static boolean declaresCapability(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Capability capability,
		@Nonnull Scope scope
	) {
		// no `default` branch on purpose: an exhaustive switch over Capability makes a future value a compile error
		// here, which catches the omission earlier and more loudly than any runtime throw could
		return switch (capability) {
			case HIERARCHICAL -> entitySchema.isHierarchyIndexedInScope(scope);
			case PRICED -> entitySchema.isPriceIndexedInScope(scope);
			// the entity declares none of these directly - they belong to its attributes, its compounds or its
			// references - so such a key could never match any schema and dropping it silently would hide its author
			case FILTERABLE, SUBSTRING_FILTERABLE, SORTABLE, UNIQUE, FACETED, INDEXED, BUCKETED ->
				throw new GenericEvitaInternalError(
					"Entity `" + entitySchema.getName() + "` cannot carry capability " + capability + " directly."
				);
		};
	}

	/**
	 * Decides whether a sortable attribute compound still exists and is still indexed in the key's scope.
	 *
	 * @param compoundSchema the compound as the new schema version declares it, or null when it declares none
	 * @param key            the capability in question - always {@link Capability#SORTABLE}, a compound has no other
	 * @return true when the compound exists and is indexed in that scope
	 */
	private static boolean declaresCapability(
		@Nullable SortableAttributeCompoundSchemaContract compoundSchema,
		@Nonnull SchemaCapabilityKey key
	) {
		if (key.capability() != Capability.SORTABLE) {
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
