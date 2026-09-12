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

package io.evitadb.core.management;

import io.evitadb.api.task.ServerTask;
import io.evitadb.core.engine.CatalogFolderReservation;
import io.evitadb.core.executor.SequentialTask;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.UUID;

/**
 * The two steps that unpack a backup archive and bind a catalog to the folder it was unpacked into, plus the claim
 * on the catalog name that has to stay held across both of them.
 *
 * A restore is the one materialising path whose folder claim outlives the call that took it: the folder is allocated
 * by {@link #unpackStep()} and the mutation that binds a catalog to it runs in {@link #registerStep()}, resolving the
 * folder by catalog **name**. Releasing in between would let a second restore take the name while the first is still
 * writing, and the first would then bind its catalog to the second one's half-written folder.
 *
 * Whoever assembles these into a task therefore owes exactly one call to {@link #releaseClaim()}, attached to **that
 * task's** completion - it runs on success, on failure and on cancellation alike, including a first step that failed
 * and never reached the second. A task that never ran holds nothing, so there is nothing to release.
 *
 * Handed out as steps rather than as a finished task because the same pair serves two different sequences - a plain
 * restore, and the restore-to-version operation that wraps further steps around it.
 *
 * @param unpackStep   unpacks the archive into a freshly allocated folder
 * @param registerStep binds a catalog of the restored name to that folder, leaving it inactive
 * @param claim        the claim spanning the two steps
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
record RestorationSteps(
	@Nonnull ServerTask<?, Void> unpackStep,
	@Nonnull ServerTask<?, Void> registerStep,
	@Nonnull RestoreFolderClaim claim
) {

	/**
	 * Gives the folder claim back, if this restoration still owns it.
	 *
	 * A handover rather than a read: the registering step takes the very same claim, and this can fire *while* that
	 * step is running, because `SequentialTask#cancel()` completes the task's future without stopping a step already
	 * in flight. Whichever of the two gets there first owns the release and the other finds nothing. It can also fire
	 * before there is anything to take - when a cancel lands inside the allocation itself - which is why the
	 * allocation compare-and-sets its publication and releases on the spot when it loses; see
	 * {@link RestoreFolderClaim}.
	 */
	void releaseClaim() {
		final CatalogFolderReservation reservation = this.claim.takeClaim();
		if (reservation != null) {
			reservation.close();
		}
	}

	/**
	 * Assembles this pair into a {@link SequentialTask} under the given name, with {@link #releaseClaim()}
	 * already attached to its completion.
	 *
	 * The one call every caller of this pair must make - see {@link #releaseClaim()} - is wired here instead of
	 * at each call site, so a future caller cannot build the sequence and forget to attach it.
	 *
	 * @param catalogName name of the catalog the sequence operates on, or `null` when it is instance-wide
	 * @param taskName    human-readable name displayed to clients
	 * @return the sequence, ready to be issued and executed, or submitted to a scheduler
	 */
	@Nonnull
	SequentialTask<Void> asSequentialTask(@Nullable String catalogName, @Nonnull String taskName) {
		final SequentialTask<Void> task = new SequentialTask<>(
			catalogName, taskName, this.unpackStep, this.registerStep
		);
		task.getFutureResult().whenComplete((result, ex) -> releaseClaim());
		return task;
	}

	/**
	 * Builds the steps that unpack one archive into a catalog of a given name.
	 *
	 * Exists so that a task assembling a larger sequence can create its restoration steps **at the moment its
	 * archive exists**, without reaching into {@link EvitaManagement}'s internals. The archive is produced by an
	 * earlier step of that same sequence, so its identity is unknown when the sequence is assembled.
	 */
	@FunctionalInterface
	interface RestorationStepsFactory {

		/**
		 * @param catalogName        name of the catalog to restore into
		 * @param fileId             id of the archive being restored
		 * @param pathToFile         path to the ZIP archive on a local file system
		 * @param totalBytesExpected size of the archive, used to report unpacking progress
		 * @param deleteAfterRestore whether to delete the archive once it has been unpacked
		 * @return the steps and the claim spanning them
		 */
		@Nonnull
		RestorationSteps create(
			@Nonnull String catalogName,
			@Nonnull UUID fileId,
			@Nonnull Path pathToFile,
			long totalBytesExpected,
			boolean deleteAfterRestore
		);

	}

}
