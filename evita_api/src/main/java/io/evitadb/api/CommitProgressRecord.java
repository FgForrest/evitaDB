/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;

/**
 * CommitProgressRecord is an implementation of {@link CommitProgress} that represents the progress of a transaction
 * commit operation in a database. It contains multiple CompletableFuture objects that allow tracking the status of
 * various stages of the commit process.
 *
 * This implementation provides methods to complete all futures either successfully or exceptionally.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class CommitProgressRecord implements CommitProgress {

    /**
     * CompletableFuture that completes when the system verifies changes are not in conflict with
     * changes from other transactions committed in the meantime.
     */
    private final CompletableFuture<CommitVersions> onConflictResolved;

    /**
     * CompletableFuture that completes when the changes are appended to the Write Ahead Log (WAL).
     */
    private final CompletableFuture<CommitVersions> onWalAppended;

    /**
     * CompletableFuture that completes when the changes are visible to all readers.
     */
    private final CompletableFuture<CommitVersions> onChangesVisible;

    /**
     * Creates a new instance of CommitProgressRecord with the specified CompletableFuture objects.
     */
    public CommitProgressRecord() {
        this.onConflictResolved = new CompletableFuture<>();
        this.onWalAppended = new CompletableFuture<>();
        this.onChangesVisible = new CompletableFuture<>();
    }

    @Override
    @Nonnull
    public CompletableFuture<CommitVersions> onConflictResolved() {
        return this.onConflictResolved;
    }

    @Override
    @Nonnull
    public CompletableFuture<CommitVersions> onWalAppended() {
        return this.onWalAppended;
    }

    @Override
    @Nonnull
    public CompletableFuture<CommitVersions> onChangesVisible() {
        return this.onChangesVisible;
    }

    @Override
    public boolean isCompletedSuccessfully() {
        return this.onConflictResolved.isDone() &&
                this.onWalAppended.isDone() &&
                this.onChangesVisible.isDone() &&
                !this.onConflictResolved.isCompletedExceptionally() &&
                !this.onWalAppended.isCompletedExceptionally() &&
                !this.onChangesVisible.isCompletedExceptionally();
    }

    @Override
    public boolean isCompletedExceptionally() {
        return this.onConflictResolved.isCompletedExceptionally() ||
                this.onWalAppended.isCompletedExceptionally() ||
                this.onChangesVisible.isCompletedExceptionally();
    }

    /**
     * Completes all non-finished futures exceptionally with the specified exception.
     *
     * @param exception the exception to complete the futures with
     */
    public void completeExceptionally(@Nonnull Throwable exception) {
        if (!this.onConflictResolved.isDone()) {
            this.onConflictResolved.completeExceptionally(exception);
        }
        if (!this.onWalAppended.isDone()) {
            this.onWalAppended.completeExceptionally(exception);
        }
        if (!this.onChangesVisible.isDone()) {
            this.onChangesVisible.completeExceptionally(exception);
        }
    }

    /**
     * Completes all non-finished futures successfully.
     * For onConflictResolved, it completes with null.
     * For onWalAppended and onChangesVisible, it completes with the specified CommitVersions.
     *
     * @param durableResult the result to complete the futures with
     */
    public void complete(@Nonnull CommitVersions durableResult) {
        if (!this.onConflictResolved.isDone()) {
            this.onConflictResolved.complete(durableResult);
        }
        if (!this.onWalAppended.isDone()) {
            this.onWalAppended.complete(durableResult);
        }
        if (!this.onChangesVisible.isDone()) {
            this.onChangesVisible.complete(durableResult);
        }
    }
}