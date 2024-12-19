/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

// use existing session id to execute a query
SessionIdHolder.executeInSession(
	sessionResponse.getSessionId(),
	() -> {
		final List<GrpcQueryParam> params = new ArrayList<>();
		params.add(QueryConverter.convertQueryParam("Product"));
		params.add(QueryConverter.convertQueryParam("en"));
		params.add(QueryConverter.convertQueryParam("url"));
		params.add(QueryConverter.convertQueryParam("www"));
		params.add(QueryConverter.convertQueryParam(Currency.getInstance("USD")));
		params.add(QueryConverter.convertQueryParam("vip"));
		params.add(QueryConverter.convertQueryParam("basic"));
		params.add(QueryConverter.convertQueryParam("variantParameters"));
		params.add(QueryConverter.convertQueryParam(1));
		params.add(QueryConverter.convertQueryParam(1));
		params.add(QueryConverter.convertQueryParam(20));

		final String stringQuery = """
		    query(
			    collection(?),
			    filterBy(
			        and(
			            entityLocaleEquals(?),
			            attributeContains(?, ?),
			            priceInCurrency(?),
			            priceInPriceLists(?, ?),
			            userFilter(
			                facetHaving(?, entityPrimaryKeyInSet(?))
			            )
			        )
			    ),
			    require(
			        page(?, ?),
			        entityFetch(attributeContentAll())
			    )
			 )
		     """;

		final GrpcQueryResponse response = sessionService.query(GrpcQueryRequest.newBuilder()
			.setQuery(stringQuery)
			.addAllPositionalQueryParams(params)
			.build());
	}
);
