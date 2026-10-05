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

package io.evitadb.test.upgrade;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.Query;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * The schema and the mutations of one release fixture — a catalog written by a released engine for the upgrade tests.
 * The same recipe is executed by the released engine that writes the fixture ({@link ReleaseFixtureWriter}) and can be
 * replayed by the current engine into a fresh catalog, so the answers of an upgraded release catalog can be compared
 * with what the current engine builds from the same input.
 *
 * A recipe therefore uses only the API that the released engines and the current one have in common. Recipes of
 * fixtures the 2026.1 engine writes live in {@link Release2026_1FixtureRecipes}, which compiles against that release as
 * well; all others in {@link Release2026_2FixtureRecipes}.
 *
 * The catalog name equals the fixture {@link #name()}; the generator writes each fixture into a storage directory of
 * its own, named after the fixture.
 *
 * @param name                      the fixture and catalog name, unique among all fixtures
 * @param origin                    which released engines write the fixture
 * @param shape                     one sentence on the release shape the fixture holds
 * @param warmUp                    schema and data written in a warming-up session, which then goes live
 * @param aliveTransactions         transactions the writing release commits after the catalog went live, in order
 * @param release2026_2Transactions transactions the 2026.2 engine commits after opening a catalog the 2026.1 engine
 *                                  wrote (only for {@link ReleaseFixtureOrigin#RELEASE_2026_1_OPENED_BY_2026_2})
 * @param probes                    read-only queries the fixture is checked with
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record ReleaseFixtureRecipe(
	@Nonnull String name,
	@Nonnull ReleaseFixtureOrigin origin,
	@Nonnull String shape,
	@Nonnull Consumer<EvitaSessionContract> warmUp,
	@Nonnull List<Consumer<EvitaSessionContract>> aliveTransactions,
	@Nonnull List<Consumer<EvitaSessionContract>> release2026_2Transactions,
	@Nonnull List<ReleaseFixtureProbe> probes
) {

	/**
	 * Creates a recipe of a fixture the v2026.2.18 engine writes.
	 *
	 * @param name   the fixture and catalog name
	 * @param shape  one sentence on the release shape the fixture holds
	 * @param warmUp schema and data written in a warming-up session
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe writtenBy2026_2(
		@Nonnull String name,
		@Nonnull String shape,
		@Nonnull Consumer<EvitaSessionContract> warmUp
	) {
		return new ReleaseFixtureRecipe(
			name, ReleaseFixtureOrigin.RELEASE_2026_2, shape, warmUp,
			Collections.emptyList(), Collections.emptyList(), Collections.emptyList()
		);
	}

	/**
	 * Creates a recipe of a fixture the v2026.1.20 engine writes and no later release opens. Call
	 * {@link #thenOpenedBy2026_2(Consumer[])} to have the 2026.2 engine open it as well.
	 *
	 * @param name   the fixture and catalog name
	 * @param shape  one sentence on the release shape the fixture holds
	 * @param warmUp schema and data written in a warming-up session
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe writtenBy2026_1(
		@Nonnull String name,
		@Nonnull String shape,
		@Nonnull Consumer<EvitaSessionContract> warmUp
	) {
		return new ReleaseFixtureRecipe(
			name, ReleaseFixtureOrigin.RELEASE_2026_1, shape, warmUp,
			Collections.emptyList(), Collections.emptyList(), Collections.emptyList()
		);
	}

	/**
	 * Returns a copy of this recipe whose writing release also commits the given transactions, one after another,
	 * once the catalog went live.
	 *
	 * @param transactions the transactions in the order they are committed
	 * @return the extended recipe
	 */
	@SafeVarargs
	@Nonnull
	public final ReleaseFixtureRecipe thenAlive(@Nonnull Consumer<EvitaSessionContract>... transactions) {
		return new ReleaseFixtureRecipe(
			this.name, this.origin, this.shape, this.warmUp,
			append(this.aliveTransactions, transactions), this.release2026_2Transactions, this.probes
		);
	}

	/**
	 * Returns a copy of this 2026.1 recipe that the 2026.2 engine opens after the 2026.1 engine wrote it, upgrading the
	 * catalog to storage protocol 6, and in which it then commits the given transactions (possibly none).
	 *
	 * @param transactions the transactions the 2026.2 engine commits, in order
	 * @return the extended recipe
	 * @throws IllegalStateException when this recipe is not written by the 2026.1 engine
	 */
	@SafeVarargs
	@Nonnull
	public final ReleaseFixtureRecipe thenOpenedBy2026_2(@Nonnull Consumer<EvitaSessionContract>... transactions) {
		if (this.origin == ReleaseFixtureOrigin.RELEASE_2026_2) {
			throw new IllegalStateException(
				"Fixture `" + this.name + "` is written by the 2026.2 engine and cannot be opened by it afterwards."
			);
		}
		return new ReleaseFixtureRecipe(
			this.name, ReleaseFixtureOrigin.RELEASE_2026_1_OPENED_BY_2026_2, this.shape, this.warmUp,
			this.aliveTransactions, append(this.release2026_2Transactions, transactions), this.probes
		);
	}

	/**
	 * Returns a copy of this recipe extended by one read-only probe.
	 *
	 * @param label a short name of the probe, unique within the recipe
	 * @param query the query; it must return entity references
	 * @return the extended recipe
	 */
	@Nonnull
	public ReleaseFixtureRecipe withProbe(@Nonnull String label, @Nonnull Query query) {
		final List<ReleaseFixtureProbe> extended = new ArrayList<>(this.probes.size() + 1);
		extended.addAll(this.probes);
		extended.add(new ReleaseFixtureProbe(label, query));
		return new ReleaseFixtureRecipe(
			this.name, this.origin, this.shape, this.warmUp,
			this.aliveTransactions, this.release2026_2Transactions, Collections.unmodifiableList(extended)
		);
	}

	/**
	 * Returns a new unmodifiable list holding the elements of `list` followed by `additions`.
	 *
	 * @param list      the original elements
	 * @param additions the elements to append
	 * @param <T>       the element type
	 * @return the combined list
	 */
	@Nonnull
	private static <T> List<T> append(@Nonnull List<T> list, @Nonnull T[] additions) {
		final List<T> extended = new ArrayList<>(list.size() + additions.length);
		extended.addAll(list);
		extended.addAll(Arrays.asList(additions));
		return Collections.unmodifiableList(extended);
	}

}
