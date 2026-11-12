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
import java.util.function.Supplier;

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
	/** Verifies a complete value identity, field transplant and declared equal clone. */
	@Test
	@DisplayName("Accepts a correct value holder")
	void shouldAcceptCorrectHolder() {
		IdentityContractVerifier.verify(CorrectIdentityHolder.class, () -> Map.of("value", new ConstructionMode<>(
			() -> new CorrectIdentityHolder(1), Map.of(
				"value", () -> new CorrectIdentityHolder(2),
				"tokens", () -> new CorrectIdentityHolder(1, TokenList.ofProducerIds(new long[]{2}, false))
			), Map.of(), Map.of("oversized", () -> new CorrectIdentityHolder(
				1, TokenList.ofProducerIds(new long[TokenList.CAP + 1], false)
			)), Map.of("copy", new ClonePath<>(
				holder -> new CorrectIdentityHolder(holder.value, holder.tokens), CloneOutcome.EQUAL)),
			Set.of(), Set.of()
		)));
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

	/** Checks all Java line terminators when stripping a leading line comment. */
	@Test
	@DisplayName("Discovers source after CR, LF and CRLF line comments")
	void shouldRecognizeLineTerminators(@TempDir @Nonnull Path sourceRoot) throws IOException {
		for (final String terminator : List.of("\r", "\n", "\r\n")) {
			Files.writeString(sourceRoot.resolve("Missing.java"), "// header" + terminator +
				"package missing;" + terminator + "class Missing {}" + terminator);
			final AssertionError error = assertThrows(AssertionError.class,
				() -> IdentityContractVerifier.discover(sourceRoot));
			assertTrue(error.getMessage().contains("Cannot load discovered source class missing.Missing"),
				error::getMessage);
		}
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
		assertTrue(error.getMessage().contains("check 8: newly allocated opaque clone"), error::getMessage);
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
			@Override public void describeTo(@Nonnull KeyAccumulator accumulator) { accumulator.addStrings(this.values); }
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
