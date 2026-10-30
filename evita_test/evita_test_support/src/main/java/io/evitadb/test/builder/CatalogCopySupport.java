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

package io.evitadb.test.builder;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.EntityContract;
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
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor.ReferenceSchemaBuilder;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.builder.SortableAttributeCompoundSchemaBuilder;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * Extracted so both target modes of the `IsolatedWarmupLoadBenchmark` - `remote`, which drives a
 * separate server over gRPC, and `embedded`, which drives an in-process engine - share exactly one
 * implementation, and so the older all-in-one `WarmupCopyCatalogBenchmark` can adopt it without a second
 * copy. It lives in the test support so the functional tests share it too: the persisted-storage check of
 * the virtual reference partitions rebuilds a catalog from its entity bodies through it. The
 * schema-replication rules encoded here are load-bearing and were arrived at empirically; every non-obvious
 * one carries the reason it exists at its site (see {@link #replicateReflectedReferences} in particular).
 * Duplicating them would mean rediscovering the same engine rejections.
 *
 * Everything here operates on an open {@link EvitaSessionContract}, so the caller owns session
 * lifecycle and this class stays usable from both the embedded ({@code Evita}) and the remote
 * ({@code EvitaClient}) side.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class CatalogCopySupport {

	/**
	 * Separates the segments of a searchable-field path: entity type, reference and field name.
	 */
	private static final String PATH_SEPARATOR = ".";
	/**
	 * Path prefix of a catalog-level global attribute - the flag lives on the global schema, so it applies to every
	 * entity type that uses the attribute.
	 */
	private static final String GLOBAL_ATTRIBUTE_PREFIX = "*" + PATH_SEPARATOR;
	/**
	 * Marks the last path segment as associated data rather than an attribute - the two may share a name.
	 */
	private static final String ASSOCIATED_DATA_MARKER = "@";
	/**
	 * Separates an associated data path from the EvitaEL expression selecting the text of its complex values.
	 */
	private static final String TEXT_EXPRESSION_SEPARATOR = "=";

	/**
	 * Reference names promoted from `FOR_FILTERING` to `FOR_FILTERING_AND_PARTITIONING` on the target schema,
	 * parsed once from the `evita.warmup.raiseReferences` system property. A single `*` promotes every plain
	 * reference; an empty value (the default) replicates the source index types faithfully.
	 *
	 * This exists to measure the write path in the shape a client creates by flipping that one schema flag.
	 * Raising a reference on an already-populated catalog is not supported (the engine does not rebuild
	 * indexes on a schema change - issue #409), but the target catalog here is created empty and populated
	 * afterwards, which is precisely the full-reindex path that *is* supported.
	 */
	private static final Set<String> RAISED_REFERENCES = parseRaisedReferences();
	/**
	 * Fields made searchable on the target schema, keyed by their path, parsed once from the
	 * `evita.warmup.searchable` system property - see {@link #parseSearchableFields()} for the notation. Empty (the
	 * default) replicates the source faithfully.
	 *
	 * This exists to measure what fulltext maintenance costs the write path of a real catalog whose schema predates
	 * the fulltext index. Declaring a field searchable on a populated catalog does not index the values it already
	 * holds (issue #409), but the target catalog here is created empty and populated afterwards, so every copied value
	 * is indexed - the same reasoning as for {@link #RAISED_REFERENCES}.
	 */
	private static final Map<String, SearchableField> SEARCHABLE_FIELDS = parseSearchableFields();
	/**
	 * Associated data given a locale on the target schema, keyed by path (`<entityType>.@<associatedData>`), parsed
	 * once from the `evita.warmup.localize` system property - see {@link #parseLocalizedAssociatedData()}. Empty (the
	 * default) replicates the source faithfully.
	 *
	 * This exists because a searchable field must carry a locale, and a catalog whose text predates that rule may keep
	 * it without one - the migration the fulltext decision record names as the accepted cost of the rule. The copy
	 * declares the associated data localized, adds the locale to the entity type, and writes every value of it under
	 * the given locale, so the field can be made searchable in the same copy. It also declares the associated data
	 * nullable: a localized value is mandatory in every locale of an entity, and an entity with other locales holds
	 * only the one value the source had.
	 */
	private static final Map<String, Locale> LOCALIZED_ASSOCIATED_DATA = parseLocalizedAssociatedData();

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
		verifySearchableFieldsExist(sourceCatalogSchema, sourceEntitySchemas);
		verifyLocalizedAssociatedDataExist(sourceEntitySchemas);
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
		return entityFetch(requirements.toArray(EntityContentRequire.EMPTY_ARRAY));
	}

	/**
	 * Creates the builder that writes one source entity into the target catalog. An entity type none of whose
	 * associated data {@link #LOCALIZED_ASSOCIATED_DATA} names is copied verbatim; otherwise every value of the named
	 * associated data that carries no locale is moved under the configured one, and the copy is validated against
	 * the target's schema, which declares the associated data localized.
	 *
	 * @param entity       the source entity, fetched with {@link #buildContentRequirement}
	 * @param targetSchema schema of the entity type in the target catalog
	 * @return the builder to upsert into the target catalog
	 */
	@Nonnull
	public static CopyExistingEntityBuilder createCopyBuilder(
		@Nonnull final EntityContract entity,
		@Nonnull final EntitySchemaContract targetSchema
	) {
		if (LOCALIZED_ASSOCIATED_DATA.isEmpty()) {
			return new CopyExistingEntityBuilder(entity);
		}
		final String pathPrefix = entity.getType() + PATH_SEPARATOR + ASSOCIATED_DATA_MARKER;
		final Collection<AssociatedDataValue> sourceValues = entity.getAssociatedDataValues();
		final List<AssociatedDataValue> targetValues = new ArrayList<>(sourceValues.size());
		boolean localized = false;
		for (final AssociatedDataValue value : sourceValues) {
			final AssociatedDataKey key = value.key();
			final Locale locale = key.localized() ?
				null : LOCALIZED_ASSOCIATED_DATA.get(pathPrefix + key.associatedDataName());
			if (locale == null) {
				targetValues.add(value);
			} else {
				targetValues.add(
					new AssociatedDataValue(
						value.version(), new AssociatedDataKey(key.associatedDataName(), locale), value.value()
					)
				);
				localized = true;
			}
		}
		return localized ?
			new CopyExistingEntityBuilder(entity, targetSchema, targetValues) :
			new CopyExistingEntityBuilder(entity);
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
					applySearchable(GLOBAL_ATTRIBUTE_PREFIX + attribute.getName(), editor);
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
						editor.uniqueGloballyInScope(uniqueGlobally.toArray(Scope.NO_SCOPE));
					}
					if (!uniqueGloballyWithinLocale.isEmpty()) {
						editor.uniqueGloballyWithinLocaleInScope(uniqueGloballyWithinLocale.toArray(Scope.NO_SCOPE));
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

		final Set<Locale> locales = new LinkedHashSet<>(source.getLocales());
		locales.addAll(getAddedLocales(source.getName()));
		if (!locales.isEmpty()) {
			builder.withLocale(locales.toArray(new Locale[0]));
		}

		for (final AttributeSchemaContract attribute : source.getAttributes().values()) {
			if (attribute instanceof GlobalAttributeSchemaContract) {
				// global attributes are defined at catalog level - just reference them here
				builder.withGlobalAttribute(attribute.getName());
			} else {
				builder.withAttribute(
					attribute.getName(),
					attribute.getType(),
					editor -> {
						applyAttribute(attribute, editor);
						applySearchable(source.getName() + PATH_SEPARATOR + attribute.getName(), editor);
					}
				);
			}
		}

		for (final AssociatedDataSchemaContract associatedData : source.getAssociatedData().values()) {
			final String path = source.getName() + PATH_SEPARATOR + ASSOCIATED_DATA_MARKER + associatedData.getName();
			builder.withAssociatedData(
				associatedData.getName(),
				associatedData.getType(),
				editor -> {
					final Locale addedLocale = LOCALIZED_ASSOCIATED_DATA.get(path);
					if (associatedData.isLocalized() || addedLocale != null) {
						editor.localized();
					}
					if (addedLocale != null) {
						System.out.println(
							"[localized] " + path + " under " + addedLocale.toLanguageTag() + ", nullable"
						);
					}
					// a localized value is mandatory in every locale of an entity, and the source holds one value
					if (associatedData.isNullable() || addedLocale != null) {
						editor.nullable();
					}
					final SearchableField searchable = SEARCHABLE_FIELDS.get(path);
					if (searchable != null) {
						if (searchable.textExpression() == null) {
							editor.searchable();
						} else {
							editor.searchable(ExpressionFactory.parse(searchable.textExpression()));
						}
						printSearchable(searchable);
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
					editor -> applyReference(source.getName(), reference, editor)
				);
			} else {
				builder.withReferenceTo(
					reference.getName(),
					reference.getReferencedEntityType(),
					reference.getCardinality(),
					editor -> applyReference(source.getName(), reference, editor)
				);
			}
		}
	}

	/**
	 * Adds the reflected references of the source entity schema to the target builder, reproducing the
	 * reflected reference name and every setting it overrides rather than inherits: cardinality, index type, indexed
	 * components, faceting (an explicit "not faceted" included) and attribute-inheritance behaviour.
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
					if (!reflected.isIndexedInherited()) {
						applyIndexType(reflected, editor, false);
					}
					if (!reflected.isIndexedComponentsInherited()) {
						applyIndexedComponents(reflected, editor);
					}
					if (!reflected.isFacetedInherited()) {
						final Scope[] facetedScopes = scopesWhere(reflected::isFacetedInScope);
						if (facetedScopes.length > 0) {
							editor.facetedInScope(facetedScopes);
						} else {
							// an explicit "not faceted" - left unset, the target would inherit the faceting (and the
							// partial-faceting expression) of the reference it reflects; the scope-less variant is the
							// one that works before the reflected reference's own entity schema exists
							editor.nonFaceted();
						}
						// the partial-faceting expression is inherited together with faceting itself
						applyFacetedPartially(reflected, editor);
					}
				}
			);
		}
	}

	/**
	 * Parses {@link #RAISED_REFERENCES} from the `evita.warmup.raiseReferences` system property.
	 *
	 * @return the reference names to promote, empty when the property is unset or blank
	 */
	@Nonnull
	private static Set<String> parseRaisedReferences() {
		final String raw = System.getProperty("evita.warmup.raiseReferences", "");
		if (raw.isBlank()) {
			return Set.of();
		}
		final String[] names = raw.split(",");
		final Set<String> result = CollectionUtils.createHashSet(names.length);
		for (final String name : names) {
			final String trimmed = name.trim();
			if (!trimmed.isEmpty()) {
				result.add(trimmed);
			}
		}
		return result;
	}

	/**
	 * Tells whether a reference should be promoted to `FOR_FILTERING_AND_PARTITIONING` on the target.
	 *
	 * @param referenceName name of the replicated reference
	 * @return true when the reference is named by `evita.warmup.raiseReferences`, or that property is `*`
	 */
	private static boolean isRaisedToPartitioning(@Nonnull final String referenceName) {
		return RAISED_REFERENCES.contains("*") || RAISED_REFERENCES.contains(referenceName);
	}

	/**
	 * Parses {@link #SEARCHABLE_FIELDS} from the `evita.warmup.searchable` system property. Entries are separated by
	 * `;` rather than `,`, because an EvitaEL text expression may itself contain commas. Each entry is a path:
	 *
	 * - `*.name` - the catalog-level global attribute `name`
	 * - `product.name` - the attribute `name` of entity type `product`
	 * - `product.brand.name` - the attribute `name` of the reference `brand` of entity type `product`
	 * - `product.@description` - the `String` / `String[]` associated data `description`
	 * - `product.@content=$value.blocks.*[$.text]` - complex associated data with the expression selecting its text
	 *
	 * @return the fields keyed by their path (the entry without its expression), empty when the property is unset
	 */
	@Nonnull
	private static Map<String, SearchableField> parseSearchableFields() {
		final String raw = System.getProperty("evita.warmup.searchable", "");
		if (raw.isBlank()) {
			return Map.of();
		}
		final String[] entries = raw.split(";");
		final Map<String, SearchableField> result = CollectionUtils.createLinkedHashMap(entries.length);
		for (final String entry : entries) {
			final String trimmed = entry.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			final int expressionStart = trimmed.indexOf(TEXT_EXPRESSION_SEPARATOR);
			final String path = expressionStart < 0 ? trimmed : trimmed.substring(0, expressionStart).trim();
			final String expression = expressionStart < 0 ? null : trimmed.substring(expressionStart + 1).trim();
			if (expression != null && !path.contains(PATH_SEPARATOR + ASSOCIATED_DATA_MARKER)) {
				throw new GenericEvitaInternalError(
					"Searchable field `" + trimmed + "` carries a text expression, which only associated data takes.",
					"Only associated data takes a text expression."
				);
			}
			result.put(path, new SearchableField(path, expression));
		}
		return result;
	}

	/**
	 * Fails the copy before anything is written when a path in {@link #SEARCHABLE_FIELDS} names no field of the
	 * source schema. A misspelt path would otherwise be ignored silently, and the run would measure a catalog with
	 * less fulltext maintenance than it claims.
	 *
	 * @param sourceCatalogSchema the source catalog schema (for global attributes)
	 * @param sourceEntitySchemas the source entity schemas
	 */
	private static void verifySearchableFieldsExist(
		@Nonnull final CatalogSchemaContract sourceCatalogSchema,
		@Nonnull final Collection<EntitySchemaContract> sourceEntitySchemas
	) {
		if (SEARCHABLE_FIELDS.isEmpty()) {
			return;
		}
		final Map<String, EntitySchemaContract> byName = CollectionUtils.createHashMap(sourceEntitySchemas.size());
		for (final EntitySchemaContract schema : sourceEntitySchemas) {
			byName.put(schema.getName(), schema);
		}
		final List<String> unresolved = new ArrayList<>(SEARCHABLE_FIELDS.size());
		for (final String path : SEARCHABLE_FIELDS.keySet()) {
			if (!searchableFieldExists(path, sourceCatalogSchema, byName)) {
				unresolved.add(path);
			}
		}
		if (!unresolved.isEmpty()) {
			throw new GenericEvitaInternalError(
				"Searchable fields " + unresolved + " name no field of the source schema. A global attribute is " +
					"named `*.<attribute>`, an entity attribute `<entityType>.<attribute>` (only when it is not " +
					"global), a reference attribute `<entityType>.<reference>.<attribute>` and associated data " +
					"`<entityType>.@<associatedData>`.",
				"Searchable fields name no field of the source schema."
			);
		}
	}

	/**
	 * Parses {@link #LOCALIZED_ASSOCIATED_DATA} from the `evita.warmup.localize` system property. Entries are
	 * separated by `;`, each `<entityType>.@<associatedData>=<language tag>` - for example `article.@body=cs`.
	 *
	 * @return the locale of each associated data path, empty when the property is unset
	 */
	@Nonnull
	private static Map<String, Locale> parseLocalizedAssociatedData() {
		final String raw = System.getProperty("evita.warmup.localize", "");
		if (raw.isBlank()) {
			return Map.of();
		}
		final String[] entries = raw.split(";");
		final Map<String, Locale> result = CollectionUtils.createLinkedHashMap(entries.length);
		for (final String entry : entries) {
			final String trimmed = entry.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			final int localeStart = trimmed.indexOf(TEXT_EXPRESSION_SEPARATOR);
			final String path = localeStart < 0 ? trimmed : trimmed.substring(0, localeStart).trim();
			final String languageTag = localeStart < 0 ? "" : trimmed.substring(localeStart + 1).trim();
			if (languageTag.isEmpty() || !path.contains(PATH_SEPARATOR + ASSOCIATED_DATA_MARKER)) {
				throw new GenericEvitaInternalError(
					"Localized associated data `" + trimmed + "` must read `<entityType>.@<associatedData>=<locale>`.",
					"Localized associated data must name an associated data path and a locale."
				);
			}
			result.put(path, Locale.forLanguageTag(languageTag));
		}
		return result;
	}

	/**
	 * Fails the copy before anything is written when a path in {@link #LOCALIZED_ASSOCIATED_DATA} names no associated
	 * data of the source schema, or names one that is localized already - neither would be moved, and the run would
	 * copy something other than it claims.
	 *
	 * @param sourceEntitySchemas the source entity schemas
	 */
	private static void verifyLocalizedAssociatedDataExist(
		@Nonnull final Collection<EntitySchemaContract> sourceEntitySchemas
	) {
		if (LOCALIZED_ASSOCIATED_DATA.isEmpty()) {
			return;
		}
		final List<String> unresolved = new ArrayList<>(LOCALIZED_ASSOCIATED_DATA.size());
		for (final String path : LOCALIZED_ASSOCIATED_DATA.keySet()) {
			final int separator = path.indexOf(PATH_SEPARATOR + ASSOCIATED_DATA_MARKER);
			final String entityType = path.substring(0, separator);
			final String name = path.substring(separator + PATH_SEPARATOR.length() + ASSOCIATED_DATA_MARKER.length());
			boolean found = false;
			for (final EntitySchemaContract schema : sourceEntitySchemas) {
				if (schema.getName().equals(entityType)) {
					found = schema.getAssociatedData(name)
						.filter(associatedData -> !associatedData.isLocalized())
						.isPresent();
					break;
				}
			}
			if (!found) {
				unresolved.add(path);
			}
		}
		if (!unresolved.isEmpty()) {
			throw new GenericEvitaInternalError(
				"Localized associated data " + unresolved + " name no associated data of the source schema that " +
					"is not localized already.",
				"Localized associated data name no non-localized associated data of the source schema."
			);
		}
	}

	/**
	 * Lists the locales {@link #LOCALIZED_ASSOCIATED_DATA} moves associated data of an entity type under. The target
	 * schema must support them, or it would refuse the moved values.
	 *
	 * @param entityType name of the entity type
	 * @return the locales, empty when no associated data of the type is localized by the copy
	 */
	@Nonnull
	private static Set<Locale> getAddedLocales(@Nonnull String entityType) {
		if (LOCALIZED_ASSOCIATED_DATA.isEmpty()) {
			return Set.of();
		}
		final String pathPrefix = entityType + PATH_SEPARATOR + ASSOCIATED_DATA_MARKER;
		final Set<Locale> locales = CollectionUtils.createLinkedHashSet(4);
		for (final Map.Entry<String, Locale> entry : LOCALIZED_ASSOCIATED_DATA.entrySet()) {
			if (entry.getKey().startsWith(pathPrefix)) {
				locales.add(entry.getValue());
			}
		}
		return locales;
	}

	/**
	 * Tells whether a searchable-field path names a field of the source schema - see
	 * {@link #parseSearchableFields()} for the notation.
	 *
	 * @param path                the searchable-field path
	 * @param sourceCatalogSchema the source catalog schema (for global attributes)
	 * @param entitySchemas       the source entity schemas keyed by entity type
	 * @return true when the path names an existing field of the kind its shape implies
	 */
	private static boolean searchableFieldExists(
		@Nonnull final String path,
		@Nonnull final CatalogSchemaContract sourceCatalogSchema,
		@Nonnull final Map<String, EntitySchemaContract> entitySchemas
	) {
		if (path.startsWith(GLOBAL_ATTRIBUTE_PREFIX)) {
			return sourceCatalogSchema.getAttribute(path.substring(GLOBAL_ATTRIBUTE_PREFIX.length())).isPresent();
		}
		// keep trailing empty segments - `Product.name.` must not pass for `Product.name`, under which it is never
		// looked up when the flag is applied
		final String[] segments = path.split("\\" + PATH_SEPARATOR, -1);
		for (final String segment : segments) {
			if (segment.isEmpty()) {
				return false;
			}
		}
		final EntitySchemaContract entitySchema = entitySchemas.get(segments[0]);
		if (entitySchema == null) {
			return false;
		}
		if (segments.length == 2 && segments[1].startsWith(ASSOCIATED_DATA_MARKER)) {
			return entitySchema.getAssociatedData(segments[1].substring(ASSOCIATED_DATA_MARKER.length())).isPresent();
		} else if (segments.length == 2) {
			// a global attribute is flagged on the catalog schema, so an entity-level path would never be applied
			return entitySchema.getAttribute(segments[1])
				.filter(attribute -> !(attribute instanceof GlobalAttributeSchemaContract))
				.isPresent();
		} else if (segments.length == 3) {
			return entitySchema.getReference(segments[1])
				.filter(reference -> !(reference instanceof ReflectedReferenceSchemaContract))
				.flatMap(reference -> reference.getAttribute(segments[2]))
				.isPresent();
		} else {
			return false;
		}
	}

	/**
	 * Makes the attribute being replicated searchable when {@link #SEARCHABLE_FIELDS} names its path.
	 *
	 * @param path   the path of the attribute in the notation of {@link #parseSearchableFields()}
	 * @param editor the target attribute editor
	 */
	private static void applySearchable(@Nonnull final String path, @Nonnull final AttributeSchemaEditor<?> editor) {
		final SearchableField searchable = SEARCHABLE_FIELDS.get(path);
		if (searchable != null) {
			editor.searchable();
			printSearchable(searchable);
		}
	}

	/**
	 * Prints that a field was made searchable on the target, so a measurement run proves the flag actually applied
	 * rather than assuming it did - the same purpose as the `[raise]` line.
	 *
	 * @param searchable the field that was made searchable
	 */
	private static void printSearchable(@Nonnull final SearchableField searchable) {
		System.out.println(
			"[searchable] " + searchable.path() +
				(searchable.textExpression() == null ? "" : " with text expression " + searchable.textExpression())
		);
	}

	/**
	 * Copies the per-scope index type of the source reference onto the target editor.
	 *
	 * @param reference the source reference schema
	 * @param editor    the target reference editor
	 * @param raised    true to promote `FOR_FILTERING` to `FOR_FILTERING_AND_PARTITIONING`
	 * @return the scopes the target indexes `FOR_FILTERING_AND_PARTITIONING`
	 */
	@Nonnull
	private static List<Scope> applyIndexType(
		@Nonnull final ReferenceSchemaContract reference,
		@Nonnull final ReferenceSchemaEditor<?> editor,
		final boolean raised
	) {
		final List<Scope> forFiltering = new ArrayList<>(2);
		final List<Scope> forFilteringAndPartitioning = new ArrayList<>(2);
		for (final Scope scope : Scope.values()) {
			switch (reference.getReferenceIndexType(scope)) {
				case FOR_FILTERING -> (raised ? forFilteringAndPartitioning : forFiltering).add(scope);
				case FOR_FILTERING_AND_PARTITIONING -> forFilteringAndPartitioning.add(scope);
				case NONE -> {
					// not indexed in this scope - nothing to do
				}
			}
		}
		if (!forFiltering.isEmpty()) {
			editor.indexedForFilteringInScope(forFiltering.toArray(Scope.NO_SCOPE));
		}
		if (!forFilteringAndPartitioning.isEmpty()) {
			editor.indexedForFilteringAndPartitioningInScope(forFilteringAndPartitioning.toArray(Scope.NO_SCOPE));
		}
		return forFilteringAndPartitioning;
	}

	/**
	 * Copies the per-scope indexed components of the source reference onto the target editor. Without them the
	 * target indexes the referenced entity only and builds none of the group entity indexes the source has.
	 *
	 * @param reference the source reference schema
	 * @param editor    the target reference editor
	 */
	private static void applyIndexedComponents(
		@Nonnull final ReferenceSchemaContract reference,
		@Nonnull final ReferenceSchemaEditor<?> editor
	) {
		for (final Scope scope : Scope.values()) {
			final Set<ReferenceIndexedComponents> components = reference.getIndexedComponents(scope);
			// declaring components on a scope the source does not index would promote it to FOR_FILTERING
			if (reference.getReferenceIndexType(scope) != ReferenceIndexType.NONE && !components.isEmpty()) {
				editor.indexedWithComponentsInScope(scope, components.toArray(ReferenceIndexedComponents.EMPTY));
			}
		}
	}

	/**
	 * Copies the per-scope `facetedPartially` expression of the source reference onto the target editor. Without it
	 * the target facets the reference in every reduced index its owner sits in, which on a production corpus with
	 * a partially faceted reference multiplies the facet id indexes and drives the heap of a full rebuild past any
	 * sandbox.
	 *
	 * @param reference the source reference schema
	 * @param editor    the target reference editor
	 */
	private static void applyFacetedPartially(
		@Nonnull final ReferenceSchemaContract reference,
		@Nonnull final ReferenceSchemaEditor<?> editor
	) {
		for (final Scope scope : Scope.values()) {
			final Expression expression = reference.getFacetedPartiallyInScope(scope);
			if (expression != null) {
				editor.facetedPartiallyInScope(scope, expression);
			}
		}
	}

	/**
	 * Applies the group type, per-scope index type, indexed components, faceting (including partial faceting),
	 * attributes and sortable attribute compounds of a plain reference onto its target reference builder.
	 *
	 * @param entityType name of the entity type owning the reference, for the searchable-field lookup
	 * @param reference  the source reference schema
	 * @param editor     the target reference builder
	 */
	private static void applyReference(
		@Nonnull final String entityType,
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

		final boolean raised = isRaisedToPartitioning(reference.getName());
		final List<Scope> forFilteringAndPartitioning = applyIndexType(reference, editor, raised);
		if (raised) {
			// printed so a measurement run proves the promotion actually applied, rather than assuming it did:
			// a reflected reference never reaches this method, and would be silently left at its source type
			System.out.println(
				"[raise] " + reference.getName() + " -> FOR_FILTERING_AND_PARTITIONING in " + forFilteringAndPartitioning
			);
		}
		applyIndexedComponents(reference, editor);

		final Scope[] facetedScopes = scopesWhere(reference::isFacetedInScope);
		if (facetedScopes.length > 0) {
			editor.facetedInScope(facetedScopes);
		}
		applyFacetedPartially(reference, editor);

		for (final AttributeSchemaContract attribute : reference.getAttributes().values()) {
			editor.withAttribute(
				attribute.getName(),
				attribute.getType(),
				attributeEditor -> {
					applyAttribute(attribute, attributeEditor);
					applySearchable(
						entityType + PATH_SEPARATOR + reference.getName() + PATH_SEPARATOR + attribute.getName(),
						attributeEditor
					);
				}
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
			editor.uniqueInScope(unique.toArray(Scope.NO_SCOPE));
		}
		if (!uniqueWithinLocale.isEmpty()) {
			editor.uniqueWithinLocaleInScope(uniqueWithinLocale.toArray(Scope.NO_SCOPE));
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
		return matching.toArray(Scope.NO_SCOPE);
	}

	/**
	 * A field the target schema makes searchable although the source schema does not.
	 *
	 * @param path           the field's path in the notation of {@link #parseSearchableFields()}
	 * @param textExpression the EvitaEL expression selecting the text of complex associated data, or null
	 */
	private record SearchableField(
		@Nonnull String path,
		@Nullable String textExpression
	) {
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
