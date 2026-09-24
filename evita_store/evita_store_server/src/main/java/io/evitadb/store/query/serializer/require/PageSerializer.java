/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.store.query.serializer.require;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.require.Page;
import io.evitadb.api.query.require.Spacing;
import lombok.RequiredArgsConstructor;

/**
 * This {@link Serializer} implementation reads/writes {@link Page} from/to binary format.
 *
 * The payload is the page number, the page size and finally the optional {@link Spacing} child. The spacing is a
 * child of the constraint and therefore part of its equality, so a page that lost it would replay as a different
 * page than the one that was recorded. Its position at the end of the payload keeps the two leading fields where
 * they were, but this is still a format change without a compatibility reader - the same position taken by the
 * managed-references behaviour before it, and with the same consequence: a payload written before it cannot be read
 * by this serializer.
 *
 * Who that affects: the traffic recorder and its replaying reader, and the locally generated benchmark query
 * corpora that `ClientSyntheticTestState` and `SanityChecker` load - none of which is tracked in this repository,
 * so a break costs a regeneration rather than data. Why no compatible middle ground exists for a query-constraint
 * serializer at all is written down once, for the sibling `QueryTelemetrySerializer`, in
 * `documentation/adr/2026-08-04-query-telemetry-actionable-profile.md`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class PageSerializer extends Serializer<Page> {

	@Override
	public void write(Kryo kryo, Output output, Page object) {
		output.writeInt(object.getPageNumber());
		output.writeInt(object.getPageSize());
		kryo.writeObjectOrNull(output, object.getSpacing().orElse(null), Spacing.class);
	}

	@Override
	public Page read(Kryo kryo, Input input, Class<? extends Page> type) {
		final int pageNumber = input.readInt();
		final int pageSize = input.readInt();
		return new Page(pageNumber, pageSize, kryo.readObjectOrNull(input, Spacing.class));
	}

}
