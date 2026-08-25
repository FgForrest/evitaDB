/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.requestResponse.schema.mutation.attribute;


import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.mutation.conflict.CatalogConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.CollectionConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.schema.FilterIndexCapability;
import io.evitadb.api.requestResponse.schema.mutation.NamedSchemaMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Base class for attribute schema mutations that operate on a single attribute identified by its
 * {@code name}.
 *
 * This class centralizes three cross-cutting concerns common to attribute schema mutations:
 *
 * - container addressing: {@link #containerName()} returns the attribute name so mutation processing
 *   can route the change to the correct schema container
 * - conflict scoping: {@link #collectConflictKeys(ConflictGenerationContext)} yields a single
 *   conflict key that scopes the mutation either to the current entity collection (when an entity
 *   type is present in the {@link ConflictGenerationContext}) or to the catalog as a whole (when no
 *   entity type is present). This allows the conflict resolver to detect and serialize concurrent
 *   schema changes that would otherwise clash
 * - filter index capability verification: {@link #verifyCapabilityScopesAreFilterable}, {@link
 *   #verifyCapabilityNotOnReferenceAttribute} and {@link #verifyCapabilitiesApplicableToType} are the
 *   shared refusal checks that every mutation carrying a {@link ScopedFilterCapabilities} array (create
 *   and set-filterable mutations, both at entity and catalog level) runs against it
 *
 * Characteristics:
 *
 * - immutable and thread-safe
 * - value-based equality and hash code (via Lombok)
 * - stores only the attribute {@code name}, leaving concrete mutation details to subclasses
 *
 * Typical subclasses include mutations that create, remove, or rename attribute schemas, both at the
 * catalog level and within a particular entity collection. Implementors should focus on the mutation's
 * semantics; naming and conflict-key generation are handled here.
 *
 * @see io.evitadb.api.requestResponse.schema.mutation.attribute.GlobalAttributeSchemaMutation
 * @see io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ThreadSafe
@Immutable
@RequiredArgsConstructor
@EqualsAndHashCode
abstract class AbstractAttributeSchemaMutation implements NamedSchemaMutation {
	@Serial private static final long serialVersionUID = -1239026715678744015L;
	@Getter @Nonnull protected final String name;

	@Nonnull
	@Override
	public String containerName() {
		return this.name;
	}

	@Nonnull
	@Override
	public Stream<ConflictKey> collectConflictKeys(
		@Nonnull ConflictGenerationContext context
	) {
		return context.isEntityTypePresent() ?
			Stream.of(new CollectionConflictKey(context.getEntityType())) :
			Stream.of(new CatalogConflictKey(context.getCatalogName()));
	}

	/**
	 * Joins an array of {@link ScopedAttributeUniquenessType} into a single string representation.
	 * Each element in the array is transformed into a string format combining its scope and uniqueness type
	 * (e.g., "scope: UNIQUENESS_TYPE") and concatenated with a comma separator.
	 *
	 * @param scopes an array of {@link ScopedAttributeUniquenessType} defining the scope and uniqueness type combinations;
	 *               must not be null.
	 * @return a comma-separated string representation of the input array; never null.
	 */
	@Nonnull
	protected static String join(@Nonnull ScopedAttributeUniquenessType[] scopes) {
		return Arrays.stream(scopes)
			.map(it -> it.scope() + ": " + it.uniquenessType().name())
			.collect(Collectors.joining(", "));
	}

	/**
	 * Concatenates an array of {@code ScopedGlobalAttributeUniquenessType} entries into a single string.
	 * Each entry in the array is transformed into a string representation in the format:
	 * {@code "scope: uniquenessType"} and entries are joined with a comma and space.
	 *
	 * @param scopes an array of {@code ScopedGlobalAttributeUniquenessType} objects representing
	 *               attribute uniqueness types scoped by a specific domain or context.
	 * @return a concatenated string representation of all entries in the {@code scopes} array.
	 *         Returns an empty string if the input array is null or empty.
	 */
	@Nonnull
	protected static String join(ScopedGlobalAttributeUniquenessType[] scopes) {
		return Arrays.stream(scopes)
			.map(it -> it.scope() + ": " + it.uniquenessType().name())
			.collect(Collectors.joining(", "));
	}

	/**
	 * Renders the filter index capability carriers as `SCOPE: CAP_A, CAP_B; SCOPE: ...` for `toString()`.
	 *
	 * @param scopes the carriers to render; must not be null
	 * @return a comma-separated rendering of the carriers; never null
	 */
	@Nonnull
	protected static String join(@Nonnull ScopedFilterCapabilities[] scopes) {
		return Arrays.stream(scopes)
			.map(
				it -> it.scope() + ": " + Arrays.stream(it.capabilities())
					.map(Enum::name)
					.collect(Collectors.joining(", "))
			)
			.collect(Collectors.joining("; "));
	}

	/**
	 * Refuses a mutation that would leave a filter index capability behind in a scope the attribute is not filterable
	 * in.
	 *
	 * The builder cannot express that combination at all - it folds the capabilities into the `filterable()` call -
	 * but a mutation reaching the engine over gRPC, REST or GraphQL is assembled field by field and can, so it is
	 * checked here rather than trusted. The alternative, silently dropping the orphaned capability, would let a client
	 * believe it had enabled an acceleration it will never get.
	 *
	 * A carrier declaring **no** capability is deliberately accepted for any scope: it orphans nothing, and
	 * `nonFilterableInScope(...)` legitimately emits such carriers.
	 *
	 * @param name                       name of the altered attribute, for the error message
	 * @param filterableInScopes         the scopes the mutation makes the attribute filterable in
	 * @param filterCapabilitiesInScopes the capability carriers the mutation transports
	 * @throws InvalidSchemaMutationException when a carrier names a scope outside `filterableInScopes`
	 */
	protected static void verifyCapabilityScopesAreFilterable(
		@Nonnull String name,
		@Nonnull Scope[] filterableInScopes,
		@Nonnull ScopedFilterCapabilities[] filterCapabilitiesInScopes
	) {
		if (filterCapabilitiesInScopes.length == 0) {
			return;
		}
		final EnumSet<Scope> filterable = ArrayUtils.toEnumSet(Scope.class, filterableInScopes);
		for (final ScopedFilterCapabilities scopedCapabilities : filterCapabilitiesInScopes) {
			if (scopedCapabilities.capabilities().length > 0 && !filterable.contains(scopedCapabilities.scope())) {
				throw new InvalidSchemaMutationException(
					"Attribute `" + name + "` is asked to maintain filter index capabilities " +
						Arrays.toString(scopedCapabilities.capabilities()) + " in scope `" +
						scopedCapabilities.scope() + "`, but the same mutation does not make it filterable there! " +
						"Filter index capabilities accelerate an existing filter index - add `" +
						scopedCapabilities.scope() + "` to the filterable scopes, or drop the capabilities."
				);
			}
		}
	}

	/**
	 * Refuses a filter index capability declared on a **reference attribute**.
	 *
	 * The index backing a capability is hosted on the entity's global index, while a reference attribute's values live
	 * in the reduced and referenced-type indexes - so nothing would ever serve the declaration. Accepting it would sell
	 * the user schema ceremony and a memory bill in exchange for a silent full scan, which is worse than being told no.
	 *
	 * This is a **restriction that can be lifted compatibly** once the index learns to host reference-attribute values;
	 * the reverse - shipping the permission and withdrawing it later - could not be, which is why it goes in now.
	 *
	 * @param name                       name of the altered attribute, for the error message
	 * @param referenceName              name of the reference the attribute belongs to, for the error message
	 * @param entityTypeName             name of the entity declaring the reference, for the error message
	 * @param filterCapabilitiesInScopes the capability carriers the mutation transports
	 * @throws InvalidSchemaMutationException when any carrier declares a capability
	 */
	protected static void verifyCapabilityNotOnReferenceAttribute(
		@Nonnull String name,
		@Nonnull String referenceName,
		@Nonnull String entityTypeName,
		@Nonnull ScopedFilterCapabilities[] filterCapabilitiesInScopes
	) {
		for (final ScopedFilterCapabilities scopedCapabilities : filterCapabilitiesInScopes) {
			if (scopedCapabilities.capabilities().length > 0) {
				throw new InvalidSchemaMutationException(
					"Filter index capabilities " + Arrays.toString(scopedCapabilities.capabilities()) +
						" cannot be declared on attribute `" + name + "` of reference `" + referenceName +
						"` in entity `" + entityTypeName + "`! They are supported on entity attributes only " +
						"(including catalog-shared global ones), because the index that serves them is maintained " +
						"on the entity's global index and never sees reference attribute values. Declare the " +
						"capability on an entity attribute, or filter this one without the acceleration."
				);
			}
		}
	}

	/**
	 * Refuses a filter index capability the attribute's data type cannot support.
	 *
	 * Every route that can attach a capability to an attribute of the wrong type passes through here: the dedicated
	 * set mutation, which learns the type only when it meets the schema it alters, and the two create mutations, which
	 * carry the type themselves and can therefore check at construction time.
	 *
	 * @param name         name of the altered attribute, for the error message
	 * @param type         the attribute's data type
	 * @param capabilities the capabilities the mutation asks for, per scope
	 * @throws InvalidSchemaMutationException when a capability is not applicable to the type
	 */
	protected static void verifyCapabilitiesApplicableToType(
		@Nonnull String name,
		@Nonnull Class<? extends Serializable> type,
		@Nonnull Map<Scope, Set<FilterIndexCapability>> capabilities
	) {
		if (capabilities.isEmpty()) {
			return;
		}
		final Class<?> plainType = type.isArray() ? type.getComponentType() : type;
		for (final Set<FilterIndexCapability> scopedCapabilities : capabilities.values()) {
			for (final FilterIndexCapability capability : scopedCapabilities) {
				// a switch *expression* rather than a statement: it is the expression form that the compiler requires
				// to be exhaustive, so a future capability added without teaching this method - the only place that
				// knows which data types a capability can be maintained for - is a compile error here
				final Class<?> requiredType = switch (capability) {
					case SUBSTRING -> String.class;
				};
				Assert.isTrue(
					requiredType.equals(plainType),
					() -> new InvalidSchemaMutationException(
						"Filter index capability `" + capability + "` can only be declared on attributes of type `" +
							requiredType.getSimpleName() + "` or `" + requiredType.getSimpleName() +
							"[]`, but attribute `" + name + "` is of type `" + type.getName() + "`!"
					)
				);
			}
		}
	}

}
