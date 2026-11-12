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

package io.evitadb.core.query.algebra.identity;

import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.identity.CloneOutcome;
import io.evitadb.test.identity.ClonePath;
import io.evitadb.test.identity.ConstructionMode;
import io.evitadb.test.identity.IdentityContractVerifier;
import io.evitadb.test.identity.IdentityFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.TEST_HARNESS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the reflective verifier with real, small holders and deliberately seeded defects.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Identity contract verifier self-tests")
@Tag(TEST_HARNESS)
@Tag(CACHE)
class IdentityContractVerifierTest implements EvitaTestSupport {
	/** Correct-holder variant whose operand list exceeds the cross-query admission limit. */
	private static final Map<String, Supplier<CorrectIdentityHolder>> OVERSIZED = Map.of(
		"oversized", () -> new CorrectIdentityHolder(1, TokenList.ofProducerIds(new long[TokenList.CAP + 1], false))
	);

	/** Verifies a complete value identity, field transplant and declared equal clone. */
	@Test
	@DisplayName("Accepts a correct value holder")
	void shouldAcceptCorrectHolder() {
		IdentityContractVerifier.verify(CorrectIdentityHolder.class, correctHolderFixture(OVERSIZED, Map.of(
			"copy", new ClonePath<>(
				holder -> new CorrectIdentityHolder(holder.value, holder.tokens), CloneOutcome.EQUAL
			)
		), Set.of()));
	}

	/** Verifies a clone declared equal cannot move the key. */
	@Test
	@DisplayName("Detects an equal-declared clone that moves the key through check 7")
	void shouldDetectEqualDeclaredCloneMovingKey() {
		final AssertionError error = assertThrows(AssertionError.class, () -> IdentityContractVerifier.verify(
			CorrectIdentityHolder.class, correctHolderFixture(OVERSIZED, Map.of("equal-declared", new ClonePath<>(
				holder -> new CorrectIdentityHolder(holder.value + 1, holder.tokens), CloneOutcome.EQUAL
			)), Set.of())));
		assertTrue(error.getMessage().contains("check 7: equal-declared"), error::getMessage);
	}

	/** Verifies a clone declared unequal cannot keep the key. */
	@Test
	@DisplayName("Detects an unequal-declared clone that keeps the key through check 7")
	void shouldDetectUnequalDeclaredCloneKeepingKey() {
		final AssertionError error = assertThrows(AssertionError.class, () -> IdentityContractVerifier.verify(
			CorrectIdentityHolder.class, correctHolderFixture(OVERSIZED, Map.of("unequal-declared", new ClonePath<>(
				holder -> new CorrectIdentityHolder(holder.value, holder.tokens), CloneOutcome.UNEQUAL
			)), Set.of())));
		assertTrue(error.getMessage().contains("check 7: unequal-declared"), error::getMessage);
	}

	/** Verifies a clone declared rejected must actually refuse to clone. */
	@Test
	@DisplayName("Detects a rejected-declared clone that succeeds through check 7")
	void shouldDetectRejectedDeclaredCloneSucceeding() {
		final AssertionError error = assertThrows(AssertionError.class, () -> IdentityContractVerifier.verify(
			CorrectIdentityHolder.class, correctHolderFixture(OVERSIZED, Map.of("rejected-declared", new ClonePath<>(
				holder -> new CorrectIdentityHolder(holder.value, holder.tokens), CloneOutcome.REJECTED
			)), Set.of())));
		assertTrue(error.getMessage().contains("check 7: rejected-declared"), error::getMessage);
	}

	/** Verifies an opaque clone returning its own holder cannot be declared unequal. */
	@Test
	@DisplayName("Detects an opaque self clone declared unequal through check 7")
	void shouldDetectOpaqueSelfCloneDeclaredUnequal() {
		final AssertionError error = assertThrows(AssertionError.class, () -> IdentityContractVerifier.verify(
			OpaqueSelfHolder.class, opaqueFixture(
				OpaqueSelfHolder::new, new ClonePath<>(holder -> holder, CloneOutcome.UNEQUAL))));
		assertTrue(error.getMessage().contains("check 7: clone"), error::getMessage);
	}

	/** Verifies every token component needs a variant that makes it query-local. */
	@Test
	@DisplayName("Detects a token component without an oversized variant through check 8")
	void shouldDetectMissingLocalityVariant() {
		final AssertionError error = assertThrows(AssertionError.class, () -> IdentityContractVerifier.verify(
			CorrectIdentityHolder.class, correctHolderFixture(Map.of(), Map.of(), Set.of())));
		assertTrue(error.getMessage().contains("check 8: no opaque/oversized variant for tokens"), error::getMessage);
	}

