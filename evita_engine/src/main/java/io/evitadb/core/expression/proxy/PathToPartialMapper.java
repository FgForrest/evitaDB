/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.core.expression.proxy;

import io.evitadb.api.query.expression.object.accessor.entity.EntityContractAccessor;
import io.evitadb.api.query.expression.object.accessor.entity.ReferenceContractAccessor;
import io.evitadb.api.query.expression.visitor.ElementPathItem;
import io.evitadb.api.query.expression.visitor.IdentifierPathItem;
import io.evitadb.api.query.expression.visitor.PathItem;
import io.evitadb.api.query.expression.visitor.VariablePathItem;
import io.evitadb.core.expression.proxy.entity.EntityAssociatedDataPartial;
import io.evitadb.core.expression.proxy.entity.EntityAttributePartial;
import io.evitadb.core.expression.proxy.entity.EntityParentPartial;
import io.evitadb.core.expression.proxy.entity.EntityPrimaryKeyPartial;
import io.evitadb.core.expression.proxy.entity.EntityReferencesPartial;
import io.evitadb.core.expression.proxy.entity.EntitySchemaPartial;
import io.evitadb.core.expression.proxy.entity.EntityVersionAndDroppablePartial;
import io.evitadb.core.expression.proxy.reference.GroupEntityPartial;
import io.evitadb.core.expression.proxy.reference.GroupReferencePartial;
import io.evitadb.core.expression.proxy.reference.ReferenceAttributePartial;
import io.evitadb.core.expression.proxy.reference.ReferenceIdentityPartial;
import io.evitadb.core.expression.proxy.reference.ReferenceVersionAndDroppablePartial;
import io.evitadb.core.expression.proxy.reference.ReferencedEntityPartial;
import io.evitadb.exception.ExpressionEvaluationException;
import one.edee.oss.proxycian.PredicateMethodClassification;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static io.evitadb.utils.CollectionUtils.createHashSet;
import static io.evitadb.utils.CollectionUtils.createLinkedHashSet;

/**
 * Static utility that translates the output of `AccessedDataFinder.findAccessedPaths(ExpressionNode)` into a
 * {@link MappingResult} containing the proxy partials to compose and the storage part recipe to fetch.
 *
 * The mapper processes each path in the list, determines which entity/reference data is accessed, and accumulates
 * the required partials and recipe flags. Always-included partials (schema, version, identity, catch-all) are
 * appended automatically.
 *
 * ## Path structure
 *
 * Each path is a `List<PathItem>` with the following structure:
 *
 * - Index 0: `VariablePathItem` — the root variable (`entity`)
 * - Index 1: `IdentifierPathItem` — the property being accessed (`primaryKey`, `attributes`, `references`, etc.)
 * - Index 2+: `ElementPathItem` or `IdentifierPathItem` — further drill-down (attribute name, reference name,
 *   reference sub-property, etc.)
 *
 * Nested entity paths (e.g., `$entity.references['brand'].referencedEntity.attributes['name']`) are recursively
 * processed to compute independent partial sets and storage part recipes for the nested entities.
 */
public final class PathToPartialMapper {
	private static final String ENTITY_VARIABLE = EntityContractAccessor.ENTITY_VARIABLE_NAME;
	private static final String REFERENCE_VARIABLE = ReferenceContractAccessor.REFERENCE_VARIABLE_NAME;

	/**
	 * Maps a list of accessed data paths to the proxy partials and storage part recipe needed for expression
	 * evaluation.
	 *
	 * @param paths the list of paths from `AccessedDataFinder.findAccessedPaths()`
	 * @return mapping result containing entity/reference partials and the storage part recipe
	 * @throws ExpressionEvaluationException if a path starts with an unknown variable
	 */
	@Nonnull
	public static MappingResult map(@Nonnull List<List<PathItem>> paths) {
		final MappingContext ctx = new MappingContext();

		for (final List<PathItem> path : paths) {
			if (path.isEmpty()) {
				continue;
			}
			processPath(path, ctx);
		}

		return ctx.buildResult();
	}

