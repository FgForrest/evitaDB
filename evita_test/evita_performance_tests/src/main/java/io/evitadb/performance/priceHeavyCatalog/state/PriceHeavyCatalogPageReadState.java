/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.performance.priceHeavyCatalog.state;

import io.evitadb.performance.client.state.ClientPageReadState;
import io.evitadb.performance.priceHeavyCatalog.PriceHeavyCatalogDataSource;
import io.evitadb.performance.setup.EvitaCatalogReusableSetup;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import javax.annotation.Nonnull;

/**
 * evitaDB in memory implementation specific implementation of {@link ClientPageReadState}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@State(Scope.Benchmark)
public class PriceHeavyCatalogPageReadState extends ClientPageReadState
	implements EvitaCatalogReusableSetup, PriceHeavyCatalogDataSource {

	@Nonnull
	@Override
	public String getCatalogName() {
		return PriceHeavyCatalogDataSource.super.getCatalogName();
	}

}