	/** Verifies a component declared always exact cannot become query-local. */
	@Test
	@DisplayName("Detects an always-exact component that becomes query-local through check 8")
	void shouldDetectLocalAlwaysExactComponent() {
		final AssertionError error = assertThrows(AssertionError.class, () -> IdentityContractVerifier.verify(
			CorrectIdentityHolder.class, correctHolderFixture(OVERSIZED, Map.of(), Set.of("tokens"))));
		assertTrue(error.getMessage().contains("check 8: alwaysExact component became query-local: tokens"),
			error::getMessage);
	}

	/** Verifies an identity built from a snapshot cannot hide the field it claims to represent. */
	@Test
	@DisplayName("Detects a field whose transplant does not move the key through check 4")
	void shouldDetectFieldNotFeedingIdentity() {
		final AssertionError error = assertThrows(AssertionError.class, () -> IdentityContractVerifier.verify(
			SnapshotIdentityHolder.class, valueFixture(() -> new SnapshotIdentityHolder(1),
				() -> new SnapshotIdentityHolder(2))));
		assertTrue(error.getMessage().contains("check 4: transplanted field did not move key"), error::getMessage);
	}

	/** Verifies a field declared derived must be equal whenever the keys are. */
	@Test
	@DisplayName("Detects a derived field that differs between equal keys through check 5")
	void shouldDetectUndeterminedDerivedField() {
		final AssertionError error = assertThrows(AssertionError.class, () -> IdentityContractVerifier.verify(
			StampedIdentityHolder.class, valueFixture(() -> new StampedIdentityHolder(1),
				() -> new StampedIdentityHolder(2))));
		assertTrue(error.getMessage().contains("check 5: equal keys have different DERIVED field"),
			error::getMessage);
	}

	/** Verifies the record's class identifier must match the holder's constant. */
	@Test
	@DisplayName("Detects a record class identifier differing from CLASS_ID through check 12")
	void shouldDetectMislabeledClassId() {
		final AssertionError error = assertThrows(AssertionError.class, () -> IdentityContractVerifier.verify(
			MislabeledIdentityHolder.class, valueFixture(() -> new MislabeledIdentityHolder(1),
				() -> new MislabeledIdentityHolder(2))));
		assertTrue(error.getMessage().contains("check 12: record classId differs from CLASS_ID"), error::getMessage);
	}

	/** Verifies components outside the closed value domain are refused. */
	@Test
	@DisplayName("Detects a component type outside the closed domain through check 9")
	void shouldDetectForbiddenComponentType() {
		final AssertionError error = assertThrows(AssertionError.class, () -> IdentityContractVerifier.verify(
			RatioIdentityHolder.class, valueFixture(() -> new RatioIdentityHolder(1),
				() -> new RatioIdentityHolder(2))));
		assertTrue(error.getMessage().contains("check 9: forbidden component type: double"), error::getMessage);
	}

	/** Verifies that incomplete variants cannot make an identity contract vacuously pass. */
	@Test
	@DisplayName("Detects missing component coverage through check 2")
	void shouldDetectUncoveredComponent() {
		final AssertionError error = assertThrows(AssertionError.class, () -> IdentityContractVerifier.verify(
			CorrectIdentityHolder.class, () -> Map.of("value", new ConstructionMode<>(
				() -> new CorrectIdentityHolder(1), Map.of(), Map.of(), Map.of(), Map.of(), Set.of(), Set.of()))));
		assertTrue(error.getMessage().contains("check 2"), error::getMessage);
	}

	/** Verifies fixture metadata cannot pin a component that a variant changes. */
	@Test
	@DisplayName("Detects a changed pinned component through check 2")
	void shouldDetectChangedPinnedComponent() {
		final AssertionError error = assertThrows(AssertionError.class, () -> IdentityContractVerifier.verify(
			CorrectIdentityHolder.class, () -> Map.of("value", new ConstructionMode<>(
				() -> new CorrectIdentityHolder(1), Map.of("value", () -> new CorrectIdentityHolder(2)),
				Map.of(), Map.of(), Map.of(), Set.of("value", "tokens"), Set.of("tokens")))));
		assertTrue(error.getMessage().contains("check 2"), error::getMessage);
	}