	/**
	 * Processes a single path and updates the mapping context with the required partials and recipe flags.
	 *
	 * @param path the individual accessed data path
	 * @param ctx  the mutable mapping context accumulating results
	 */
	private static void processPath(@Nonnull List<PathItem> path, @Nonnull MappingContext ctx) {
		final PathItem root = path.get(0);
		if (!(root instanceof VariablePathItem)) {
			throw new ExpressionEvaluationException(
				"Expected variable at path root, got: " + root.getClass().getSimpleName()
					+ " with value `" + root.value() + "`.",
				"Invalid expression path — expected a variable."
			);
		}

		final String variableName = root.value();
		if (REFERENCE_VARIABLE.equals(variableName)) {
			// $reference paths are mapped to reference partials directly
			processReferenceVariablePath(path, ctx);
			return;
		}

		if (!ENTITY_VARIABLE.equals(variableName)) {
			throw new ExpressionEvaluationException(
				"Unknown expression variable `$" + variableName
					+ "`. Only `$entity` and `$reference` are supported.",
				"Unknown variable `$" + variableName + "`."
			);
		}

		if (path.size() < 2) {
			// bare $entity — no specific data accessed, just schema
			return;
		}

		final PathItem propertyItem = path.get(1);
		if (!(propertyItem instanceof IdentifierPathItem)) {
			throw new ExpressionEvaluationException(
				"Expected property identifier after variable `$entity`, got: "
					+ propertyItem.getClass().getSimpleName()
					+ " with value `" + propertyItem.value() + "`.",
				"Invalid expression path after `$entity`."
			);
		}

		final String property = propertyItem.value();
		processEntityProperty(property, path, 1, ctx.entityAcc);

		// handle properties that need reference-level processing
		if (EntityContractAccessor.REFERENCES_PROPERTY.equals(property)) {
			processReferencePath(path, ctx);
		}
	}

	/**
	 * Processes an entity property at a given depth in the path and updates the accumulator with the required
	 * partials and recipe flags. This method is reusable for both top-level entities and nested entities.
	 *
	 * @param property  the property name being accessed
	 * @param path      the full path
	 * @param depth     the current index in the path (where the property identifier is located)
	 * @param acc       the mutable entity mapping accumulator
	 */
	private static void processEntityProperty(
		@Nonnull String property,
		@Nonnull List<PathItem> path,
		int depth,
		@Nonnull EntityMappingAccumulator acc
	) {
		switch (property) {
			case EntityContractAccessor.PRIMARY_KEY_PROPERTY -> {
				acc.needsEntityBody = true;
				acc.partials.add(EntityPrimaryKeyPartial.GET_PRIMARY_KEY);
			}
			case "version", "scope", "locales", "allLocales" -> {
				// handled by always-included VersionAndDroppablePartial
				acc.needsEntityBody = true;
			}
			case "type" -> {
				// handled by always-included SchemaPartial
			}
			case "parent", "parentEntity" -> {
				acc.needsEntityBody = true;
				acc.partials.add(EntityParentPartial.PARENT_AVAILABLE);
				acc.partials.add(EntityParentPartial.GET_PARENT_ENTITY);
			}
			case EntityContractAccessor.ATTRIBUTES_PROPERTY -> processAttributePath(acc);
			case EntityContractAccessor.LOCALIZED_ATTRIBUTES_PROPERTY ->
				processLocalizedAttributePath(acc);
			case EntityContractAccessor.ASSOCIATED_DATA_PROPERTY ->
				processAssociatedDataPath(path, depth, acc);
			case EntityContractAccessor.LOCALIZED_ASSOCIATED_DATA_PROPERTY ->
				processLocalizedAssociatedDataPath(path, depth, acc);
			// references on nested entities are not supported (would require another level of nesting)
			default -> {
				// unknown property — will be handled by CatchAllPartial at runtime
			}
		}
	}

	/**
	 * Processes an attribute access path and updates the given accumulator.
	 *
	 * @param acc the mutable entity mapping accumulator
	 */
	private static void processAttributePath(@Nonnull EntityMappingAccumulator acc) {
		acc.needsGlobalAttributes = true;
		acc.partials.add(EntityAttributePartial.GET_ATTRIBUTE);
		acc.partials.add(EntityAttributePartial.GET_ATTRIBUTE_LOCALIZED);
		acc.partials.add(EntityAttributePartial.GET_ATTRIBUTE_SCHEMA);
		acc.partials.add(EntityAttributePartial.GET_ATTRIBUTE_LOCALES);
		acc.partials.add(EntityAttributePartial.ATTRIBUTES_AVAILABLE);
	}

