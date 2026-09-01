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

package io.evitadb.performance.warmupload;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor.ReferenceSchemaBuilder;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.builder.SortableAttributeCompoundSchemaBuilder;
import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static io.evitadb.api.query.QueryConstraints.associatedDataContentAll;
import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.dataInLocalesAll;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.entityFetchAll;
import static io.evitadb.api.query.QueryConstraints.hierarchyContent;
import static io.evitadb.api.query.QueryConstraints.priceContentAll;
import static io.evitadb.api.query.QueryConstraints.referenceContentWithAttributes;

/**
 * Shared, transport-agnostic building blocks for copying one catalog into another: faithful schema
 * reconstruction and the full-content fetch requirement used to read the entities being copied.
 *
 * Extracted so both target modes of {@link IsolatedWarmupLoadBenchmark} - `remote`, which drives a
 * separate server over gRPC, and `embedded`, which drives an in-process engine - share exactly one
 * implementation, and so the older all-in-one {@link io.evitadb.spike.WarmupCopyCatalogBenchmark} can
 * adopt it without a second copy. The schema-replication rules encoded here are load-bearing and were
 * arrived at empirically; every non-obvious one carries the reason it exists at its site (see
 * {@link #replicateReflectedReferences} in particular). Duplicating them would mean rediscovering the
 * same engine rejections.
 *
 * Everything here operates on an open {@link EvitaSessionContract}, so the caller owns session
 * lifecycle and this class stays usable from both the embedded ({@code Evita}) and the remote
 * ({@code EvitaClient}) side.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class CatalogCopySupport {

	/**
	 * Purely static helper - never instantiated.
	 */
	private CatalogCopySupport() {
		throw new UnsupportedOperationException("CatalogCopySupport is a static helper and must not be instantiated.");
	}

	/**
	 * Reconstructs the source schema on the target catalog through the supplied (read-write) session.
	 * Global (catalog-level) attributes are created first, then entity schemas are created in three passes
	 * so cross-collection references resolve: (1) everything except references, (2) plain references, (3)
	 * reflected references (which need the plain reference they reflect to already exist on the target
	 * entity).
	 *
	 * @param session             open read-write session of the target catalog (in WARM_UP)
	 * @param sourceCatalogSchema the source catalog schema (for global attributes)
	 * @param sourceEntitySchemas the source entity schemas to reproduce
	 */
	public static void replicateSchema(
		@Nonnull final EvitaSessionContract session,
		@Nonnull final CatalogSchemaContract sourceCatalogSchema,
		@Nonnull final Collection<EntitySchemaContract> sourceEntitySchemas
	) {
		replicateGlobalAttributes(sourceCatalogSchema, session);
		// pass 1 - entity bodies without references
		for (final EntitySchemaContract source : sourceEntitySchemas) {
			final EntitySchemaBuilder builder = session.defineEntitySchema(source.getName());
			replicateEntityBase(source, builder);
			builder.updateVia(session);
		}
		// pass 2 - plain references
		for (final EntitySchemaContract source : sourceEntitySchemas) {
			final EntitySchemaBuilder builder = session.defineEntitySchema(source.getName());
			replicatePlainReferences(source, builder);
			builder.updateVia(session);
		}
		// pass 3 - reflected references
		for (final EntitySchemaContract source : sourceEntitySchemas) {
			final EntitySchemaBuilder builder = session.defineEntitySchema(source.getName());
			replicateReflectedReferences(source, builder);
			builder.updateVia(session);
		}
	}

	/**
	 * Builds the full-content fetch requirement for a collection. When the collection has no reflected
	 * references, {@link io.evitadb.api.query.QueryConstraints#entityFetchAll() entityFetchAll()} is used
	 * (guaranteed complete and future-proof). When it does, an equivalent fetch is assembled that pulls all
	 * attributes, associated data, prices, locales, hierarchy and only the plain (non-reflected) references
	 * with their attributes - so the read-only reflected projections are never transferred nor copied.
	 *
	 * @param schema the source schema of the collection
	 * @return the content requirement to use when fetching entities for copying
	 */
	@Nonnull
	public static EntityFetch buildContentRequirement(@Nonnull final EntitySchemaContract schema) {
		boolean hasReflectedReference = false;
		for (final ReferenceSchemaContract reference : schema.getReferences().values()) {
			if (reference instanceof ReflectedReferenceSchemaContract) {
				hasReflectedReference = true;
				break;
			}
		}
		if (!hasReflectedReference) {
			return entityFetchAll();
		}
		final List<EntityContentRequire> requirements = new ArrayList<>();
		requirements.add(attributeContentAll());
		requirements.add(associatedDataContentAll());
		requirements.add(dataInLocalesAll());
		if (schema.isWithPrice()) {
			requirements.add(priceContentAll());
		}
		if (schema.isWithHierarchy()) {
			requirements.add(hierarchyContent());
		}
		for (final ReferenceSchemaContract reference : schema.getReferences().values()) {
			if (reference instanceof ReflectedReferenceSchemaContract) {
				continue;
			}
			requirements.add(referenceContentWithAttributes(reference.getName()));
		}
		return entityFetch(requirements.toArray(new EntityContentRequire[0]));
	}

	/**
	 * Copies every catalog-level global attribute (including its global-uniqueness settings) onto the
	 * target catalog schema.
	 *
	 * @param sourceCatalogSchema the source catalog schema
	 * @param session             open WARM_UP session of the target catalog
	 */
	private static void replicateGlobalAttributes(
		@Nonnull final CatalogSchemaContract sourceCatalogSchema,
		@Nonnull final EvitaSessionContract session
	) {
		if (sourceCatalogSchema.getAttributes().isEmpty()) {
			return;
		}
		final CatalogSchemaBuilder builder = session.getCatalogSchema().openForWrite();
		for (final GlobalAttributeSchemaContract attribute : sourceCatalogSchema.getAttributes().values()) {
			builder.withAttribute(
				attribute.getName(),
				attribute.getType(),
				editor -> {
					applyAttribute(attribute, editor);
					final List<Scope> uniqueGlobally = new ArrayList<>(2);
					final List<Scope> uniqueGloballyWithinLocale = new ArrayList<>(2);
					for (final Scope scope : Scope.values()) {
						final GlobalAttributeUniquenessType type = attribute.getGlobalUniquenessType(scope);
						if (type == GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG) {
							uniqueGlobally.add(scope);
						} else if (type == GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG_LOCALE) {
							uniqueGloballyWithinLocale.add(scope);
						}
					}
					if (!uniqueGlobally.isEmpty()) {
						editor.uniqueGloballyInScope(uniqueGlobally.toArray(new Scope[0]));
					}
					if (!uniqueGloballyWithinLocale.isEmpty()) {
						editor.uniqueGloballyWithinLocaleInScope(uniqueGloballyWithinLocale.toArray(new Scope[0]));
					}
				}
			);
		}
		builder.updateVia(session);
	}

	/**
	 * Reproduces the primary-key strategy, hierarchy, price handling, locales, evolution mode, attributes,
	 * associated data and (entity-level) sortable attribute compounds of the source entity schema - but no
	 * references.
	 *
	 * @param source  the source entity schema
	 * @param builder the target entity schema builder
	 */
	private static void replicateEntityBase(
		@Nonnull final EntitySchemaContract source,
		@Nonnull final EntitySchemaBuilder builder
	) {
		final Set<EvolutionMode> evolutionModes = source.getEvolutionMode();
		if (evolutionModes.isEmpty()) {
			builder.verifySchemaStrictly();
		} else {
			builder.verifySchemaButAllow(evolutionModes.toArray(new EvolutionMode[0]));
		}

		if (source.isWithGeneratedPrimaryKey()) {
			builder.withGeneratedPrimaryKey();
		} else {
			builder.withoutGeneratedPrimaryKey();
		}

		if (source.isWithHierarchy()) {
			final Scope[] hierarchyScopes = scopesWhere(source::isHierarchyIndexedInScope);
			if (hierarchyScopes.length > 0) {
				builder.withHierarchyIndexedInScope(hierarchyScopes);
			} else {
				builder.withHierarchy();
			}
		}

		if (source.isWithPrice()) {
			final Scope[] priceScopes = scopesWhere(source::isPriceIndexedInScope);
			final Scope[] effectivePriceScopes = priceScopes.length > 0 ?
				priceScopes : new Scope[]{Scope.DEFAULT_SCOPE};
			final int indexedPricePlaces = source.getIndexedPricePlaces();
			final Currency[] currencies = source.getCurrencies().toArray(new Currency[0]);
			if (currencies.length > 0) {
				builder.withPriceInCurrencyIndexedInScope(indexedPricePlaces, currencies, effectivePriceScopes);
			} else {
				builder.withPriceIndexedInScope(indexedPricePlaces, effectivePriceScopes);
			}
		}

		if (!source.getLocales().isEmpty()) {
			builder.withLocale(source.getLocales().toArray(new Locale[0]));
		}

		for (final AttributeSchemaContract attribute : source.getAttributes().values()) {
			if (attribute instanceof GlobalAttributeSchemaContract) {
				// global attributes are defined at catalog level - just reference them here
				builder.withGlobalAttribute(attribute.getName());
			} else {
				builder.withAttribute(
					attribute.getName(),
					attribute.getType(),
					editor -> applyAttribute(attribute, editor)
				);
			}
		}

		for (final AssociatedDataSchemaContract associatedData : source.getAssociatedData().values()) {
			builder.withAssociatedData(
				associatedData.getName(),
				associatedData.getType(),
				editor -> {
					if (associatedData.isLocalized()) {
						editor.localized();
					}
					if (associatedData.isNullable()) {
						editor.nullable();
					}
				}
			);
		}

		for (final SortableAttributeCompoundSchemaContract compound : source.getSortableAttributeCompounds().values()) {
			applySortableAttributeCompound(compound, builder::withSortableAttributeCompound);
		}
	}

	/**
	 * Adds the plain (non-reflected) references of the source entity schema to the target builder,
	 * reproducing cardinality, group type, per-scope index type, faceting, reference attributes and
	 * reference-level sortable attribute compounds.
	 *
	 * @param source  the source entity schema
	 * @param builder the target entity schema builder
	 */
	private static void replicatePlainReferences(
		@Nonnull final EntitySchemaContract source,
		@Nonnull final EntitySchemaBuilder builder
	) {
		for (final ReferenceSchemaContract reference : source.getReferences().values()) {
			if (reference instanceof ReflectedReferenceSchemaContract) {
				continue;
			}
			if (reference.isReferencedEntityTypeManaged()) {
				builder.withReferenceToEntity(
					reference.getName(),
					reference.getReferencedEntityType(),
					reference.getCardinality(),
					editor -> applyReference(reference, editor)
				);
			} else {
				builder.withReferenceTo(
					reference.getName(),
					reference.getReferencedEntityType(),
					reference.getCardinality(),
					editor -> applyReference(reference, editor)
				);
			}
		}
	}

	/**
	 * Adds the reflected references of the source entity schema to the target builder, reproducing the
	 * reflected reference name, cardinality, faceting and attribute-inheritance behaviour.
	 *
	 * @param source  the source entity schema
	 * @param builder the target entity schema builder
	 */
	private static void replicateReflectedReferences(
		@Nonnull final EntitySchemaContract source,
		@Nonnull final EntitySchemaBuilder builder
	) {
		for (final ReferenceSchemaContract reference : source.getReferences().values()) {
			if (!(reference instanceof ReflectedReferenceSchemaContract reflected)) {
				continue;
			}
			builder.withReflectedReferenceToEntity(
				reflected.getName(),
				reflected.getReferencedEntityType(),
				reflected.getReflectedReferenceName(),
				editor -> {
					// Only override what the reflected reference does NOT inherit from its target reference.
					// Re-declaring an inherited property (cardinality, faceting) or an inherited attribute is
					// rejected by the engine ("... is inherited ... but it is already defined!"). A freshly
					// created reflected reference already defaults to inherited cardinality, so we set it only
					// when it is explicitly overridden - calling withCardinalityInherited() here would emit a
					// ModifyReferenceSchemaCardinalityMutation(null) that the WAL serializer cannot write.
					if (!reflected.isCardinalityInherited()) {
						editor.withCardinality(reflected.getCardinality());
					}
					// attribute-inheritance behaviour governs which target-reference attributes are inherited;
					// the inherited attributes themselves must NOT be re-declared here
					final AttributeInheritanceBehavior behavior = reflected.getAttributesInheritanceBehavior();
					final String[] inheritanceFilter = reflected.getAttributeInheritanceFilter();
					if (behavior == AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED) {
						editor.withAttributesInherited(inheritanceFilter);
					} else {
						editor.withAttributesInheritedExcept(inheritanceFilter);
					}
					if (!reflected.isFacetedInherited()) {
						final Scope[] facetedScopes = scopesWhere(reflected::isFacetedInScope);
						if (facetedScopes.length > 0) {
							editor.facetedInScope(facetedScopes);
						}
					}
				}
			);
		}
	}

	/**
	 * Applies the group type, per-scope index type, faceting, attributes and sortable attribute compounds
	 * of a plain reference onto its target reference builder.
	 *
	 * @param reference the source reference schema
	 * @param editor    the target reference builder
	 */
	private static void applyReference(
		@Nonnull final ReferenceSchemaContract reference,
		@Nonnull final ReferenceSchemaBuilder editor
	) {
		if (reference.getReferencedGroupType() != null) {
			if (reference.isReferencedGroupTypeManaged()) {
				editor.withGroupTypeRelatedToEntity(reference.getReferencedGroupType());
			} else {
				editor.withGroupType(reference.getReferencedGroupType());
			}
		}

		final List<Scope> forFiltering = new ArrayList<>(2);
		final List<Scope> forFilteringAndPartitioning = new ArrayList<>(2);
		for (final Scope scope : Scope.values()) {
			switch (reference.getReferenceIndexType(scope)) {
				case FOR_FILTERING -> forFiltering.add(scope);
				case FOR_FILTERING_AND_PARTITIONING -> forFilteringAndPartitioning.add(scope);
				case NONE -> {
					// not indexed in this scope - nothing to do
				}
			}
		}
		if (!forFiltering.isEmpty()) {
			editor.indexedForFilteringInScope(forFiltering.toArray(new Scope[0]));
		}
		if (!forFilteringAndPartitioning.isEmpty()) {
			editor.indexedForFilteringAndPartitioningInScope(forFilteringAndPartitioning.toArray(new Scope[0]));
		}

		final Scope[] facetedScopes = scopesWhere(reference::isFacetedInScope);
		if (facetedScopes.length > 0) {
			editor.facetedInScope(facetedScopes);
		}

		for (final AttributeSchemaContract attribute : reference.getAttributes().values()) {
			editor.withAttribute(
				attribute.getName(),
				attribute.getType(),
				attributeEditor -> applyAttribute(attribute, attributeEditor)
			);
		}

		for (final SortableAttributeCompoundSchemaContract compound :
			reference.getSortableAttributeCompounds().values()) {
			applySortableAttributeCompound(compound, editor::withSortableAttributeCompound);
		}
	}

	/**
	 * Copies all common attribute flags (localized, nullable, representative, default value, indexed
	 * decimal places, per-scope filterable/sortable and per-scope collection-level uniqueness) from the
	 * source attribute onto the given editor. Works for entity, reference and global attribute editors
	 * alike.
	 *
	 * @param attribute the source attribute schema
	 * @param editor    the target attribute editor
	 */
	private static void applyAttribute(
		@Nonnull final AttributeSchemaContract attribute,
		@Nonnull final AttributeSchemaEditor<?> editor
	) {
		if (attribute.isLocalized()) {
			editor.localized();
		}
		if (attribute.isNullable()) {
			editor.nullable();
		}
		if (attribute.isRepresentative()) {
			editor.representative();
		}
		if (attribute.getDefaultValue() != null) {
			editor.withDefaultValue(attribute.getDefaultValue());
		}
		if (attribute.getIndexedDecimalPlaces() > 0) {
			editor.indexDecimalPlaces(attribute.getIndexedDecimalPlaces());
		}

		final Scope[] filterableScopes = scopesWhere(attribute::isFilterableInScope);
		if (filterableScopes.length > 0) {
			editor.filterableInScope(filterableScopes);
		}
		final Scope[] sortableScopes = scopesWhere(attribute::isSortableInScope);
		if (sortableScopes.length > 0) {
			editor.sortableInScope(sortableScopes);
		}

		final List<Scope> unique = new ArrayList<>(2);
		final List<Scope> uniqueWithinLocale = new ArrayList<>(2);
		for (final Scope scope : Scope.values()) {
			final AttributeUniquenessType type = attribute.getUniquenessType(scope);
			if (type == AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION) {
				unique.add(scope);
			} else if (type == AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE) {
				uniqueWithinLocale.add(scope);
			}
		}
		if (!unique.isEmpty()) {
			editor.uniqueInScope(unique.toArray(new Scope[0]));
		}
		if (!uniqueWithinLocale.isEmpty()) {
			editor.uniqueWithinLocaleInScope(uniqueWithinLocale.toArray(new Scope[0]));
		}
	}

	/**
	 * Recreates a sortable attribute compound (its ordered attribute elements and per-scope indexing)
	 * through the supplied factory - which is either the entity or the reference builder's
	 * {@code withSortableAttributeCompound} method.
	 *
	 * @param compound the source sortable attribute compound
	 * @param factory  builder method that registers the compound
	 */
	private static void applySortableAttributeCompound(
		@Nonnull final SortableAttributeCompoundSchemaContract compound,
		@Nonnull final SortableAttributeCompoundFactory factory
	) {
		final List<AttributeElement> elements = compound.getAttributeElements();
		factory.create(
			compound.getName(),
			elements.toArray(new AttributeElement[0]),
			editor -> {
				final Scope[] indexedScopes = scopesWhere(compound::isIndexedInScope);
				if (indexedScopes.length > 0) {
					editor.indexedInScope(indexedScopes);
				}
			}
		);
	}

	/**
	 * Returns the scopes for which the supplied predicate holds.
	 *
	 * @param predicate scope test
	 * @return matching scopes (never null, possibly empty)
	 */
	@Nonnull
	private static Scope[] scopesWhere(@Nonnull final Predicate<Scope> predicate) {
		final List<Scope> matching = new ArrayList<>(Scope.values().length);
		for (final Scope scope : Scope.values()) {
			if (predicate.test(scope)) {
				matching.add(scope);
			}
		}
		return matching.toArray(new Scope[0]);
	}

	/**
	 * Narrow functional bridge for the two {@code withSortableAttributeCompound(name, elements, whichIs)}
	 * builder methods (entity-level and reference-level), which share an identical signature but live on
	 * unrelated editor interfaces.
	 */
	@FunctionalInterface
	private interface SortableAttributeCompoundFactory {

		/**
		 * Registers a sortable attribute compound.
		 *
		 * @param name     compound name
		 * @param elements ordered attribute elements
		 * @param whichIs  compound configuration callback
		 */
		void create(
			@Nonnull String name,
			@Nonnull AttributeElement[] elements,
			@Nonnull Consumer<SortableAttributeCompoundSchemaBuilder> whichIs
		);
	}

}
