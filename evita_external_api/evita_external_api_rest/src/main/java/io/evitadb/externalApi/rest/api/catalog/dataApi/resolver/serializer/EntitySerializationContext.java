/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.serializer;

import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetchRequire;
import io.evitadb.api.query.require.HierarchyContent;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.query.require.SeparateEntityContentRequireContainer;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Context holding shared information for entity serialization.
 *
 * Besides the catalog schema it carries the requirement the entities it serves were fetched with, so that the
 * serializer can read what the caller asked for instead of guessing it back from the data. Every path reporting
 * entities supplies one: an endpoint reads it off the query it executed, and an extra result is served by the
 * `entityFetch` of the constraint that produced it. It is nevertheless deliberately nullable - a constraint may
 * ask for no bodies at all, and a requirement may fail to be located - and the serializer falls back to inference
 * wherever it is missing.
 *
 * One context serves a whole response - the query endpoint hands the same instance to every entity of the page -
 * so everything it derives from that single requirement is memoized on it. Within one response the requirement of
 * a given reference name cannot vary, and the derivation would otherwise be repeated once per reference instance
 * of every entity.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
@Data
@RequiredArgsConstructor
public class EntitySerializationContext {

	/**
	 * Stand-in memoized in {@link #resolvedHierarchyContent} for "already resolved, and there is none", so that
	 * a requirement asking for no ancestor axis is resolved once rather than on every entity of the page.
	 */
	private static final HierarchyContent NO_HIERARCHY_CONTENT = new HierarchyContent();

	@Nonnull private final CatalogSchemaContract catalogSchema;
	/**
	 * The requirement the entities served by this context were fetched with, or NULL when the endpoint could not
	 * resolve one. It describes exactly one level of the response - entities reached through a `referenceContent`
	 * are fetched with a requirement of their own, which {@link #forReferencedEntity(String)} descends into.
	 */
	@Nullable private final EntityFetchRequire entityRequirement;

	/**
	 * Contexts of the entities reached through a `referenceContent` of this level, keyed by reference name.
	 */
	@Getter(AccessLevel.NONE)
	@EqualsAndHashCode.Exclude
	@ToString.Exclude
	private final Map<String, EntitySerializationContext> referencedEntityContexts = new ConcurrentHashMap<>(8);
	/**
	 * Contexts of the group entities of a `referenceContent` of this level, keyed by reference name.
	 */
	@Getter(AccessLevel.NONE)
	@EqualsAndHashCode.Exclude
	@ToString.Exclude
	private final Map<String, EntitySerializationContext> groupEntityContexts = new ConcurrentHashMap<>(8);
	/**
	 * The reduced `hierarchyContent` of this level, or {@link #NO_HIERARCHY_CONTENT} when the reduction yielded
	 * none. NULL until the first {@link #resolveHierarchyContent()} call.
	 */
	@Getter(AccessLevel.NONE)
	@Setter(AccessLevel.NONE)
	@EqualsAndHashCode.Exclude
	@ToString.Exclude
	private volatile HierarchyContent resolvedHierarchyContent;

	/**
	 * Creates a context carrying no requirement, leaving the serializer to infer what the caller asked for.
	 *
	 * @param catalogSchema the schema of the catalog the served entities belong to
	 */
	public EntitySerializationContext(@Nonnull CatalogSchemaContract catalogSchema) {
		this(catalogSchema, null);
	}

	/**
	 * Returns the `hierarchyContent` requirement the entities served by this context were fetched with, or NULL when
	 * there is none - either because no ancestor axis was asked for, or because the endpoint could supply no
	 * requirement at all.
	 *
	 * A single entity fetch may carry several `hierarchyContent` siblings, since `entityFetchAllContent()` emits
	 * a bare one of its own and an explicit requirement written beside it therefore always makes two. The engine
	 * reduces them with {@link HierarchyContent#combineWith(EntityContentRequire)} into the one requirement it really
	 * serves, and the same reduction is repeated here so that the serializer reads that same requirement. It is
	 * repeated rather than called: the engine's copy is inlined in
	 * {@link io.evitadb.api.requestResponse.EvitaRequest#isRequiresParent()}, which needs a whole parsed query and
	 * reduces only its top-level `entityFetch`, where this level holds a single requirement that may equally be the
	 * one nested inside a `referenceContent`.
	 *
	 * The result is memoized because this context serves every entity of the response and the answer cannot differ
	 * between them.
	 *
	 * @return the requirement the ancestor axis was fetched with, or NULL
	 */
	@Nullable
	public HierarchyContent resolveHierarchyContent() {
		HierarchyContent memoizedHierarchyContent = this.resolvedHierarchyContent;
		if (memoizedHierarchyContent == null) {
			final HierarchyContent combinedHierarchyContent = combineHierarchyContent();
			memoizedHierarchyContent = combinedHierarchyContent == null ?
				NO_HIERARCHY_CONTENT : combinedHierarchyContent;
			this.resolvedHierarchyContent = memoizedHierarchyContent;
		}
		return memoizedHierarchyContent == NO_HIERARCHY_CONTENT ? null : memoizedHierarchyContent;
	}

