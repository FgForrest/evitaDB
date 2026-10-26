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


package io.evitadb.index.analysis;

import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.spi.analysis.AnalysisStepFactory;
import io.evitadb.spi.analysis.AnalysisStepParameterDescriptor;
import io.evitadb.spi.analysis.NormalizingAnalysisStepFactory;
import io.evitadb.spi.analysis.StringNormalizationStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises installed provider discovery, capability checks and diagnostic parameter validation. */
@DisplayName("Analysis step registry")
@Tag(ENGINE)
@Tag(SCHEMA)
class AnalysisStepRegistryTest {
	/** The production discovery path must find the classpath/JPMS providers for all four built-ins. */
	@Test
	@DisplayName("should discover all built-in providers")
	void shouldDiscoverBuiltIns() {
		final AnalysisStepRegistry registry = new AnalysisStepRegistry();
		for (final AnalysisStep step : List.of(lowercase(), asciiFolding(), trim(), removeCharacters("-"))) {
			assertEquals(step.name(), registry.resolve(step).getName());
		}
		final AnalysisStepParameterDescriptor descriptor = registry.resolve(removeCharacters("-")).getParameters().get(0);
		assertEquals("characters", descriptor.name());
		assertEquals(String.class, descriptor.type());
		assertTrue(descriptor.required());
		assertFalse(descriptor.description().isBlank());
	}

	/** Provider conflicts, missing capabilities and invalid descriptions fail before compilation. */
	@Nested
	@DisplayName("Provider resolution and validation")
	class ProviderResolution {
		/** Reversing provider discovery order must produce the same refusal, never an arbitrary winner. */
		@Test
		@DisplayName("should reject duplicate names deterministically")
		void shouldRejectDuplicateNames() {
			final AnalysisStepFactory first = new LowercaseAnalysisStepFactory();
			final AnalysisStepFactory second = factory("lowercase", UnaryOperator.identity());
			final String forward = assertThrows(EvitaInvalidUsageException.class,
				() -> new AnalysisStepRegistry(List.of(first, second))).getMessage();
			final String reverse = assertThrows(EvitaInvalidUsageException.class,
				() -> new AnalysisStepRegistry(List.of(second, first))).getMessage();
			assertEquals(forward, reverse);
			assertTrue(forward.contains("Duplicate analysis step `lowercase`"));
			assertTrue(forward.contains(first.getClass().getName()));
			assertTrue(forward.contains(second.getClass().getName()));
		}

		/** An unavailable name has a separate diagnostic from an installed provider with invalid parameters. */
		@Test
		@DisplayName("should identify an unknown step")
		void shouldRejectUnknownStep() {
			assertTrue(assertThrows(EvitaInvalidUsageException.class,
				() -> new AnalysisStepRegistry().resolve(step("absent"))).getMessage().contains("Unknown analysis step `absent`"));
		}

		/** Fulltext-only factories may be installed but cannot serve a normalization declaration. */
		@Test
		@DisplayName("should refuse a factory without normalizer capability")
		void shouldRefuseNonNormalizingFactory() {
			final AnalysisStepRegistry registry = new AnalysisStepRegistry(List.of(new AnalysisStepFactory() {
				/** {@inheritDoc} */
				@Nonnull
				@Override
				public String getName() { return "tokenize"; }
				/** {@inheritDoc} */
				@Nonnull
				@Override
				public List<AnalysisStepParameterDescriptor> getParameters() { return List.of(); }
				/** {@inheritDoc} */
				@Override
				public void validate(@Nonnull Map<String, Serializable> parameters) { }
			}));
			assertTrue(assertThrows(EvitaInvalidUsageException.class,
				() -> registry.resolve(step("tokenize"))).getMessage().contains("not usable as a normalizer"));
		}

		/** Required, typed, nonempty and unknown parameters must all fail with the offending step named. */
		@Test
		@DisplayName("should reject invalid parameter names types and values")
		void shouldRejectInvalidParameters() {
			final AnalysisStepRegistry registry = new AnalysisStepRegistry();
			for (final AnalysisStep description : List.of(
				step("remove-characters"), step("remove-characters", parameter("characters", 1)),
				step("remove-characters", parameter("characters", "")),
				step("remove-characters", parameter("characters", "-"), parameter("extra", true)),
				step("lowercase", parameter("locale", "tr")), step("trim", parameter("extra", true)),
				step("ascii-folding", parameter("extra", true))
			)) {
				final String message = assertThrows(EvitaInvalidUsageException.class,
					() -> registry.resolve(description)).getMessage();
				assertTrue(message.contains("Invalid parameters"));
				assertTrue(message.contains(description.name()));
			}
		}
	}

	/** Factory validation remains effective when a provider is invoked directly instead of through the registry. */
	@Test
	@DisplayName("should validate parameters before creating a built-in operation")
	void shouldValidateDirectFactoryCreation() {
		for (final NormalizingAnalysisStepFactory factory : List.of(
			new LowercaseAnalysisStepFactory(), new AsciiFoldingAnalysisStepFactory(),
			new TrimAnalysisStepFactory(), new RemoveCharactersAnalysisStepFactory()
		)) {
			assertThrows(EvitaInvalidUsageException.class, () -> factory.validate(Map.of("unexpected", "x")));
			assertThrows(EvitaInvalidUsageException.class, () -> factory.create(Map.of("unexpected", "x"), null));
		}
	}

	/** A supplementary removal must preserve another character sharing its high surrogate. */
	@Test
	@DisplayName("should remove code points rather than UTF-16 units")
	void shouldRemoveCodePoints() {
		final StringNormalizationStep operation = new RemoveCharactersAnalysisStepFactory().create(
			Map.of("characters", "😀😀"), null);
		assertEquals("😁", operation.apply("😀😁😀"));
		final String unchanged = new String("😁");
		assertSame(unchanged, operation.apply(unchanged));
	}

	/**
	 * Provides deterministic custom operations for registry tests without changing the installed provider set.
	 *
	 * @param name step name the factory is registered under
	 * @param operation normalization applied by every step the factory creates, regardless of parameters or fold locale
	 * @return factory accepting no parameters
	 */
	@Nonnull
	static NormalizingAnalysisStepFactory factory(@Nonnull String name, @Nonnull UnaryOperator<String> operation) {
		return new NormalizingAnalysisStepFactory() {
			/** {@inheritDoc} */
			@Nonnull
			@Override
			public String getName() { return name; }
			/** {@inheritDoc} */
			@Nonnull
			@Override
			public List<AnalysisStepParameterDescriptor> getParameters() { return List.of(); }
			/** {@inheritDoc} */
			@Override
			public void validate(@Nonnull Map<String, Serializable> parameters) {
				if (!parameters.isEmpty()) {
					throw new EvitaInvalidUsageException("No parameters accepted.");
				}
			}
			/** {@inheritDoc} */
			@Nonnull
			@Override
			public StringNormalizationStep create(
				@Nonnull Map<String, Serializable> parameters, @Nullable Locale foldLocale
			) {
				return operation::apply;
			}
		};
	}
}