	/**
	 * Processes a localized attribute access path and updates the given accumulator. In addition to the partials
	 * added by {@link #processAttributePath(EntityMappingAccumulator)}, this method marks that the entity body is
	 * needed (to resolve available locales at runtime) and uses {@link Locale#ROOT} as a sentinel in
	 * `neededAttributeLocales` to signal that **all** locale-specific attribute storage parts must be loaded.
	 *
	 * @param acc the mutable entity mapping accumulator
	 */
	private static void processLocalizedAttributePath(@Nonnull EntityMappingAccumulator acc) {
		processAttributePath(acc);
		// entity body needed to resolve available locales at runtime
		acc.needsEntityBody = true;
		// Locale.ROOT is a sentinel meaning "all locales" — the instantiator should read the entity's
		// locale set from the body and fetch attribute parts for each
		acc.neededAttributeLocales.add(Locale.ROOT);
	}

	/**
	 * Processes an associated data access path and updates the given accumulator.
	 *
	 * @param path  the full path
	 * @param depth the index of the property identifier in the path
	 * @param acc   the mutable entity mapping accumulator
	 */
	private static void processAssociatedDataPath(
		@Nonnull List<PathItem> path,
		int depth,
		@Nonnull EntityMappingAccumulator acc
	) {
		acc.partials.add(EntityAssociatedDataPartial.GET_ASSOCIATED_DATA);
		acc.partials.add(EntityAssociatedDataPartial.GET_ASSOCIATED_DATA_LOCALIZED);
		acc.partials.add(EntityAssociatedDataPartial.GET_ASSOCIATED_DATA_SCHEMA);
		acc.partials.add(EntityAssociatedDataPartial.GET_ASSOCIATED_DATA_LOCALES);
		acc.partials.add(EntityAssociatedDataPartial.ASSOCIATED_DATA_AVAILABLE);

		// extract specific associated data name if available (element after the property identifier)
		final int nameIndex = depth + 1;
		if (path.size() > nameIndex && path.get(nameIndex) instanceof ElementPathItem element) {
			acc.neededAssociatedDataNames.add(element.value());
		}
	}

	/**
	 * Processes a localized associated data access path and updates the given accumulator. In addition to the
	 * partials added by {@link #processAssociatedDataPath(List, int, EntityMappingAccumulator)}, this method marks
	 * that the entity body is needed (to resolve available locales at runtime) and uses {@link Locale#ROOT} as a
	 * sentinel in `neededAssociatedDataLocales` to signal that **all** locale-specific associated data storage
	 * parts must be loaded.
	 *
	 * @param path  the full path
	 * @param depth the index of the property identifier in the path
	 * @param acc   the mutable entity mapping accumulator
	 */
	private static void processLocalizedAssociatedDataPath(
		@Nonnull List<PathItem> path,
		int depth,
		@Nonnull EntityMappingAccumulator acc
	) {
		processAssociatedDataPath(path, depth, acc);
		// entity body needed to resolve available locales at runtime
		acc.needsEntityBody = true;
		// Locale.ROOT is a sentinel meaning "all locales" — the instantiator should read the entity's
		// locale set from the body and fetch associated data parts for each
		acc.neededAssociatedDataLocales.add(Locale.ROOT);
	}

	/**
	 * Processes a reference access path (`$entity.references['name']...`).
	 *
	 * @param path the full path
	 * @param ctx  the mutable mapping context
	 */
	private static void processReferencePath(@Nonnull List<PathItem> path, @Nonnull MappingContext ctx) {
		ctx.entityAcc.needsReferences = true;
		ctx.entityAcc.partials.add(EntityReferencesPartial.GET_REFERENCES_BY_NAME);
		ctx.entityAcc.partials.add(EntityReferencesPartial.GET_REFERENCE);
		ctx.entityAcc.partials.add(EntityReferencesPartial.GET_ALL_REFERENCES);
		ctx.entityAcc.partials.add(EntityReferencesPartial.REFERENCES_AVAILABLE);
		ctx.hasReferencePartials = true;

		// process reference sub-path if present (after references['name'])
		// nested entity property is at index 4: $entity.references['name'].referencedEntity.attributes
		//                                          0       1         2           3            4
		if (path.size() >= 4 && path.get(3) instanceof IdentifierPathItem refProperty) {
			processReferenceSubPath(refProperty.value(), path, 4, ctx);
		}
	}