	/** Verifies that key inequality alone cannot hide an omitted hash component. */
	@Test
	@DisplayName("Detects a component omitted from describeTo through check 10")
	void shouldDetectOmittedComponent() {
		final AssertionError error = assertThrows(AssertionError.class, () -> IdentityContractVerifier.verify(
			OmittedIdentityHolder.class, valueFixture(() -> new OmittedIdentityHolder(1),
				() -> new OmittedIdentityHolder(2))));
		assertTrue(error.getMessage().contains("check 10"), error::getMessage);
	}

	/** Checks component coverage independently of coordinated fixture changes. */
	@Test
	@DisplayName("Rejects hashing only a when both a and b change in the variant")
	void shouldDetectComponentOmittedByCoordinatedVariant() {
		final AssertionError error = assertThrows(AssertionError.class, () -> IdentityContractVerifier.verify(
			PartiallyDescribedHolder.class, valueFixture(() -> new PartiallyDescribedHolder(1, 1),
				() -> new PartiallyDescribedHolder(2, 2))));
		assertTrue(error.getMessage().contains("check 10"), error::getMessage);
	}

	/** Checks replacement on a fixed-size list that rejects additions. */
	@Test
	@DisplayName("Rejects a fixed-size mutable identity list")
	void shouldDetectFixedSizeList() {
		final AssertionError error = assertThrows(AssertionError.class, () -> IdentityContractVerifier.verify(
			ListIdentityHolder.class, valueFixture(() -> new ListIdentityHolder("a", false),
				() -> new ListIdentityHolder("b", false))));
		assertTrue(error.getMessage().contains("check 9"), error::getMessage);
	}

	/** Checks an unmodifiable list whose retained backing array can still change. */
	@Test
	@DisplayName("Rejects a retained mutable backing array")
	void shouldDetectRetainedBackingArray() {
		final AssertionError error = assertThrows(AssertionError.class, () -> IdentityContractVerifier.verify(
			ListIdentityHolder.class, valueFixture(() -> new ListIdentityHolder("a", true),
				() -> new ListIdentityHolder("b", true))));
		assertTrue(error.getMessage().contains("check 9"), error::getMessage);
	}

	/** Lists the Java line terminators by name, keeping control characters out of the test display names. */
	@Nonnull
	static Stream<Arguments> lineTerminators() {
		return Stream.of(Arguments.of("CR", "\r"), Arguments.of("LF", "\n"), Arguments.of("CRLF", "\r\n"));
	}

	/** Checks every Java line terminator when stripping a leading line comment. */
	@ParameterizedTest(name = "{0}")
	@MethodSource("lineTerminators")
	@DisplayName("Discovers source after a line comment")
	void shouldRecognizeLineTerminators(
		@Nonnull String name, @Nonnull String terminator, @TempDir @Nonnull Path sourceRoot
	) throws IOException {
		Files.writeString(sourceRoot.resolve("Missing.java"), "// header" + terminator +
			"package missing;" + terminator + "class Missing {}" + terminator);

		final AssertionError error = assertThrows(AssertionError.class,
			() -> IdentityContractVerifier.discover(sourceRoot));
		assertTrue(error.getMessage().contains("Cannot load discovered source class missing.Missing"),
			error::getMessage);
	}

	/** Checks that literal and comment contents cannot derail the source parser. */
	@Test
	@DisplayName("Parses text blocks, character literals and comments containing quotes")
	void shouldParseQuotesInsideLiteralsAndComments(@TempDir @Nonnull Path sourceRoot) throws IOException {
		Files.writeString(sourceRoot.resolve("IdentityContractVerifierTest.java"), """
			package io.evitadb.core.query.algebra.identity;
			class IdentityContractVerifierTest {
				String block = \"""
					text with "quotes" and an apostrophe's mark
					\""";
				// a comment that isn't closed by its apostrophe
				char quote = '"';
				char apostrophe = '\\'';
				String url = "http://localhost/*";
				/* a comment with a " quote */
				void sealed() { }
			}
			""");

		assertTrue(IdentityContractVerifier.discover(sourceRoot).isEmpty());
	}

