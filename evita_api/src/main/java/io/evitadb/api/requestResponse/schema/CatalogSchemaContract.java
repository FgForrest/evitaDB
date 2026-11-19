/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.exception.SchemaAlteringException;
import io.evitadb.api.requestResponse.data.ContentComparator;
import io.evitadb.api.requestResponse.data.Versioned;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Catalog schema defines the basic properties of the {@link CatalogContract} that is a main container
 * of evitaDB data comparable to the single schema/database in a relational database system. Single catalog usually
 * contains data of single customer and represents a distinguishing unit in multi-tenant system.
 *
 * Catalog is uniquely identified by its {@link #getName()} among all other catalogs in the same evitaDB engine.
 * The instance of the catalog is represented by the {@link CatalogContract}.
 *
 * @see  CatalogContract
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface CatalogSchemaContract
	extends
	Versioned,
	NamedSchemaContract,
	ContentComparator<CatalogSchemaContract>,
	EntitySchemaProvider,
	AttributeSchemaProvider<GlobalAttributeSchemaContract> {

	/**
	 * Returns set of allowed evolution modes. These allow to specify how strict is evitaDB when unknown information is
	 * presented to it for the first time. When no evolution mode is set, each violation of the {@link EntitySchemaContract} is
	 * reported by an exception. This behaviour can be changed by this evolution mode, however.
	 */
	@Nonnull
	Set<CatalogEvolutionMode> getCatalogEvolutionMode();

	/**
	 * Returns the transaction conflict resolution declared for this whole catalog, or an empty result when the catalog
	 * inherits the engine-level default.
	 *
	 * A `null` / empty value means "inherit from the engine configuration" — i.e. the default declared on
	 * {@link io.evitadb.api.configuration.TransactionOptions}. When a value is present it overrides that default for
	 * this entire catalog, both the coarse conflict policy and the optional sub-entity granularity carried by
	 * {@link ConflictResolution}.
	 *
	 * The override is a whole-record replacement — there is no per-field merging with the inherited default; the most
	 * specific non-empty {@link ConflictResolution} wins in its entirety.
	 *
	 * @return the catalog-level conflict resolution override, or an empty result to inherit the engine default
	 */
	@Nonnull
	Optional<ConflictResolution> getConflictResolution();

	/**
	 * Returns the word splitting declared per language for fulltext. A language missing here is split by
	 * {@link io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps#standardTokenizer()} alone.
	 *
	 * @return the declared languages keyed by their ISO 639 code
	 */
	@Nonnull
	Map<String, FulltextLanguageSchemaContract> getFulltextLanguages();

	/**
	 * Returns the word splitting declared for one language.
	 *
	 * @param language the ISO 639 language code, as {@link java.util.Locale#getLanguage()} returns it
	 * @return the declaration, or empty when the catalog declares none for the language
	 */
	@Nonnull
	default Optional<FulltextLanguageSchemaContract> getFulltextLanguage(@Nonnull String language) {
		return Optional.ofNullable(getFulltextLanguages().get(language));
	}

	/**
	 * Returns the fulltext resources declared in the catalog - the lists analysis steps read by name. The built-in
	 * stop lists are not part of the result.
	 *
	 * @return the declared resources keyed by name
	 */
	@Nonnull
	Map<String, FulltextResourceSchemaContract> getFulltextResources();

	/**
	 * Returns one declared fulltext resource.
	 *
	 * @param name the name of the resource
	 * @return the resource, or empty when the catalog declares none of the name
	 */
	@Nonnull
	default Optional<FulltextResourceSchemaContract> getFulltextResource(@Nonnull String name) {
		return Optional.ofNullable(getFulltextResources().get(name));
	}

	/**
	 * Returns the fulltext analyzers declared in the catalog. The built-in analyzers
	 * ({@link FulltextAnalyzerSchemaContract#BUILT_IN_ANALYZERS}) are not part of the result.
	 *
	 * @return the declared analyzers keyed by name
	 */
	@Nonnull
	Map<String, FulltextAnalyzerSchemaContract> getFulltextAnalyzers();

	/**
	 * Returns one declared fulltext analyzer.
	 *
	 * @param name the name of the analyzer
	 * @return the analyzer, or empty when the catalog declares none of the name (also for a built-in name)
	 */
	@Nonnull
	default Optional<FulltextAnalyzerSchemaContract> getFulltextAnalyzer(@Nonnull String name) {
		return Optional.ofNullable(getFulltextAnalyzers().get(name));
	}

	/**
	 * Returns a collection of all entity schemas in catalog.
	 *
	 * @return a collection of entity schemas in catalog
	 */
	@Nonnull
	Collection<EntitySchemaContract> getEntitySchemas();

	/**
	 * Returns entity schema that is connected with passed `entityType` or NULL if such entity collection doesn't
	 * exist.
	 */
	@Nonnull
	Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType);

	/**
	 * Returns entity schema that is connected with passed `entityType` or throws an exception if such entity collection
	 * doesn't exist.
	 */
	@Nonnull
	default EntitySchemaContract getEntitySchemaOrThrowException(@Nonnull String entityType) {
		return getEntitySchema(entityType)
			.orElseThrow(() -> new EvitaInvalidUsageException("Schema for entity with name `" + entityType + "` was not found!"));
	}

	/**
	 * Validates the current state of the object. If the object is not valid, {@link SchemaAlteringException} is thrown.
	 * Method validates all entity schemas using {@link EntitySchemaContract#validate(CatalogSchemaContract)}.
	 *
	 * @throws SchemaAlteringException if current schema contains validation errors
	 */
	void validate() throws SchemaAlteringException;

}