	/**
	 * Processes a `$reference` variable path. The `$reference` variable accesses the reference directly (without going
	 * through `$entity.references['name']`), so we activate reference partials and process the reference property
	 * at path index 1.
	 *
	 * @param path the full path starting with `$reference`
	 * @param ctx  the mutable mapping context
	 */
	private static void processReferenceVariablePath(
		@Nonnull List<PathItem> path,
		@Nonnull MappingContext ctx
	) {
		ctx.hasReferencePartials = true;

		if (path.size() >= 2 && path.get(1) instanceof IdentifierPathItem refProperty) {
			// nested entity property is at index 2: $reference.referencedEntity.attributes
			//                                           0           1              2
			processReferenceSubPath(refProperty.value(), path, 2, ctx);
		}
	}

	/**
	 * Processes a reference sub-path (property access after `$entity.references['name']` or `$reference`).
	 * For `referencedEntity` and `groupEntity` sub-paths, recursively processes the nested entity path items
	 * to compute nested entity partials and recipe flags.
	 *
	 * The `nestedPropertyIndex` parameter accounts for the different path layouts:
	 * - `$entity.references['name'].referencedEntity.attributes` → nested property at index 4
	 * - `$reference.referencedEntity.attributes` → nested property at index 2
	 *
	 * @param refProperty        the reference property identifier value
	 * @param path               the full path
	 * @param nestedPropertyIndex the index in the path where the nested entity property resides
	 * @param ctx                the mutable mapping context
	 */
	private static void processReferenceSubPath(
		@Nonnull String refProperty,
		@Nonnull List<PathItem> path,
		int nestedPropertyIndex,
		@Nonnull MappingContext ctx
	) {
		switch (refProperty) {
			case ReferenceContractAccessor.ATTRIBUTES_PROPERTY -> {
				ctx.referencePartials.add(ReferenceAttributePartial.GET_ATTRIBUTE);
				ctx.referencePartials.add(ReferenceAttributePartial.GET_ATTRIBUTE_LOCALIZED);
				ctx.referencePartials.add(ReferenceAttributePartial.GET_ATTRIBUTE_SCHEMA);
				ctx.referencePartials.add(ReferenceAttributePartial.GET_ATTRIBUTE_LOCALES);
				ctx.referencePartials.add(ReferenceAttributePartial.ATTRIBUTES_AVAILABLE);
			}
			case ReferenceContractAccessor.REFERENCED_ENTITY_PROPERTY -> {
				ctx.referencePartials.add(ReferencedEntityPartial.GET_REFERENCED_ENTITY);
				ctx.needsReferencedEntityProxy = true;
				// process nested entity sub-path at the caller-provided index
				if (path.size() > nestedPropertyIndex
					&& path.get(nestedPropertyIndex) instanceof IdentifierPathItem nestedProp) {
					processEntityProperty(
						nestedProp.value(), path, nestedPropertyIndex, ctx.referencedEntityAcc
					);
				}
			}
			case ReferenceContractAccessor.GROUP_ENTITY_PROPERTY -> {
				ctx.referencePartials.add(GroupEntityPartial.GET_GROUP_ENTITY);
				ctx.needsGroupEntityProxy = true;
				// process nested entity sub-path at the caller-provided index
				if (path.size() > nestedPropertyIndex
					&& path.get(nestedPropertyIndex) instanceof IdentifierPathItem nestedProp) {
					processEntityProperty(
						nestedProp.value(), path, nestedPropertyIndex, ctx.groupEntityAcc
					);
				}
			}
			case "group" -> {
				ctx.referencePartials.add(GroupReferencePartial.GET_GROUP);
			}
			default -> {
				// referencedPrimaryKey, referencedEntityType, etc. — handled by always-included identity partial
			}
		}
	}