	/** Checks the inventory keeps concrete holders of the primary root only, including named nested classes. */
	@Test
	@DisplayName("Inventories concrete holders of the primary root only")
	void shouldInventoryConcreteHoldersOfPrimaryRoot(@TempDir @Nonnull Path temporary) throws IOException {
		final Path primary = Files.createDirectories(temporary.resolve("primary"));
		final Path related = Files.createDirectories(temporary.resolve("related"));
		Files.writeString(primary.resolve("ConstantFormula.java"),
			"package io.evitadb.core.query.algebra.base;\npublic class ConstantFormula {}\n");
		Files.writeString(primary.resolve("AbstractFormula.java"),
			"package io.evitadb.core.query.algebra;\npublic abstract class AbstractFormula {}\n");
		Files.writeString(primary.resolve("Formula.java"),
			"package io.evitadb.core.query.algebra;\npublic interface Formula {}\n");
		Files.writeString(primary.resolve("HierarchyFilteringPredicate.java"), """
			package io.evitadb.index.hierarchy.predicate;
			public interface HierarchyFilteringPredicate {
				class AndHierarchyFilteringPredicate {}
			}
			""");
		Files.writeString(related.resolve("AndFormula.java"),
			"package io.evitadb.core.query.algebra.base;\npublic class AndFormula {}\n");

		assertEquals(
			Set.of(ConstantFormula.class, HierarchyFilteringPredicate.AndHierarchyFilteringPredicate.class),
			IdentityContractVerifier.discover(primary, related)
		);
	}

	/** Verifies the field inventory cannot silently ignore a new holder input. */
	@Test
	@DisplayName("Detects an unannotated field outside the record through check 3")
	void shouldDetectUnannotatedField() {
		final AssertionError error = assertThrows(AssertionError.class, () -> IdentityContractVerifier.verify(
			UnannotatedIdentityHolder.class, valueFixture(() -> new UnannotatedIdentityHolder(1),
				() -> new UnannotatedIdentityHolder(2))));
		assertTrue(error.getMessage().contains("check 3"), error::getMessage);
	}

	/** Verifies that an allocating opaque clone cannot carry its original owner's key. */
	@Test
	@DisplayName("Detects a new opaque clone retaining its original key through check 8")
	void shouldDetectOpaqueCloneRetainingKey() {
		final IdentityFixture<OpaqueAllocatingHolder> fixture = opaqueFixture(
			OpaqueAllocatingHolder::new, new ClonePath<>(holder -> {
				final OpaqueAllocatingHolder clone = new OpaqueAllocatingHolder();
				clone.memoizedKey = holder.getKey();
				return clone;
			}, CloneOutcome.UNEQUAL));
		final AssertionError error = assertThrows(AssertionError.class,
			() -> IdentityContractVerifier.verify(OpaqueAllocatingHolder.class, fixture));
		assertTrue(error.getMessage().contains("check 8: newly allocated opaque clone compares equal"),
			error::getMessage);
	}

	/** Verifies the same allocating path passes when the clone binds to its new owner. */
	@Test
	@DisplayName("Accepts a new opaque clone with its own key")
	void shouldAcceptDistinctOpaqueClone() {
		IdentityContractVerifier.verify(OpaqueAllocatingHolder.class, opaqueFixture(
			OpaqueAllocatingHolder::new,
			new ClonePath<>(holder -> new OpaqueAllocatingHolder(), CloneOutcome.UNEQUAL)));
	}

	/** Verifies the explicit exception for clone paths that return their original opaque holder. */
	@Test
	@DisplayName("Accepts an opaque clone returning itself as equal")
	void shouldAcceptOpaqueSelfClone() {
		IdentityContractVerifier.verify(OpaqueSelfHolder.class, opaqueFixture(
			OpaqueSelfHolder::new, new ClonePath<>(holder -> holder, CloneOutcome.EQUAL)));
	}

	/** Verifies that rejected clone declarations are actually exercised. */
	@Test
	@DisplayName("Accepts a clone path that rejects cloning")
	void shouldAcceptRejectedClone() {
		IdentityContractVerifier.verify(OpaqueSelfHolder.class, opaqueFixture(
			OpaqueSelfHolder::new, new ClonePath<>(holder -> {
				throw new UnsupportedOperationException("Cloning is not supported");
			}, CloneOutcome.REJECTED)));
	}

	/** Verifies anonymous subclasses cannot evade discovery through an inherited holder interface. */
	@Test
	@DisplayName("Rejects anonymous holder subclasses in source")
	void shouldDetectAnonymousHolder(@TempDir @Nonnull Path sourceRoot) throws IOException {
		Files.writeString(sourceRoot.resolve("IdentityContractVerifierTest.java"), """
			package io.evitadb.core.query.algebra.identity;
			class IdentityContractVerifierTest {
				Object seed() {
					return new io.evitadb.core.query.algebra.base.ConstantFormula(
						new io.evitadb.index.bitmap.BaseBitmap(1)) {};
				}
			}
			""");
		final AssertionError error = assertThrows(AssertionError.class, () -> IdentityContractVerifier.discover(
			sourceRoot, getRootDirectory().resolve("evita_engine/src/main/java")));
		assertTrue(error.getMessage().contains("Anonymous identity holder"), error::getMessage);
	}

