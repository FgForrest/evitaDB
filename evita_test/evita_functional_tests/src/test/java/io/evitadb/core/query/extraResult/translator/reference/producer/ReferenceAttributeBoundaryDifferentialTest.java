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

package io.evitadb.core.query.extraResult.translator.reference.producer;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceAttributeBoundaryTestSupport.Fixture;
import io.evitadb.index.ReducedGroupEntityIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.OwnerFilterIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.util.PrimitiveIterator;
import java.util.Random;

import static io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceAttributeBoundaryTestSupport.*;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/** Fixed-seed differential sweep of independently generated bound candidates and owner witnesses in real indexes. */
@DisplayName("Reference attribute boundary pick against the exhaustive oracle on generated indexes")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(HISTOGRAM)
@Tag(REFERENCE)
class ReferenceAttributeBoundaryDifferentialTest {
	/**
	 * Varies partitions (two to ten indexes), duplicates, conditions, baselines, scope and every filter value.
	 *
	 * Three counters guard against a sweep that agrees with the oracle only because it never reaches the hard cases:
	 * `lateWitnesses` counts candidates whose owner check fails in one producing index and passes in a later one,
	 * `completeMisses` counts picks made when no candidate in scope passes the owner check, and `alternatives` counts
	 * picks carrying a distinct alternative. The sweep fails when any of them stays at zero.
	 */
	@ParameterizedTest
	@ValueSource(longs = {0, 37, 65536, 20261116})
	@DisplayName("should agree with the exhaustive oracle for every filter value")
	void shouldAgreeWithExhaustiveOracleForEveryFilterValue(long seed) {
		final Random random = new Random(seed);
		int lateWitnesses = 0;
		int completeMisses = 0;
		int alternatives = 0;
		int comparisons = 0;
		for (int run = 0; run < 180; run++) {
			// up to ten producing heads, so the merge sifts through right children and pops below the root's children
			final int indexCount = 2 + random.nextInt(9);
			final ReducedGroupEntityIndex[] indexes = new ReducedGroupEntityIndex[indexCount];
			final FilterIndex[] filters = new FilterIndex[indexCount];
			final OwnerFilterIndex owners = filter(Integer.class);
			final BaseBitmap baselinePks = new BaseBitmap();
			final BaseBitmap scope = new BaseBitmap();
			for (int owner = 100; owner < 108; owner++) {
				if (random.nextBoolean()) {
					baselinePks.add(owner);
				}
				for (int value = 0; value < 5; value++) {
					// An accepted owner attribute condition is represented by independently admitted owner/value cells.
					if (random.nextInt(4) == 0) {
						owners.addRecord(owner, value);
					}
				}
			}
			for (int p = 0; p < 18; p++) {
				if (random.nextInt(4) != 0) {
					scope.add(p < 9 ? p + 1 : 65530 + p);
				}
			}
			for (int i = 0; i < indexCount; i++) {
				indexes[i] = index(i);
				filters[i] = filter(Integer.class);
				for (int p = 0; p < 18; p++) {
					final int pk = p < 9 ? p + 1 : 65530 + p;
					for (int owner = 100; owner < 108; owner++) {
						if (random.nextInt(5) == 0) {
							indexes[i].insertPrimaryKeyIfMissing(owner, pk);
							if (random.nextBoolean()) {
								indexes[i].insertPrimaryKeyIfMissing(owner, pk);
							}
						}
					}
					// Candidate membership is independent of the owners and their condition/baseline membership.
					for (int value = 0; value < 5; value++) {
						if (random.nextInt(4) == 0) {
							filters[i].addRecord(pk, value);
						}
					}
				}
				if (random.nextInt(12) == 0) {
					filters[i] = null;
				}
			}
			final Formula baseline = run % 4 == 0 ? null : baselinePks.isEmpty() ?
				EmptyFormula.INSTANCE : new ConstantFormula(baselinePks);
			final Fixture fixture = new Fixture(filters, indexes, owners, baseline, scope);
			for (int value = 0; value < 5; value++) {
				// the failure message is built lazily, so the lambda needs effectively final copies of the loop state
				final int currentRun = run;
				final int currentValue = value;
				fixture.compare(value, null, null,
					() -> "seed=" + seed + " run=" + currentRun + " value=" + currentValue +
						describe(fixture, currentValue));
				comparisons++;
				final ReferenceAttributeBoundaryOracle.Pair result = fixture.actual(value, null);
				if (result.alternativePk() != null) {
					alternatives++;
				}
				final WitnessOutcome outcome = classifyWitnesses(fixture, value, baselinePks);
				lateWitnesses += outcome.lateWitnesses();
				if (!outcome.anyWitness() && result.pk() != null) {
					completeMisses++;
				}
			}
		}
		final String counters = "seed=" + seed + " comparisons=" + comparisons + " lateWitnesses=" + lateWitnesses +
			" completeMisses=" + completeMisses + " alternatives=" + alternatives;
		assertTrue(lateWitnesses > 0, "no candidate passed the owner check only in a later index: " + counters);
		assertTrue(completeMisses > 0, "no bound was picked without any passing owner check: " + counters);
		assertTrue(alternatives > 0, "no pick carried a distinct alternative: " + counters);
	}