	/**
	 * Private constructor to prevent instantiation of this utility class.
	 */
	private PathToPartialMapper() {
		// utility class
	}

	/**
	 * Result of the path-to-partial mapping containing the composed proxy partial arrays and storage part recipe.
	 *
	 * @param entityPartials             array of entity proxy partials in composition order (specific -> catch-all)
	 * @param referencePartials          array of reference proxy partials, or `null` if no references are accessed
	 * @param entityRecipe               recipe describing which storage parts to fetch for the entity
	 * @param needsReferencedEntityProxy whether the reference proxy needs a nested referenced entity proxy
	 * @param needsGroupEntityProxy      whether the reference proxy needs a nested group entity proxy
	 * @param referencedEntityPartials   array of partials for the nested referenced entity proxy, or `null`
	 * @param groupEntityPartials        array of partials for the nested group entity proxy, or `null`
	 * @param referencedEntityRecipe     recipe for fetching storage parts of the nested referenced entity, or `null`
	 * @param groupEntityRecipe          recipe for fetching storage parts of the nested group entity, or `null`
	 */
	public record MappingResult(
		@Nonnull PredicateMethodClassification<?, ?, ?>[] entityPartials,
		@Nullable PredicateMethodClassification<?, ?, ?>[] referencePartials,
		@Nonnull StoragePartRecipe entityRecipe,
		boolean needsReferencedEntityProxy,
		boolean needsGroupEntityProxy,
		@Nullable PredicateMethodClassification<?, ?, ?>[] referencedEntityPartials,
		@Nullable PredicateMethodClassification<?, ?, ?>[] groupEntityPartials,
		@Nullable StoragePartRecipe referencedEntityRecipe,
		@Nullable StoragePartRecipe groupEntityRecipe
	) {

	}

	/**
	 * Mutable accumulator for entity-level partials and recipe flags. Used for both top-level and nested entities.
	 */
	private static final class EntityMappingAccumulator {
		final LinkedHashSet<PredicateMethodClassification<?, ?, ?>> partials = createLinkedHashSet(16);
		boolean needsEntityBody;
		boolean needsGlobalAttributes;
		boolean needsReferences;
		final Set<String> neededAssociatedDataNames = createHashSet(4);
		final Set<Locale> neededAssociatedDataLocales = createHashSet(4);
		final Set<Locale> neededAttributeLocales = createHashSet(4);

		/**
		 * Indicates whether any partials have been accumulated (beyond always-included ones).
		 *
		 * @return `true` if any entity properties were accessed
		 */
		boolean hasContent() {
			return !this.partials.isEmpty() || this.needsEntityBody;
		}

		/**
		 * Builds the entity proxy partials array with always-included partials prepended and catch-all appended.
		 *
		 * @return the entity partials array in composition order
		 */
		@Nonnull
		@SuppressWarnings("rawtypes")
		PredicateMethodClassification[] buildEntityPartials() {
			final LinkedHashSet<PredicateMethodClassification<?, ?, ?>> all = createLinkedHashSet(
				this.partials.size() + 10
			);
			// schema partials first
			all.add(EntitySchemaPartial.GET_SCHEMA);
			all.add(EntitySchemaPartial.GET_TYPE);
			// version and droppable
			all.add(EntityVersionAndDroppablePartial.VERSION);
			all.add(EntityVersionAndDroppablePartial.DROPPED);
			all.add(EntityVersionAndDroppablePartial.GET_SCOPE);
			all.add(EntityVersionAndDroppablePartial.GET_ALL_LOCALES);
			all.add(EntityVersionAndDroppablePartial.GET_LOCALES);
			// specific partials
			all.addAll(this.partials);
			// catch-all last
			all.add(CatchAllPartial.OBJECT_METHODS);
			all.add(CatchAllPartial.INSTANCE);
			return all.toArray(PredicateMethodClassification[]::new);
		}

		/**
		 * Builds the storage part recipe from the accumulated flags.
		 *
		 * @return the immutable storage part recipe
		 */
		@Nonnull
		StoragePartRecipe buildRecipe() {
			return new StoragePartRecipe(
				this.needsEntityBody,
				this.needsGlobalAttributes,
				this.neededAttributeLocales.isEmpty()
					? Set.of()
					: Collections.unmodifiableSet(this.neededAttributeLocales),
				this.needsReferences,
				this.neededAssociatedDataNames.isEmpty()
					? Set.of()
					: Collections.unmodifiableSet(this.neededAssociatedDataNames),
				this.neededAssociatedDataLocales.isEmpty()
					? Set.of()
					: Collections.unmodifiableSet(this.neededAssociatedDataLocales)
			);
		}
	}