	/** Verifies local subclasses cannot disappear from the named source inventory. */
	@Test
	@DisplayName("Rejects local holder subclasses in source")
	void shouldDetectLocalHolder(@TempDir @Nonnull Path sourceRoot) throws IOException {
		Files.writeString(sourceRoot.resolve("IdentityContractVerifierTest.java"), """
			package io.evitadb.core.query.algebra.identity;
			class IdentityContractVerifierTest {
				Object seed() {
					class Local extends io.evitadb.core.query.algebra.base.ConstantFormula {
						Local() { super(new io.evitadb.index.bitmap.BaseBitmap(1)); }
					}
					return new Local();
				}
			}
			""");
		final AssertionError error = assertThrows(AssertionError.class, () -> IdentityContractVerifier.discover(
			sourceRoot, getRootDirectory().resolve("evita_engine/src/main/java")));
		assertTrue(error.getMessage().contains("Local identity holder"), error::getMessage);
	}

	/** Builds the correct holder's complete value mode with the given oversized variants, clones and exact parts. */
	@Nonnull
	private static IdentityFixture<CorrectIdentityHolder> correctHolderFixture(
		@Nonnull Map<String, Supplier<CorrectIdentityHolder>> oversizedVariants,
		@Nonnull Map<String, ClonePath<CorrectIdentityHolder>> clonePaths,
		@Nonnull Set<String> alwaysExact
	) {
		return () -> Map.of("value", new ConstructionMode<>(
			() -> new CorrectIdentityHolder(1), Map.of(
				"value", () -> new CorrectIdentityHolder(2),
				"tokens", () -> new CorrectIdentityHolder(1, TokenList.ofProducerIds(new long[]{2}, false))
			), Map.of(), oversizedVariants, clonePaths, Set.of(), alwaysExact
		));
	}

	/** Builds a fixture that varies the only value component. */
	@Nonnull
	private static <T> IdentityFixture<T> valueFixture(@Nonnull Supplier<T> base, @Nonnull Supplier<T> variant) {
		return () -> Map.of("value", new ConstructionMode<>(base, Map.of("value", variant), Map.of(), Map.of(),
			Map.of(), Set.of(), Set.of()));
	}

	/** Builds an always opaque construction mode with a declared clone path. */
	@Nonnull
	private static <T> IdentityFixture<T> opaqueFixture(@Nonnull Supplier<T> supplier, @Nonnull ClonePath<T> path) {
		return () -> Map.of("opaque", new ConstructionMode<>(null, Map.of(), Map.of("opaque", supplier), Map.of(),
			Map.of("clone", path), Set.of(), Set.of()));
	}

