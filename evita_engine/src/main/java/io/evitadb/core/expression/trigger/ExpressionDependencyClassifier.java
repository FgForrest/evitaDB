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

package io.evitadb.core.expression.trigger;

import io.evitadb.api.query.expression.object.accessor.entity.EntityContractAccessor;
import io.evitadb.api.query.expression.object.accessor.entity.ReferenceContractAccessor;
import io.evitadb.api.query.expression.visitor.AccessedDataFinder;
import io.evitadb.api.query.expression.visitor.ElementPathItem;
import io.evitadb.api.query.expression.visitor.IdentifierPathItem;
import io.evitadb.api.query.expression.visitor.PathItem;
import io.evitadb.api.query.expression.visitor.VariablePathItem;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static io.evitadb.utils.CollectionUtils.createLinkedHashMap;
import static io.evitadb.utils.CollectionUtils.createLinkedHashSet;

/**
 * Stateless utility that classifies expression access paths (produced by {@link AccessedDataFinder}) into
 * cross-entity dependency buckets and local dependency metadata. Shared by {@link FacetExpressionTriggerFactory}
 * and {@link HistogramExpressionTriggerFactory} to avoid duplication of path-analysis logic.
 *
 * The classifier distinguishes:
 *
 * - **Cross-entity paths** — reaching into referenced entities, group entities, or parent entities —
 *   producing {@link DependencyKey} entries with associated dependent attribute sets
 * - **Local paths** — referencing the owner entity's own attributes, reference attributes, associated data,
 *   or parent entity usage — producing {@link LocalDependencies} metadata
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ExpressionDependencyClassifier {

	/**
	 * Classifies accessed data paths into {@link DependencyKey} buckets and collects the dependent
	 * attribute names for each. Paths that reference only local data (`$entity.*`,
	 * `$reference.attributes['x']`) are ignored — they do not produce cross-entity dependencies.
	 *
	 * @param paths the accessed data paths from {@link AccessedDataFinder#findAccessedPaths}
	 * @return map from dependency key to the set of dependent attribute names (empty if local-only)
	 */
	@Nonnull
	static LinkedHashMap<DependencyKey, Set<String>> classifyPaths(
		@Nonnull List<List<PathItem>> paths
	) {
		final LinkedHashMap<DependencyKey, Set<String>> result = createLinkedHashMap(2);

		for (final List<PathItem> path : paths) {
			final DependencyType depType = detectDependencyType(path);
			if (depType != null) {
				final String dependentRefName = extractDependentReferenceName(path);
				final String attributeName = extractDependentAttribute(path, depType);
				if (attributeName != null) {
					final DependencyKey key = new DependencyKey(depType, dependentRefName);
					result.computeIfAbsent(key, k -> createLinkedHashSet(4))
						.add(attributeName);
				} else {
					log.warn(
						"Cross-entity path for dependency type `{}` on reference `{}` has no extractable " +
							"dependent attribute. The path will be ignored for trigger attribute-level indexing.",
						depType, dependentRefName
					);
				}
			}
		}

		return result;
	}

	/**
	 * Detects the {@link DependencyType} for a single path. Returns `null` for local-only paths.
	 *
	 * A cross-entity path starts with either:
	 *
	 * - `$reference` followed by `referencedEntity` or `groupEntity` — position 2 discriminates
	 *   entity-attribute dependencies (`attributes`/`localizedAttributes`) from reference-attribute
	 *   dependencies (`references`)
	 * - `$entity` followed by `parentEntity` — same position-2 discrimination for parent entity
	 *   attribute vs. parent entity reference-attribute dependencies
	 *
	 * @param path the accessed data path
	 * @return the dependency type, or `null` if the path is local-only
	 */
	@Nullable
	static DependencyType detectDependencyType(@Nonnull List<PathItem> path) {
		// minimum cross-entity path: [$reference, referencedEntity/groupEntity, attributes, attrName]
		if (path.size() < 4) {
			return null;
		}

		final PathItem first = path.get(0);
		final PathItem second = path.get(1);

		if (
			first instanceof VariablePathItem variable
				&& second instanceof IdentifierPathItem identifier
		) {
			if (ReferenceContractAccessor.REFERENCE_VARIABLE_NAME.equals(variable.value())) {
				// $reference.referencedEntity.* or $reference.groupEntity.*
				final boolean isReferencedEntity =
					ReferenceContractAccessor.REFERENCED_ENTITY_PROPERTY.equals(identifier.value());
				final boolean isGroupEntity =
					ReferenceContractAccessor.GROUP_ENTITY_PROPERTY.equals(identifier.value());

				if (!isReferencedEntity && !isGroupEntity) {
					return null;
				}

				// check position 2 to distinguish entity-attribute from reference-attribute dependency
				final PathItem third = path.get(2);
				if (third instanceof IdentifierPathItem thirdIdentifier) {
					if (isAttributesProperty(thirdIdentifier.value())) {
						return isReferencedEntity
							? DependencyType.REFERENCED_ENTITY_ATTRIBUTE
							: DependencyType.GROUP_ENTITY_ATTRIBUTE;
					} else if (
						EntityContractAccessor.REFERENCES_PROPERTY.equals(thirdIdentifier.value())
					) {
						return isReferencedEntity
							? DependencyType.REFERENCED_ENTITY_REFERENCE_ATTRIBUTE
							: DependencyType.GROUP_ENTITY_REFERENCE_ATTRIBUTE;
					}
				}
			} else if (
				EntityContractAccessor.ENTITY_VARIABLE_NAME.equals(variable.value())
					&& EntityContractAccessor.PARENT_ENTITY_PROPERTY.equals(identifier.value())
			) {
				// $entity.parentEntity.* — parent entity of the owner (same entity type)
				final PathItem third = path.get(2);
				if (third instanceof IdentifierPathItem thirdIdentifier) {
					if (isAttributesProperty(thirdIdentifier.value())) {
						return DependencyType.PARENT_ENTITY_ATTRIBUTE;
					} else if (
						EntityContractAccessor.REFERENCES_PROPERTY.equals(thirdIdentifier.value())
					) {
						return DependencyType.PARENT_ENTITY_REFERENCE_ATTRIBUTE;
					}
				}
			}
		}

		return null;
	}

	/**
	 * Extracts the dependent attribute name from a cross-entity path. Scans from the appropriate
	 * start position depending on the dependency type:
	 *
	 * - For `REFERENCED_ENTITY_ATTRIBUTE` / `GROUP_ENTITY_ATTRIBUTE` / `PARENT_ENTITY_ATTRIBUTE`:
	 *   scan from position 2 (immediately after `referencedEntity`/`groupEntity`/`parentEntity`)
	 * - For `*_REFERENCE_ATTRIBUTE`: scan from position 4 (after `references['r']`)
	 *
	 * @param path    the accessed data path (must be a cross-entity path)
	 * @param depType the dependency type determining the scan start position
	 * @return the attribute name, or `null` if no attribute access is found in the path
	 */
	@Nullable
	static String extractDependentAttribute(
		@Nonnull List<PathItem> path,
		@Nonnull DependencyType depType
	) {
		final int startIndex = switch (depType) {
			case REFERENCED_ENTITY_REFERENCE_ATTRIBUTE, GROUP_ENTITY_REFERENCE_ATTRIBUTE,
				PARENT_ENTITY_REFERENCE_ATTRIBUTE -> 4;
			default -> 2;
		};
		for (int i = startIndex; i < path.size() - 1; i++) {
			final PathItem item = path.get(i);
			if (
				item instanceof IdentifierPathItem identifier
					&& isAttributesProperty(identifier.value())
					&& path.get(i + 1) instanceof ElementPathItem element
			) {
				return element.value();
			}
		}
		return null;
	}

	/**
	 * Extracts the dependent reference name from a cross-entity path. For reference-attribute paths
	 * (`[$reference, referencedEntity, references, x, ...]`), position 3 is an {@link ElementPathItem}
	 * containing the reference name. For entity-attribute paths, returns `null`.
	 *
	 * @param path the accessed data path
	 * @return the reference name on the target entity, or `null` if not a reference-attribute path
	 */
	@Nullable
	static String extractDependentReferenceName(@Nonnull List<PathItem> path) {
		if (
			path.size() > 3
				&& path.get(2) instanceof IdentifierPathItem identifier
				&& EntityContractAccessor.REFERENCES_PROPERTY.equals(identifier.value())
				&& path.get(3) instanceof ElementPathItem element
		) {
			return element.value();
		}
		return null;
	}

	/**
	 * Checks whether the given property name refers to an attributes accessor.
	 * Delegates to {@link EntityContractAccessor#isAttributesProperty(String)}.
	 *
	 * @param propertyName the property name to check
	 * @return `true` if the name matches `attributes` or `localizedAttributes`
	 */
	static boolean isAttributesProperty(@Nonnull String propertyName) {
		return EntityContractAccessor.isAttributesProperty(propertyName);
	}

	/**
	 * Checks whether the given property name refers to an associated data accessor.
	 *
	 * @param propertyName the property name to check
	 * @return `true` if the name matches `associatedData` or `localizedAssociatedData`
	 */
	static boolean isAssociatedDataProperty(@Nonnull String propertyName) {
		return EntityContractAccessor.ASSOCIATED_DATA_PROPERTY.equals(propertyName)
			|| EntityContractAccessor.LOCALIZED_ASSOCIATED_DATA_PROPERTY.equals(propertyName);
	}

	/**
	 * Extracts local dependencies from the accessed data paths. Local dependencies are paths that
	 * reference the owner entity's own data (`$entity.attributes['x']`, `$reference.attributes['y']`,
	 * `$entity.associatedData['z']`, `$entity.parentEntity`) — as opposed to cross-entity paths that
	 * reach into referenced or group entities.
	 *
	 * @param paths the accessed data paths from {@link AccessedDataFinder#findAccessedPaths}
	 * @return a record containing all extracted local dependencies
	 */
	@Nonnull
	static LocalDependencies extractLocalDependencies(@Nonnull List<List<PathItem>> paths) {
		Set<String> entityAttributes = null;
		Set<String> referenceAttributes = null;
		Set<String> associatedData = null;
		boolean usesParent = false;

		for (final List<PathItem> path : paths) {
			if (path.size() < 2) {
				continue;
			}
			final PathItem first = path.get(0);
			final PathItem second = path.get(1);

			if (
				!(first instanceof VariablePathItem variable)
					|| !(second instanceof IdentifierPathItem identifier)
			) {
				continue;
			}

			if (EntityContractAccessor.ENTITY_VARIABLE_NAME.equals(variable.value())) {
				// $entity.attributes['x'] or $entity.localizedAttributes['x']
				if (
					isAttributesProperty(identifier.value())
						&& path.size() > 2 && path.get(2) instanceof ElementPathItem element
				) {
					if (entityAttributes == null) {
						entityAttributes = createLinkedHashSet(4);
					}
					entityAttributes.add(element.value());
				}
				// $entity.associatedData['x'] or $entity.localizedAssociatedData['x']
				else if (
					isAssociatedDataProperty(identifier.value())
						&& path.size() > 2 && path.get(2) instanceof ElementPathItem element
				) {
					if (associatedData == null) {
						associatedData = createLinkedHashSet(4);
					}
					associatedData.add(element.value());
				}
				// $entity.parentEntity
				else if (
					EntityContractAccessor.PARENT_ENTITY_PROPERTY.equals(identifier.value())
				) {
					usesParent = true;
				}
			} else if (
				ReferenceContractAccessor.REFERENCE_VARIABLE_NAME.equals(variable.value())
					&& isAttributesProperty(identifier.value())
					&& path.size() > 2
					&& path.get(2) instanceof ElementPathItem element
			) {
				if (referenceAttributes == null) {
					referenceAttributes = createLinkedHashSet(4);
				}
				referenceAttributes.add(element.value());
			}
		}

		return new LocalDependencies(
			entityAttributes != null ? entityAttributes : Set.of(),
			referenceAttributes != null ? referenceAttributes : Set.of(),
			associatedData != null ? associatedData : Set.of(),
			usesParent
		);
	}

	/**
	 * Resolves the mutated entity type from the owner entity type, reference schema and dependency
	 * type. The mutated entity type is the entity type whose changes fire the trigger — it is the
	 * key under which the trigger is indexed in the registry's inverted index.
	 *
	 * @param ownerEntityType the entity type that owns the reference (e.g. "product")
	 * @param referenceSchema the reference schema carrying the expression
	 * @param dependencyType  the dependency type classifying the cross-entity relationship
	 * @return the entity type name of the mutated entity
	 */
	@Nonnull
	static String resolveMutatedEntityType(
		@Nonnull String ownerEntityType,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull DependencyType dependencyType
	) {
		return switch (dependencyType) {
			case REFERENCED_ENTITY_ATTRIBUTE, REFERENCED_ENTITY_REFERENCE_ATTRIBUTE ->
				referenceSchema.getReferencedEntityType();
			case GROUP_ENTITY_ATTRIBUTE, GROUP_ENTITY_REFERENCE_ATTRIBUTE -> {
				final String groupType = referenceSchema.getReferencedGroupType();
				if (groupType == null) {
					throw new IllegalStateException(
						"Reference `" + referenceSchema.getName() + "` has a group entity dependency " +
							"but no referenced group type is defined."
					);
				}
				yield groupType;
			}
			// parent entity is the same entity type as the owner — hierarchy is self-referential
			case PARENT_ENTITY_ATTRIBUTE, PARENT_ENTITY_REFERENCE_ATTRIBUTE -> ownerEntityType;
		};
	}

	/**
	 * Composite key for dependency classification that includes both the dependency type and the
	 * optional reference name on the target entity. This allows an expression that accesses
	 * multiple reference names on the same target entity to produce separate triggers per reference.
	 *
	 * @param type          the dependency type
	 * @param referenceName the reference name on the target entity, or `null` for entity-attribute deps
	 */
	record DependencyKey(@Nonnull DependencyType type, @Nullable String referenceName) {
	}

	/**
	 * Holds local dependency metadata extracted from expression paths: entity-level attributes,
	 * reference-level attributes, associated data names, and parent entity usage.
	 *
	 * @param entityAttributes    entity-level attribute names read by the expression
	 * @param referenceAttributes reference-level attribute names read by the expression
	 * @param associatedData      associated data names read by the expression
	 * @param usesParent          whether the expression accesses `$entity.parentEntity`
	 */
	record LocalDependencies(
		@Nonnull Set<String> entityAttributes,
		@Nonnull Set<String> referenceAttributes,
		@Nonnull Set<String> associatedData,
		boolean usesParent
	) {
	}

}