	/**
	 * Returns the context serving the entity referenced through `referenceName`, which is fetched with the
	 * requirement written inside the `referenceContent` that pulled it in rather than with this level's one.
	 *
	 * @param referenceName name of the reference the entity is reached through
	 * @return the context of the referenced entity
	 */
	@Nonnull
	public EntitySerializationContext forReferencedEntity(@Nonnull String referenceName) {
		return this.referencedEntityContexts.computeIfAbsent(
			referenceName,
			theReferenceName -> new EntitySerializationContext(
				this.catalogSchema,
				combineReferenceRequirement(
					theReferenceName, referenceContent -> referenceContent.getEntityRequirement().orElse(null)
				)
			)
		);
	}

	/**
	 * Returns the context serving the group entity of the reference `referenceName`, which is fetched with the
	 * requirement written inside the `referenceContent` that pulled it in rather than with this level's one.
	 *
	 * @param referenceName name of the reference the group entity belongs to
	 * @return the context of the group entity
	 */
	@Nonnull
	public EntitySerializationContext forGroupEntity(@Nonnull String referenceName) {
		return this.groupEntityContexts.computeIfAbsent(
			referenceName,
			theReferenceName -> new EntitySerializationContext(
				this.catalogSchema,
				combineReferenceRequirement(
					theReferenceName, referenceContent -> referenceContent.getGroupEntityRequirement().orElse(null)
				)
			)
		);
	}

	/**
	 * Reduces the `hierarchyContent` siblings of this level into the single requirement they express between them.
	 *
	 * @return the combined requirement, or NULL when this level carries none
	 */
	@Nullable
	private HierarchyContent combineHierarchyContent() {
		if (this.entityRequirement == null) {
			return null;
		}
		final List<HierarchyContent> hierarchyContents = QueryUtils.findConstraints(
			this.entityRequirement, HierarchyContent.class, SeparateEntityContentRequireContainer.class
		);
		HierarchyContent combinedHierarchyContent = null;
		for (final HierarchyContent hierarchyContent : hierarchyContents) {
			combinedHierarchyContent = combinedHierarchyContent == null ?
				hierarchyContent : combinedHierarchyContent.combineWith(hierarchyContent);
		}
		return combinedHierarchyContent;
	}

	/**
	 * Reduces the body requirement every `referenceContent` of this level that covers `referenceName` asks for into
	 * the single requirement they express between them. A requirement naming no reference covers every one of them,
	 * so `referenceContentAll(entityFetch(a))` written beside `referenceContent("category", entityFetch(b))` both
	 * contribute rather than the one document order happens to put first winning outright.
	 *
	 * The reduction is done on the fetch requirements and not on the `referenceContent`s themselves, even though
	 * {@link ReferenceContent#combineWith(EntityContentRequire)} exists: that method resolves an all-references
	 * operand by returning it whole, which would discard the very body requirement the named sibling carries.
	 *
	 * What is reduced here is deliberately a **superset** of what the engine serves: the engine resolves a reference
	 * name to exactly one `referenceContent` - a named one beats an all-references one, and the last of two
	 * same-name siblings wins - so where two covering requirements disagree, this may report a `hierarchyContent`
	 * the engine did not honour. Mirroring that pick would hard-code a coin flip, and the superset never loses
	 * a requirement; a wrong pick degrades to the inference the serializer falls back to anyway.
	 *
	 * @param referenceName       name of the reference to reduce the requirement for
	 * @param requirementAccessor reads the requirement to reduce off one `referenceContent` - the referenced entity's
	 *                            or the group entity's - and returns NULL when that `referenceContent` asks for none
	 * @param <T>                 type of the requirement being reduced
	 * @return the combined requirement, or NULL when no covering `referenceContent` asks for one
	 */
	@Nullable
	private <T extends EntityFetchRequire> T combineReferenceRequirement(
		@Nonnull String referenceName,
		@Nonnull Function<ReferenceContent, T> requirementAccessor
	) {
		if (this.entityRequirement == null) {
			return null;
		}
		final List<ReferenceContent> referenceContents = QueryUtils.findConstraints(
			this.entityRequirement, ReferenceContent.class, SeparateEntityContentRequireContainer.class
		);
		T combinedRequirement = null;
		for (final ReferenceContent referenceContent : referenceContents) {
			if (coversReference(referenceContent, referenceName)) {
				combinedRequirement = EntityFetchRequire.combineRequirements(
					combinedRequirement, requirementAccessor.apply(referenceContent)
				);
			}
		}
		return combinedRequirement;
	}

	/**
	 * Returns TRUE when `referenceContent` pulled in the reference `referenceName` - either by naming it explicitly
	 * or by naming no reference at all, which asks for every one of them.
	 *
	 * @param referenceContent the requirement to test
	 * @param referenceName    name of the reference to test it against
	 * @return TRUE when the requirement covers the reference
	 */
	private static boolean coversReference(
		@Nonnull ReferenceContent referenceContent,
		@Nonnull String referenceName
	) {
		final String[] referenceNames = referenceContent.getReferenceNames();
		if (referenceNames.length == 0) {
			return true;
		}
		for (final String requestedReferenceName : referenceNames) {
			if (requestedReferenceName.equals(referenceName)) {
				return true;
			}
		}
		return false;
	}
}