	/**
	 * Correct holder whose constructor input is fully represented and described.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class CorrectIdentityHolder {
		/** Stable holder identifier. */
		private static final long CLASS_ID = 4489106079837332468L;
		/** Input represented directly in the identity. */
		private final int value;
		/** Exact operands represented directly in the identity. */
		private final TokenList tokens;
		/** Value determined by the identity input. */
		@IdentityExempt(IdentityExempt.Reason.DERIVED)
		private final int doubled;
		/** Captures a scalar input with a small exact operand list. */
		private CorrectIdentityHolder(int value) {
			this(value, TokenList.ofProducerIds(new long[]{1}, false));
		}
		/** Captures independently variable scalar and operand inputs. */
		private CorrectIdentityHolder(int value, @Nonnull TokenList tokens) {
			this.value = value;
			this.tokens = tokens;
			this.doubled = 2 * value;
		}
		/** Returns this holder's declared identity. */
		@Nonnull private IdentityRecord identity() { return new Identity(this.value, this.tokens); }
		/** Returns this holder's identifier. */
		private long getClassId() { return CLASS_ID; }
		/** Builds the detached key on demand. */
		@Nonnull private FormulaKey getKey() {
			return FormulaKey.of(identity(), getClassId(), FormulaKey.NO_CHILDREN, this);
		}
		/**
		 * Complete input value.
		 *
		 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
		 */
		private record Identity(int value, @Nonnull TokenList tokens) implements IdentityRecord {
			/** Returns the owning holder's identifier. */
			@Override public long classId() { return CLASS_ID; }
			/** Includes the input component. */
			@Override public void describeTo(@Nonnull KeyAccumulator accumulator) {
				accumulator.add(this.value);
				accumulator.add(this.tokens);
			}
		}
	}

	/**
	 * Defective holder whose record equality sees an input that its hash description omits.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class OmittedIdentityHolder {
		/** Stable holder identifier. */
		private static final long CLASS_ID = 3283985400469069334L;
		/** Input deliberately omitted from hashing. */
		private final int value;
		/** Captures the input through a real constructor. */
		private OmittedIdentityHolder(int value) { this.value = value; }
		/** Returns the record with the omitted hash component. */
		@Nonnull private IdentityRecord identity() { return new Identity(this.value); }
		/** Returns this holder's identifier. */
		private long getClassId() { return CLASS_ID; }
		/** Builds the defective detached key. */
		@Nonnull private FormulaKey getKey() {
			return FormulaKey.of(identity(), getClassId(), FormulaKey.NO_CHILDREN, this);
		}
		/**
		 * Value equality includes the input, while hashing deliberately does not.
		 *
		 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
		 */
		private record Identity(int value) implements IdentityRecord {
			/** Returns the owning holder's identifier. */
			@Override public long classId() { return CLASS_ID; }
			/** Deliberately omits the input to test the verifier. */
			@Override public void describeTo(@Nonnull KeyAccumulator accumulator) { }
		}
	}

	/**
	 * Defective holder whose coordinated variants conceal one omitted hash component.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class PartiallyDescribedHolder {
		/** Stable synthetic holder identifier. */
		private static final long CLASS_ID = 6006184676372095883L;
		/** Described input. */
		private final int a;
		/** Input omitted from hashing. */
		private final int b;
		/** Captures both scalar inputs. */
		private PartiallyDescribedHolder(int a, int b) { this.a = a; this.b = b; }
		/** Returns both equality components. */
		@Nonnull private IdentityRecord identity() { return new Identity(this.a, this.b); }
		/** Returns the owning holder identifier. */
		private long getClassId() { return CLASS_ID; }
		/** Builds the incomplete key description. */
		@Nonnull private FormulaKey getKey() {
			return FormulaKey.of(identity(), getClassId(), FormulaKey.NO_CHILDREN, this);
		}
		/**
		 * Two equality components with only the first described.
		 *
		 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
		 */
		private record Identity(int a, int b) implements IdentityRecord {
			/** Returns the holder identifier. */
			@Override public long classId() { return CLASS_ID; }
			/** Deliberately omits the second component. */
			@Override public void describeTo(@Nonnull KeyAccumulator accumulator) { accumulator.add(this.a); }
		}
	}

	/**
	 * Defective holder retaining a list and its caller-owned backing storage.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class ListIdentityHolder {
		/** Stable synthetic holder identifier. */
		private static final long CLASS_ID = 1611806985532207384L;
		/** Retained caller input whose contents are represented by the list component. */
		@IdentityExempt(IdentityExempt.Reason.DECOMPOSED)
		private final String[] backing;
		/** Chooses the defective list view returned by this construction mode. */
		@IdentityExempt(IdentityExempt.Reason.STATE)
		private final boolean unmodifiable;
		/** Wraps the mutable array with an optionally unmodifiable view. */
		private ListIdentityHolder(@Nonnull String value, boolean unmodifiable) {
			this.backing = new String[]{value};
			this.unmodifiable = unmodifiable;
		}
		/** Returns the list view as identity. */
		@Nonnull private IdentityRecord identity() {
			final List<String> view = Arrays.asList(this.backing);
			return new Identity(this.unmodifiable ? Collections.unmodifiableList(view) : view);
		}
		/** Returns the holder identifier. */
		private long getClassId() { return CLASS_ID; }
		/** Builds the key before any mutation probe. */
		@Nonnull private FormulaKey getKey() {
			return FormulaKey.of(identity(), getClassId(), FormulaKey.NO_CHILDREN, this);
		}
		/**
		 * List component whose constructor deliberately omits defensive copying.
		 *
		 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
		 */
		private record Identity(@Nonnull List<String> values) implements IdentityRecord {
			/** Returns the holder identifier. */
			@Override public long classId() { return CLASS_ID; }
			/** Describes the list content. */
			@Override public void describeTo(@Nonnull KeyAccumulator accumulator) {
				accumulator.addStrings(this.values);
			}
		}
	}

	/**
	 * Defective holder with an undeclared extra input field.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class UnannotatedIdentityHolder {
		/** Stable holder identifier. */
		private static final long CLASS_ID = -7395210316615292896L;
		/** Input represented directly in the record. */
		private final int value;
		/** Deliberately unannotated input outside the identity. */
		private final int hidden;
		/** Captures both inputs through a real constructor. */
		private UnannotatedIdentityHolder(int value) { this.value = value; this.hidden = 7; }
		/** Returns an identity missing the hidden field. */
		@Nonnull private IdentityRecord identity() { return new Identity(this.value); }
		/** Returns this holder's identifier. */
		private long getClassId() { return CLASS_ID; }
		/** Builds the incomplete detached key. */
		@Nonnull private FormulaKey getKey() {
			return FormulaKey.of(identity(), getClassId(), FormulaKey.NO_CHILDREN, this);
		}
		/**
		 * Declared portion of the input.
		 *
		 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
		 */
		private record Identity(int value) implements IdentityRecord {
			/** Returns the owning holder's identifier. */
			@Override public long classId() { return CLASS_ID; }
			/** Includes the declared input component. */
			@Override public void describeTo(@Nonnull KeyAccumulator accumulator) { accumulator.add(this.value); }
		}
	}

	/**
	 * Defective holder whose identity reads a constructor-time snapshot instead of the field it claims to represent.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class SnapshotIdentityHolder {
		/** Stable holder identifier. */
		private static final long CLASS_ID = 4983150720710203257L;
		/** Input matching the identity component by name, deliberately never read by the identity. */
		private final int value;
		/** Copy of the input taken at construction; a later change of the input does not reach it. */
		@IdentityExempt(IdentityExempt.Reason.STATE)
		private final int snapshot;
		/** Captures the input and its snapshot. */
		private SnapshotIdentityHolder(int value) { this.value = value; this.snapshot = value; }
		/** Returns an identity built from the snapshot. */
		@Nonnull private IdentityRecord identity() { return new Identity(this.snapshot); }
		/** Returns this holder's identifier. */
		private long getClassId() { return CLASS_ID; }
		/** Builds the detached key. */
		@Nonnull private FormulaKey getKey() {
			return FormulaKey.of(identity(), getClassId(), FormulaKey.NO_CHILDREN, this);
		}
		/**
		 * Input value as captured by the snapshot.
		 *
		 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
		 */
		private record Identity(int value) implements IdentityRecord {
			/** Returns the owning holder's identifier. */
			@Override public long classId() { return CLASS_ID; }
			/** Includes the input component. */
			@Override public void describeTo(@Nonnull KeyAccumulator accumulator) { accumulator.add(this.value); }
		}
	}

	/**
	 * Defective holder with a field declared derived that differs between holders with equal keys.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class StampedIdentityHolder {
		/** Stable holder identifier. */
		private static final long CLASS_ID = 8158553858646080118L;
		/** Counts constructions, so every holder gets a different stamp. */
		private static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();
		/** Input represented directly in the identity. */
		private final int value;
		/** Declared derived although no identity component determines it. */
		@IdentityExempt(IdentityExempt.Reason.DERIVED)
		private final int stamp;
		/** Captures the input and stamps the construction. */
		private StampedIdentityHolder(int value) { this.value = value; this.stamp = CONSTRUCTIONS.incrementAndGet(); }
		/** Returns the input identity. */
		@Nonnull private IdentityRecord identity() { return new Identity(this.value); }
		/** Returns this holder's identifier. */
		private long getClassId() { return CLASS_ID; }
		/** Builds the detached key. */
		@Nonnull private FormulaKey getKey() {
			return FormulaKey.of(identity(), getClassId(), FormulaKey.NO_CHILDREN, this);
		}
		/**
		 * Complete input value.
		 *
		 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
		 */
		private record Identity(int value) implements IdentityRecord {
			/** Returns the owning holder's identifier. */
			@Override public long classId() { return CLASS_ID; }
			/** Includes the input component. */
			@Override public void describeTo(@Nonnull KeyAccumulator accumulator) { accumulator.add(this.value); }
		}
	}

	/**
	 * Defective holder whose identity and key carry a class identifier other than its `CLASS_ID` constant.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class MislabeledIdentityHolder {
		/** Stable holder identifier that the identity deliberately does not use. */
		private static final long CLASS_ID = 413462203321305713L;
		/** Input represented directly in the identity. */
		private final int value;
		/** Captures the input. */
		private MislabeledIdentityHolder(int value) { this.value = value; }
		/** Returns the mislabeled identity. */
		@Nonnull private IdentityRecord identity() { return new Identity(this.value); }
		/** Returns the identity's identifier rather than the declared constant. */
		private long getClassId() { return CLASS_ID + 1; }
		/** Builds the detached key under the mislabeled identifier. */
		@Nonnull private FormulaKey getKey() {
			return FormulaKey.of(identity(), getClassId(), FormulaKey.NO_CHILDREN, this);
		}
		/**
		 * Complete input value under an identifier other than the holder's constant.
		 *
		 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
		 */
		private record Identity(int value) implements IdentityRecord {
			/** Returns an identifier that differs from the holder's constant. */
			@Override public long classId() { return CLASS_ID + 1; }
			/** Includes the input component. */
			@Override public void describeTo(@Nonnull KeyAccumulator accumulator) { accumulator.add(this.value); }
		}
	}

	/**
	 * Defective holder whose identity carries a floating-point component outside the closed value domain.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class RatioIdentityHolder {
		/** Stable holder identifier. */
		private static final long CLASS_ID = -1519163504097783874L;
		/** Input represented directly in the identity. */
		private final int value;
		/** Constant input of a type the identity domain does not admit. */
		private final double ratio;
		/** Captures the input with a constant ratio. */
		private RatioIdentityHolder(int value) { this.value = value; this.ratio = 0.5d; }
		/** Returns the identity with the forbidden component. */
		@Nonnull private IdentityRecord identity() { return new Identity(this.value, this.ratio); }
		/** Returns this holder's identifier. */
		private long getClassId() { return CLASS_ID; }
		/** Builds the detached key. */
		@Nonnull private FormulaKey getKey() {
			return FormulaKey.of(identity(), getClassId(), FormulaKey.NO_CHILDREN, this);
		}
		/**
		 * Input value with a floating-point component, which no accumulator overload describes.
		 *
		 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
		 */
		private record Identity(int value, double ratio) implements IdentityRecord {
			/** Returns the owning holder's identifier. */
			@Override public long classId() { return CLASS_ID; }
			/** Includes the input component only; the ratio has no accumulator overload. */
			@Override public void describeTo(@Nonnull KeyAccumulator accumulator) { accumulator.add(this.value); }
		}
	}

	/**
	 * Opaque holder allowing a clone test to seed an incorrectly shared key.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class OpaqueAllocatingHolder {
		/** Stable holder identifier. */
		private static final long CLASS_ID = -5006071177894221475L;
		/** Opaque input marker, shared by every fresh construction. */
		private final IdentityToken token = IdentityToken.OPAQUE;
		/** Memoized key that a defective clone may incorrectly copy. */
		@IdentityExempt(IdentityExempt.Reason.STATE)
		@Nullable private FormulaKey memoizedKey;
		/** Constructs a fresh opaque holder. */
		private OpaqueAllocatingHolder() { }
		/** Returns the opaque identity. */
		@Nonnull private IdentityRecord identity() { return new Identity(this.token); }
		/** Returns this holder's identifier. */
		private long getClassId() { return CLASS_ID; }
		/** Binds the key to this holder unless a defective clone copied another owner's key. */
		@Nonnull private FormulaKey getKey() {
			if (this.memoizedKey == null) {
				this.memoizedKey = FormulaKey.of(identity(), getClassId(), FormulaKey.NO_CHILDREN, this);
			}
			return this.memoizedKey;
		}
		/**
		 * Opaque input description.
		 *
		 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
		 */
		private record Identity(@Nonnull IdentityToken token) implements IdentityRecord {
			/** Returns the owning holder's identifier. */
			@Override public long classId() { return CLASS_ID; }
			/** Includes the opaque marker. */
			@Override public void describeTo(@Nonnull KeyAccumulator accumulator) { accumulator.add(this.token); }
		}
	}

	/**
	 * Opaque holder whose declared clone path can return the holder itself.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class OpaqueSelfHolder {
		/** Stable holder identifier. */
		private static final long CLASS_ID = 7898468315114023650L;
		/** Shared opaque input marker. */
		private final IdentityToken token = IdentityToken.OPAQUE;
		/** Constructs a fresh opaque holder. */
		private OpaqueSelfHolder() { }
		/** Returns the opaque identity. */
		@Nonnull private IdentityRecord identity() { return new Identity(this.token); }
		/** Returns this holder's identifier. */
		private long getClassId() { return CLASS_ID; }
		/** Builds a key bound to this holder. */
		@Nonnull private FormulaKey getKey() {
			return FormulaKey.of(identity(), getClassId(), FormulaKey.NO_CHILDREN, this);
		}
		/**
		 * Opaque input description.
		 *
		 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
		 */
		private record Identity(@Nonnull IdentityToken token) implements IdentityRecord {
			/** Returns the owning holder's identifier. */
			@Override public long classId() { return CLASS_ID; }
			/** Includes the opaque marker. */
			@Override public void describeTo(@Nonnull KeyAccumulator accumulator) { accumulator.add(this.token); }
		}
	}
}