	/**
	 * Restates the owner check for every in-scope PK of one bound from the generated facts, independently of the
	 * picker: a PK is checked in index order in each index whose filter index holds it at the value and which tracks
	 * it, and passes in the first index where one of its owners holds the value in the owner filter and, when the
	 * bound has a baseline, belongs to it.
	 *
	 * @param fixture     the generated inputs of the bound
	 * @param value       the bound
	 * @param baselinePks the owners of the baseline; read only when the fixture has a baseline
	 * @return how many PKs passed only after failing in an earlier index, and whether any PK passed at all
	 */
	@Nonnull
	private static WitnessOutcome classifyWitnesses(@Nonnull Fixture fixture, int value, @Nonnull Bitmap baselinePks) {
		final Bitmap counted = ReferenceAttributeBoundaryOracle.lookup(fixture.owners(), value);
		int lateWitnesses = 0;
		boolean anyWitness = false;
		final PrimitiveIterator.OfInt candidates = fixture.scope().iterator();
		while (candidates.hasNext()) {
			final int pk = candidates.nextInt();
			boolean failedEarlier = false;
			for (int i = 0; i < fixture.indexes().length; i++) {
				final FilterIndex filter = fixture.filters()[i];
				final ReducedGroupEntityIndex index = (ReducedGroupEntityIndex) fixture.indexes()[i];
				if (filter == null ||
					!ReferenceAttributeBoundaryOracle.lookup(filter, value).contains(pk)
					|| !index.getAllReferencedPrimaryKeys().contains(pk)) {
					continue;
				}
				final Bitmap witnesses = index.getOwnerPKsForReferencedEntity(pk);
				boolean succeeds = false;
				if (witnesses != null) {
					final PrimitiveIterator.OfInt witness = witnesses.iterator();
					while (witness.hasNext()) {
						final int owner = witness.nextInt();
						succeeds |= counted.contains(owner) &&
							(fixture.baseline() == null || baselinePks.contains(owner));
					}
				}
				if (succeeds) {
					anyWitness = true;
					if (failedEarlier) {
						lateWitnesses++;
					}
					break;
				}
				failedEarlier = true;
			}
		}
		return new WitnessOutcome(lateWitnesses, anyWitness);
	}

	/** Describes all candidate and witness facts needed to reproduce a failing seeded cell. */
	@Nonnull
	private static String describe(@Nonnull Fixture fixture, int value) {
		final StringBuilder description = new StringBuilder(1024);
		description.append(" scope=").append(fixture.scope());
		description.append(" owners=").append(ReferenceAttributeBoundaryOracle.lookup(fixture.owners(), value));
		description.append(" baseline=").append(fixture.baseline() == null ? null : fixture.baseline().compute());
		for (int i = 0; i < fixture.indexes().length; i++) {
			description.append(" index=").append(i).append(" candidates=");
			if (fixture.filters()[i] == null) {
				description.append("null");
				continue;
			}
			final Bitmap candidates = ReferenceAttributeBoundaryOracle.lookup(fixture.filters()[i], value);
			description.append(candidates);
			final PrimitiveIterator.OfInt iterator = candidates.iterator();
			while (iterator.hasNext()) {
				final int pk = iterator.nextInt();
				description.append(" pk=").append(pk).append(" witnesses=")
					.append(((ReducedGroupEntityIndex) fixture.indexes()[i]).getOwnerPKsForReferencedEntity(pk));
			}
		}
		return description.toString();
	}

	/**
	 * How the in-scope PKs of one bound fared in the restated owner check.
	 *
	 * @param lateWitnesses the PKs that passed only after failing in an earlier producing index
	 * @param anyWitness    whether any PK passed
	 */
	private record WitnessOutcome(int lateWitnesses, boolean anyWitness) {
	}
}
