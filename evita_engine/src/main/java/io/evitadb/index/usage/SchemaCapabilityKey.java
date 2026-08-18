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

import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Names one thing an operator can act on: **a single capability flag, on a single schema element, in a single scope**
 * - `filterable()` on the entity attribute `ean` in the live scope, `sortable()` on the `categories` reference's
 * `priority` attribute, and so on. It is the identity {@link SchemaCapabilityUsage} counts against.
 *
 * # Why the schema element and not the physical index
 *
 * A capability is maintained by *many* physical indexes at once - a `filterable()` entity attribute has a
 * {@link io.evitadb.index.attribute.FilterIndex} in the global index and in every reduced index that carries the
 * attribute. But the remedial action an operator takes is **one schema mutation** that removes all of them together,
 * so the number they act on has to be one number per flag. The per-index reading is a different question with its own
 * home; see {@link io.evitadb.index.IndexActivity}.
 *
 * # The identity is a flat triple, and what that costs
 *
 * The element is described by **kind, container and name** rather than by a nested structure, because this is a hash
 * map key on a path that resolves holders during query planning: a flat record hashes its four components and compares
 * without dereferencing anything, and it allocates once at resolve time rather than per lookup.
 *
 * The price is that the discriminators are easy to overlook, so both are stated here explicitly:
 *
 * - **{@link #containerName()} distinguishes an entity attribute from a reference attribute of the same name.** Null
 *   means the entity itself owns the element; a reference name means that reference does. Attribute names are unique
 *   within their owner and not across owners, so `priority` on the entity and `priority` on `categories` are routinely
 *   both present.
 * - **{@link #elementKind()} distinguishes an attribute from a sortable compound of the same name.** Nothing else
 *   does - a compound has a container and a name exactly like an attribute.
 *
 * Conflating either pair would pool a well-used capability's traffic with an unused one and produce the one failure
 * this whole surface exists to prevent: a flag reported as dead being dropped while a query still depends on it.
 *
 * @param elementKind   what kind of schema element this is - the only thing telling an attribute apart from a sortable
 *                      compound carrying the same name
 * @param containerName name of the reference the element is declared on, or null when the entity declares it directly
 * @param elementName   name of the attribute or sortable compound itself
 * @param capability    which of the element's flags this entry counts
 * @param scope         the scope whose indexes maintain the capability - `filterable()` may be declared for the live
 *                      data set and the archive independently, and so may be dropped from one and kept in the other
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see SchemaCapabilityUsage
 */
public record SchemaCapabilityKey(
	@Nonnull ElementKind elementKind,
	@Nullable String containerName,
	@Nonnull String elementName,
	@Nonnull Capability capability,
	@Nonnull Scope scope
) {

	/**
	 * Rejects a half-described element at construction rather than letting it become a registry entry nobody can trace
	 * back to a schema - only {@link #containerName()} is genuinely optional, and its absence carries meaning.
	 */
	public SchemaCapabilityKey {
		Objects.requireNonNull(elementKind, "Element kind is mandatory.");
		Objects.requireNonNull(elementName, "Element name is mandatory.");
		Objects.requireNonNull(capability, "Capability is mandatory.");
		Objects.requireNonNull(scope, "Scope is mandatory.");
	}

	/**
	 * Names a capability of an attribute the entity declares itself.
	 *
	 * @param attributeName name of the entity attribute
	 * @param capability    which of its flags to count
	 * @param scope         the scope whose indexes maintain it
	 * @return the key
	 */
	@Nonnull
	public static SchemaCapabilityKey entityAttribute(
		@Nonnull String attributeName,
		@Nonnull Capability capability,
		@Nonnull Scope scope
	) {
		return new SchemaCapabilityKey(ElementKind.ATTRIBUTE, null, attributeName, capability, scope);
	}

	/**
	 * Names a capability of an attribute declared on a reference.
	 *
	 * @param referenceName name of the reference declaring the attribute
	 * @param attributeName name of the attribute within that reference
	 * @param capability    which of its flags to count
	 * @param scope         the scope whose indexes maintain it
	 * @return the key
	 */
	@Nonnull
	public static SchemaCapabilityKey referenceAttribute(
		@Nonnull String referenceName,
		@Nonnull String attributeName,
		@Nonnull Capability capability,
		@Nonnull Scope scope
	) {
		Objects.requireNonNull(referenceName, "Reference name is mandatory for a reference attribute.");
		return new SchemaCapabilityKey(ElementKind.ATTRIBUTE, referenceName, attributeName, capability, scope);
	}

	/**
	 * Names the sole capability of a sortable attribute compound. The capability is not a parameter because a compound
	 * exists only to be ordered by - it has no filterable or unique form to count, and accepting one would let a call
	 * site mint a key no schema can ever correspond to.
	 *
	 * @param referenceName name of the reference declaring the compound, or null when the entity declares it directly
	 * @param compoundName  name of the compound
	 * @param scope         the scope whose indexes maintain it
	 * @return the key
	 */
	@Nonnull
	public static SchemaCapabilityKey sortableCompound(
		@Nullable String referenceName,
		@Nonnull String compoundName,
		@Nonnull Scope scope
	) {
		return new SchemaCapabilityKey(
			ElementKind.SORTABLE_COMPOUND, referenceName, compoundName, Capability.SORT, scope
		);
	}

	/**
	 * What kind of schema element a key names. Deliberately **not** split by owner - an entity attribute and a
	 * reference attribute are the same kind of thing declared in two places, and {@link #containerName()} already says
	 * which place. What this enum separates is the two things that would otherwise be indistinguishable: an attribute
	 * and a sortable compound may carry the same name in the same container.
	 */
	public enum ElementKind {

		/**
		 * An attribute, of the entity or of one of its references.
		 */
		ATTRIBUTE,

		/**
		 * A sortable attribute compound, of the entity or of one of its references.
		 */
		SORTABLE_COMPOUND

	}

	/**
	 * One flag of a schema element, and one line of maintenance cost the workload either justifies or does not.
	 *
	 * The values are exactly the flags this phase can attribute a request to; prices, facets and hierarchy are
	 * maintained by their own structures and reached through their own query paths, so they get their own values when
	 * the accumulation sites that can report them exist - not before.
	 */
	public enum Capability {

		/**
		 * The element can be filtered by - `filterable()`, and the inverted indexes it costs.
		 */
		FILTER,

		/**
		 * The element can be ordered by - `sortable()`, and the sorted record arrays it costs.
		 */
		SORT,

		/**
		 * The element's values are unique - `unique()`, whether within the entity collection or globally, and the
		 * uniqueness index it costs.
		 */
		UNIQUE

	}

}
