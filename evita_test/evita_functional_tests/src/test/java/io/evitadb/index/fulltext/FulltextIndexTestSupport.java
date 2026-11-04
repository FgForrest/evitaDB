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

package io.evitadb.index.fulltext;

import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.index.fulltext.analysis.CompiledFulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import io.evitadb.utils.NamingConvention;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;

/** Shared real schema analysis for low-level fulltext index fixtures. */
public final class FulltextIndexTestSupport {
	/** The schema shared by fixtures with no custom analysis definitions. */
	public static final CatalogSchemaContract SCHEMA = CatalogSchema._internalBuild(
		"test", NamingConvention.generate("test"), null, EnumSet.allOf(CatalogEvolutionMode.class),
		EmptyEntitySchemaAccessor.INSTANCE);
	/** Keeps the fixture chains reachable for the duration of the test JVM. */
	private static final FulltextAnalyzerRegistry REGISTRY = new FulltextAnalyzerRegistry(new AnalysisStepRegistry());

	/** Prevents construction of this fixture utility. */
	private FulltextIndexTestSupport() { }

	/** Returns the fixture's Czech text chains. */
	@Nonnull
	public static CompiledFulltextAnalyzer testAnalyzer() {
		return REGISTRY.getCompiledAnalyzer(SCHEMA, "text", Locale.forLanguageTag("cs"));
	}

	/** Registers a fixture field, preserving an explicitly chosen pivot on subsequent lookups. */
	public static int registerField(@Nonnull FulltextIndex index, @Nonnull FulltextFieldKey key) {
		final int id = index.getFieldId(key);
		return id == FulltextIndex.UNKNOWN_FIELD_ID ? registerField(index, key, 25.0) : id;
	}

	/** Registers a fixture field with an explicit pivot. */
	public static int registerField(@Nonnull FulltextIndex index, @Nonnull FulltextFieldKey key, double pivot) {
		return registerField(index, key, pivot, null);
	}

	/** Registers a fixture field with its extraction expression and real analyzer identity. */
	public static int registerField(
		@Nonnull FulltextIndex index, @Nonnull FulltextFieldKey key, double pivot, @Nullable String expression
	) {
		final CompiledFulltextAnalyzer analyzer = testAnalyzer();
		return index.getOrAssignFieldId(key, "text", analyzer.fingerprint().index(),
			analyzer.fingerprint().wordPhase(), pivot, expression);
	}

	/** Returns a registered fixture's chosen pivot, or the schema default for a new field. */
	public static double fieldPivot(@Nonnull FulltextIndex index, @Nonnull FulltextFieldKey key) {
		final int id = index.getFieldId(key);
		return id == FulltextIndex.UNKNOWN_FIELD_ID ? 25.0 : index.getLengthPivot(id);
	}

	/**
	 * Returns how many compiled analyzers a registry retains. Retention is the registry's private bookkeeping and it
	 * exposes no count, so the tests bounding it read the field by name - here, in one place, failing with the field
	 * to follow when it is renamed.
	 */
	public static int retainedAnalyzerCount(@Nonnull FulltextAnalyzerRegistry registry) {
		try {
			final Field compiled = FulltextAnalyzerRegistry.class.getDeclaredField("compiled");
			compiled.setAccessible(true);
			return ((Map<?, ?>) compiled.get(registry)).size();
		} catch (ReflectiveOperationException ex) {
			throw new AssertionError(
				"The retained analyzers are read from FulltextAnalyzerRegistry#compiled - follow its rename here.", ex
			);
		}
	}
}
