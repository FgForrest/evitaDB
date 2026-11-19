/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.api.EvitaContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.data.Versioned;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps;
import io.evitadb.api.requestResponse.schema.analysis.FulltextResourceContent;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Interface follows the <a href="https://en.wikipedia.org/wiki/Builder_pattern">builder pattern</a> allowing to alter
 * the data that are available on the read-only {@link CatalogSchemaContract} interface.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface CatalogSchemaEditor<S extends CatalogSchemaEditor<S>> extends
	CatalogSchemaContract,
	NamedSchemaEditor<S>,
	AttributeProviderSchemaEditor<S, GlobalAttributeSchemaContract, GlobalAttributeSchemaEditor.GlobalAttributeSchemaBuilder>
{

	/**
	 * Sets strict verification mode for this catalog. No entity of the unknown entity type will be allowed to be
	 * upserted in the catalog. The entity collections will be required to be set up upfront by the schema API.
	 *
	 * This mode is recommended if you want to strictly control schema and define structure up-front.
	 */
	@Nonnull
	S verifyCatalogSchemaStrictly();

	/**
	 * This is lax mode of the schema evolution. New entity collection / schema is created when entity of new entity
	 * type is upserted for the first time. This mode is good for prototyping but if developer makes a typo in
	 * the entity type it may pollute your database with unwanted entity collections.
	 */
	@Nonnull
	S verifyCatalogSchemaButCreateOnTheFly();

	/**
	 * Method creates new {@link EntitySchemaContract} within the catalog schema if the schema (entity collection) of
	 * such name hasn't yet exist. The method doesn't allow to specify details of the schema - if you need to do so,
	 * use the {@link #withEntitySchema(String, Consumer)} method instead.
	 *
	 * @param entityType the type of the entity (see {@link EntitySchemaContract#getName()})
	 * @see #withEntitySchema(String, Consumer)
	 */
	@Nonnull
	default CatalogSchemaBuilder withEntitySchema(@Nonnull String entityType) {
		return withEntitySchema(entityType, null);
	}

	/**
	 * Method creates new or updates existing {@link EntitySchemaContract} within the catalog schema and allows
	 * specifying the internals of the entity schema via builder instance.
	 *
	 * @param entityType the type of the entity (see {@link EntitySchemaContract#getName()})
	 * @param whichIs lambda that can specify the entity schema internals via passed builder
	 */
	@Nonnull
	CatalogSchemaBuilder withEntitySchema(@Nonnull String entityType, @Nullable Consumer<EntitySchemaBuilder> whichIs);

	/**
	 * Declares an explicit transaction conflict resolution for this whole catalog, overriding the engine-level
	 * default inherited from {@link io.evitadb.api.configuration.TransactionOptions}.
	 *
	 * The passed {@link ConflictResolution} replaces the inherited setting in its entirety — both the coarse
	 * conflict policy and its optional sub-entity granularity — for every transaction committed against this
	 * catalog. Use {@link #withoutConflictResolution()} to drop the override and fall back to the engine default.
	 *
	 * @param conflictResolution the catalog-level conflict resolution to apply, never null
	 * @return this builder to allow method chaining
	 */
	@Nonnull
	S withConflictResolution(@Nonnull ConflictResolution conflictResolution);

	/**
	 * Removes any catalog-level transaction conflict resolution override previously declared via
	 * {@link #withConflictResolution(ConflictResolution)}, so this catalog again inherits the engine-level default
	 * from {@link io.evitadb.api.configuration.TransactionOptions}.
	 *
	 * @return this builder to allow method chaining
	 */
	@Nonnull
	S withoutConflictResolution();

	/**
	 * Declares how text in one language is split into words for fulltext, replacing any previous declaration of the
	 * language. Splitting is shared by every searchable field in the language. A language never declared is split by
	 * {@link AnalysisSteps#standardTokenizer()} alone; declaring one is how
	 * {@link FulltextLanguageSchemaEditor#wordNumberSplit()} is turned on:
	 *
	 * ```
	 * catalog.withFulltextLanguage("cs", l -> l.wordNumberSplit());
	 * ```
	 *
	 * A change of a language's splitting changes the terms of every field indexed in it: such fields stop answering
	 * queries until they are reindexed.
	 *
	 * @param language the ISO 639 language code, as {@link java.util.Locale#getLanguage()} returns it
	 * @param whichIs  defines the splitting; it starts from the standard tokenizer
	 * @return this builder to allow method chaining
	 */
	@Nonnull
	S withFulltextLanguage(@Nonnull String language, @Nonnull Consumer<FulltextLanguageSchemaEditor> whichIs);

	/**
	 * Removes the declaration of a language, which then falls back to {@link AnalysisSteps#standardTokenizer()}.
	 * Nothing happens when the language is not declared.
	 *
	 * @param language the ISO 639 language code
	 * @return this builder to allow method chaining
	 */
	@Nonnull
	S withoutFulltextLanguage(@Nonnull String language);

	/**
	 * Declares a named fulltext resource - a list that analysis steps read by name - replacing any previous content of
	 * the name:
	 *
	 * ```
	 * catalog
	 *     .withFulltextResource("brands", wordList("puma", "nike"))
	 *     .withFulltextResource("phones", synonymList(synonymGroup("mobil", "telefon")));
	 * ```
	 *
	 * A resource read only on the query side (synonyms) takes effect on the next query; a resource read on the index
	 * side (stop words, protected words) changes the terms of the fields whose analyzers read it, which then stop
	 * answering queries until they are reindexed.
	 *
	 * @param name    the name, not one of {@link AnalysisSteps#BUILT_IN_RESOURCES}
	 * @param content the content, created by {@link AnalysisSteps#wordList(String...)} or
	 *                {@link AnalysisSteps#synonymList(io.evitadb.api.requestResponse.schema.analysis.SynonymGroup...)}
	 * @return this builder to allow method chaining
	 */
	@Nonnull
	S withFulltextResource(@Nonnull String name, @Nonnull FulltextResourceContent content);

	/**
	 * Removes a fulltext resource. The schema update is refused while an analyzer still reads the resource - checked on
	 * the schema the whole update produces, so an update that re-points those analyzers may remove the resource too.
	 * Nothing happens when the resource is not declared.
	 *
	 * @param name the name of the resource
	 * @return this builder to allow method chaining
	 */
	@Nonnull
	S withoutFulltextResource(@Nonnull String name);

	/**
	 * Declares a named fulltext analyzer, replacing any previous definition of the name - see
	 * {@link FulltextAnalyzerSchemaContract} for how its phases resolve and {@link FulltextAnalyzerSchemaEditor} for
	 * examples. Searchable fields name the analyzer they use.
	 *
	 * A change to the index side of an analyzer changes the terms of the fields that use it, which then stop answering
	 * queries until they are reindexed; a change to the query side only takes effect on the next query.
	 *
	 * @param name    the name, not one of {@link FulltextAnalyzerSchemaContract#BUILT_IN_ANALYZERS}
	 * @param whichIs defines the analyzer; it starts empty
	 * @return this builder to allow method chaining
	 */
	@Nonnull
	S withFulltextAnalyzer(@Nonnull String name, @Nonnull Consumer<FulltextAnalyzerSchemaEditor> whichIs);

	/**
	 * Removes a fulltext analyzer. The schema update is refused while a searchable field or another analyzer still
	 * names it - checked on the schema the whole update produces, so an update that re-points those fields and
	 * analyzers may remove the analyzer too. Nothing happens when the analyzer is not declared.
	 *
	 * @param name the name of the analyzer
	 * @return this builder to allow method chaining
	 */
	@Nonnull
	S withoutFulltextAnalyzer(@Nonnull String name);

	/**
	 * Interface that simply combines {@link CatalogSchemaEditor} and {@link CatalogSchemaContract} entity contracts
	 * together. Builder produces either {@link ModifyCatalogSchemaMutation} that describes all changes to be made on
	 * the {@link CatalogSchemaContract} instance to get it to "up-to-date" state or can provide already built
	 * {@link CatalogSchemaContract} that may not represent globally "up-to-date" state because it is based on
	 * the version of the entity known when builder was created.
	 *
	 * Mutation allows Evita to perform surgical updates on the latest version of the {@link CatalogSchemaContract}
	 * object that is in the database at the time update request arrives.
	 */
	@NotThreadSafe
	interface CatalogSchemaBuilder
		extends CatalogSchemaEditor<CatalogSchemaEditor.CatalogSchemaBuilder> {

		/**
		 * Returns {@link ModifyCatalogSchemaMutation} that contains array of {@link LocalCatalogSchemaMutation}
		 * instances describing what changes occurred in the builder and which should be applied on the existing parent
		 * schema in particular version.
		 *
		 * Each mutation increases {@link Versioned#version()} of the modified object and allows to detect race
		 * conditions based on "optimistic locking" mechanism in very granular way.
		 */
		@Nonnull
		Optional<ModifyCatalogSchemaMutation> toMutation();

		/**
		 * Returns built "local up-to-date" {@link CatalogSchemaContract} instance that may not represent globally
		 * "up-to-date" state because it is based on the version of the entity known when builder was created.
		 *
		 * This method is particularly useful for tests.
		 */
		@Nonnull
		CatalogSchemaContract toInstance();

		/**
		 * The method is a shortcut for creating a new read-write session and calling
		 * {@link EvitaSessionContract#updateCatalogSchema(LocalCatalogSchemaMutation...)} on it.
		 * Method simplifies the statements, makes them more readable and in combination with builder pattern usage
		 * when the session is not available, and it's also easier to use.
		 *
		 * @param evita to use for updating the modified (built) schema
		 */
		default void updateViaNewSession(@Nonnull EvitaContract evita) {
			try (final EvitaSessionContract session = evita.createReadWriteSession(getName())) {
				session.updateCatalogSchema(this);
			}
		}

		/**
		 * The method is a shortcut for creating a new read-write session and calling
		 * {@link EvitaSessionContract#updateAndFetchCatalogSchema(LocalCatalogSchemaMutation...)} on it.
		 * Method simplifies the statements, makes them more readable and in combination with builder pattern usage
		 * when the session is not available, and it's also easier to use.
		 *
		 * @param evita to use for updating the modified (built) schema
		 */
		@Nonnull
		default SealedCatalogSchema updateAndFetchViaNewSession(@Nonnull EvitaContract evita) {
			try (final EvitaSessionContract session = evita.createReadWriteSession(getName())) {
				return session.updateAndFetchCatalogSchema(this);
			}
		}

		/**
		 * The method is a shortcut for calling {@link EvitaSessionContract#updateCatalogSchema(LocalCatalogSchemaMutation...)}
		 * the other way around. Method simplifies the statements, makes them more readable and in combination with
		 * builder pattern usage it's also easier to use.
		 *
		 * @param session to use for updating the modified (built) schema
		 */
		default void updateVia(@Nonnull EvitaSessionContract session) {
			session.updateCatalogSchema(this);
		}

		/**
		 * The method is a shortcut for calling {@link EvitaSessionContract#updateAndFetchCatalogSchema(LocalCatalogSchemaMutation...)}
		 * the other way around. Method simplifies the statements, makes them more readable and in combination with
		 * builder pattern usage it's also easier to use.
		 *
		 * @param session to use for updating the modified (built) schema
		 */
		@Nonnull
		default SealedCatalogSchema updateAndFetchVia(@Nonnull EvitaSessionContract session) {
			return session.updateAndFetchCatalogSchema(this);
		}

	}

}