	/**
	 * Mutable accumulator used during path processing to collect partials and recipe flags for the top-level
	 * entity, reference proxy, and nested entity proxies.
	 */
	private static final class MappingContext {
		final EntityMappingAccumulator entityAcc = new EntityMappingAccumulator();
		final LinkedHashSet<PredicateMethodClassification<?, ?, ?>> referencePartials = createLinkedHashSet(8);
		boolean hasReferencePartials;
		boolean needsReferencedEntityProxy;
		boolean needsGroupEntityProxy;
		final EntityMappingAccumulator referencedEntityAcc = new EntityMappingAccumulator();
		final EntityMappingAccumulator groupEntityAcc = new EntityMappingAccumulator();

		/**
		 * Builds the final {@link MappingResult} by appending always-included partials and constructing the recipe.
		 *
		 * @return the immutable mapping result
		 */
		@Nonnull
		MappingResult buildResult() {
			// build entity partials array: always-included → specific → catch-all
			final PredicateMethodClassification<?, ?, ?>[] entityArray = this.entityAcc.buildEntityPartials();
			final PredicateMethodClassification<?, ?, ?>[] referenceArray =
				this.hasReferencePartials ? buildReferencePartials() : null;
			final StoragePartRecipe recipe = this.entityAcc.buildRecipe();

			// build nested entity partials and recipes if needed
			final PredicateMethodClassification<?, ?, ?>[] refEntityPartials =
				this.needsReferencedEntityProxy ? this.referencedEntityAcc.buildEntityPartials() : null;
			final PredicateMethodClassification<?, ?, ?>[] grpEntityPartials =
				this.needsGroupEntityProxy ? this.groupEntityAcc.buildEntityPartials() : null;
			final StoragePartRecipe refEntityRecipe =
				this.needsReferencedEntityProxy ? this.referencedEntityAcc.buildRecipe() : null;
			final StoragePartRecipe grpEntityRecipe =
				this.needsGroupEntityProxy ? this.groupEntityAcc.buildRecipe() : null;

			return new MappingResult(
				entityArray, referenceArray, recipe,
				this.needsReferencedEntityProxy, this.needsGroupEntityProxy,
				refEntityPartials, grpEntityPartials,
				refEntityRecipe, grpEntityRecipe
			);
		}

		/**
		 * Builds the reference proxy partials array with always-included partials appended.
		 *
		 * @return the reference partials array in composition order
		 */
		@Nonnull
		@SuppressWarnings("rawtypes")
		private PredicateMethodClassification[] buildReferencePartials() {
			final LinkedHashSet<PredicateMethodClassification<?, ?, ?>> all = createLinkedHashSet(
				this.referencePartials.size() + 12
			);
			// identity partials first
			all.add(ReferenceIdentityPartial.GET_REFERENCE_KEY);
			all.add(ReferenceIdentityPartial.GET_REFERENCED_ENTITY_TYPE);
			all.add(ReferenceIdentityPartial.GET_REFERENCE_CARDINALITY);
			all.add(ReferenceIdentityPartial.GET_REFERENCE_SCHEMA);
			all.add(ReferenceIdentityPartial.GET_REFERENCED_PRIMARY_KEY);
			all.add(ReferenceIdentityPartial.GET_REFERENCE_NAME);
			all.add(ReferenceIdentityPartial.GET_REFERENCE_SCHEMA_OR_THROW);
			// version and droppable
			all.add(ReferenceVersionAndDroppablePartial.VERSION);
			all.add(ReferenceVersionAndDroppablePartial.DROPPED);
			// specific partials
			all.addAll(this.referencePartials);
			// catch-all last
			all.add(CatchAllPartial.OBJECT_METHODS);
			all.add(CatchAllPartial.INSTANCE);
			return all.toArray(PredicateMethodClassification[]::new);
		}
	}
}
