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


package io.evitadb.index.virtual;

import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance.PromotionRehearsal;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance.TransitionProbe;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance.TransitionStage;
import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Records the transitions of the partitions of one engine as its {@link TransitionProbe}, keeping only what the
 * engine kept: a transition belongs to the root entity mutation that passed it and counts only once that root
 * mutation committed. The transitions of a root mutation that was rolled back are dropped with it, so a transition a
 * failed mutation passed never explains the state the engine is in.
 *
 * Two histories are kept:
 *
 * - the **session history** - the transitions of the root mutations committed in the session that is open right now.
 *   It is dropped when the session is rolled back. When the session closes, it is dropped in `ALIVE`, where the commit
 *   replays the session's mutations onto the trunk and so produces the committed history anew, and appended to the
 *   committed history in `WARMING_UP`, where the session wrote in place;
 * - the **committed history** - the transitions of the committed root mutations of replays (onto the trunk after a
 *   commit, or from the write-ahead log at load), and of the closed sessions of a catalog warming up.
 *
 * The committed history describes the committed state of the engine; the committed history followed by the session
 * history describes what the open session sees. Tests read them to prove that a history exercised the transitions it
 * names - the registry itself is checked against the partitions, never against a history.
 *
 * # Failure injection
 *
 * The recorder can be armed to throw {@link InjectedTransitionFailure} right after an engine passes a given transition
 * of a given entity type - once, inside the mutation passing it, which fails that mutation at exactly that point.
 *
 * # Promotion points
 *
 * A single-owner partition gaining its second owner is not a transition the histories record. The recorder hands it
 * to an inspector a test installs (see {@link #setPromotionInspector(Consumer)}), if any, on the thread running the
 * mutation - the session's or the trunk replay's alike.
 *
 * Every method is synchronized: the probe is called from the threads the engine runs its mutations on - the session's,
 * the trunk replay's, the catalog load's - while the test reads it from its own.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class TransitionHistory implements TransitionProbe {
	/**
	 * The transitions of the root mutations that have not finished yet, by root mutation identity.
	 */
	private final Map<Object, List<Transition>> pending = new IdentityHashMap<>(4);
	/**
	 * The transitions of the root mutations committed in the open session.
	 */
	private final List<Transition> session = new ArrayList<>(32);
	/**
	 * The transitions of the committed root mutations the committed state is made of.
	 */
	private final List<Transition> committed = new ArrayList<>(128);
	/**
	 * The transitions of the root mutations that were rolled back.
	 */
	private final List<Transition> rolledBack = new ArrayList<>(8);
	/**
	 * The transition the recorder throws after, once; `null` when it is not armed.
	 */
	@Nullable private InjectionPoint armed;
	/**
	 * How many failures the recorder has injected.
	 */
	private int injectedFailures;
	/**
	 * The inspector every promotion point is handed to; `null` when none is installed.
	 */
	@Nullable private Consumer<PromotionRehearsal> promotionInspector;

	/**
	 * One transition of one partition.
	 *
	 * @param stage               the transition
	 * @param entityType          the entity type owning the reference
	 * @param referenceName       the reference
	 * @param scope               the scope of the partition
	 * @param partitionPrimaryKey the primary key of the partition
	 */
	record Transition(
		@Nonnull TransitionStage stage,
		@Nonnull String entityType,
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		int partitionPrimaryKey
	) {
	}

	/**
	 * A transition the recorder is armed to throw after.
	 *
	 * @param stage      the transition
	 * @param entityType the entity type owning the reference
	 * @param occurrence how many matching transitions to let pass before throwing after the next one
	 */
	private record InjectionPoint(@Nonnull TransitionStage stage, @Nonnull String entityType, int occurrence) {
	}

	/**
	 * The failure the recorder injects.
	 */
	static final class InjectedTransitionFailure extends RuntimeException {
		private static final long serialVersionUID = 4425128531853306148L;

		/**
		 * Creates the failure.
		 *
		 * @param transition the transition after which it is thrown
		 */
		InjectedTransitionFailure(@Nonnull Transition transition) {
			super("Failure injected after " + transition);
		}
	}

	@Override
	public synchronized void transitionPassed(
		@Nonnull TransitionStage stage,
		@Nonnull String entityType,
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		int partitionPrimaryKey,
		@Nonnull Object rootMutation
	) {
		final Transition transition = new Transition(stage, entityType, referenceName, scope, partitionPrimaryKey);
		this.pending.computeIfAbsent(rootMutation, it -> new ArrayList<>(4)).add(transition);
		final InjectionPoint injection = this.armed;
		if (injection != null && injection.stage() == stage && injection.entityType().equals(entityType)) {
			if (injection.occurrence() == 0) {
				this.armed = null;
				this.injectedFailures++;
				throw new InjectedTransitionFailure(transition);
			}
			this.armed = new InjectionPoint(stage, entityType, injection.occurrence() - 1);
		}
	}

	@Override
	public synchronized void rootMutationFinished(
		@Nonnull TransitionStage stage,
		@Nonnull Object rootMutation,
		boolean trunkReplay
	) {
		final List<Transition> transitions = this.pending.remove(rootMutation);
		if (transitions == null) {
			return;
		}
		switch (stage) {
			case ROOT_COMMITTED -> (trunkReplay ? this.committed : this.session).addAll(transitions);
			case ROOT_ROLLED_BACK -> this.rolledBack.addAll(transitions);
			default -> throw new IllegalStateException("Unexpected outcome of a root mutation: " + stage);
		}
	}

	@Override
	public void promotionPointPassed(@Nonnull PromotionRehearsal rehearsal, @Nonnull Object rootMutation) {
		final Consumer<PromotionRehearsal> inspector;
		synchronized (this) {
			inspector = this.promotionInspector;
		}
		// run outside the lock - the inspector does as much work as a mutation, on the mutation's thread
		if (inspector != null) {
			inspector.accept(rehearsal);
		}
	}

	/**
	 * Installs the inspector every later promotion point is handed to, or removes it.
	 *
	 * @param inspector the inspector, `null` to remove it
	 */
	synchronized void setPromotionInspector(@Nullable Consumer<PromotionRehearsal> inspector) {
		this.promotionInspector = inspector;
	}

	/**
	 * Arms the recorder to throw {@link InjectedTransitionFailure} right after the next transition of the given kind
	 * of the given entity type.
	 *
	 * @param stage      the transition
	 * @param entityType the entity type owning the reference
	 */
	synchronized void failAfter(@Nonnull TransitionStage stage, @Nonnull String entityType) {
		failAfter(stage, entityType, 0);
	}

	/**
	 * Arms the recorder to throw {@link InjectedTransitionFailure} right after a later transition of the given kind of
	 * the given entity type, letting the given number of matching transitions pass first.
	 *
	 * @param stage      the transition
	 * @param entityType the entity type owning the reference
	 * @param skip       how many matching transitions to let pass
	 */
	synchronized void failAfter(@Nonnull TransitionStage stage, @Nonnull String entityType, int skip) {
		this.armed = new InjectionPoint(stage, entityType, skip);
	}

	/**
	 * Disarms the recorder and returns whether it was still armed - i.e. whether the failure it was armed for was not
	 * injected.
	 *
	 * @return TRUE when the recorder was still armed
	 */
	synchronized boolean disarm() {
		final boolean wasArmed = this.armed != null;
		this.armed = null;
		return wasArmed;
	}

	/**
	 * Returns how many failures the recorder has injected.
	 *
	 * @return the number of injected failures
	 */
	synchronized int getInjectedFailures() {
		return this.injectedFailures;
	}

	/**
	 * Closes the session history: appends it to the committed history when the session wrote in place and was not
	 * rolled back, and drops it otherwise.
	 *
	 * @param wroteInPlace TRUE when the session ran on a catalog warming up, whose writes are committed as they are
	 *                     made
	 * @param rolledBack   TRUE when the session was rolled back
	 */
	synchronized void sessionClosed(boolean wroteInPlace, boolean rolledBack) {
		if (wroteInPlace && !rolledBack) {
			this.committed.addAll(this.session);
		}
		this.session.clear();
	}

	/**
	 * Returns the committed history.
	 *
	 * @return a copy of the committed history, in the order the transitions were passed
	 */
	@Nonnull
	synchronized List<Transition> getCommitted() {
		return List.copyOf(this.committed);
	}

	/**
	 * Returns the session history.
	 *
	 * @return a copy of the session history, in the order the transitions were passed
	 */
	@Nonnull
	synchronized List<Transition> getSession() {
		return List.copyOf(this.session);
	}

	/**
	 * Returns the transitions of the root mutations that were rolled back.
	 *
	 * @return a copy of them, in the order they were passed
	 */
	@Nonnull
	synchronized List<Transition> getRolledBack() {
		return List.copyOf(this.rolledBack);
	}
}
