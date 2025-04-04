// use existing session id to execute a query
SessionIdHolder.executeInSession(
	sessionResponse.getSessionId(),
	() -> {
		// use our data model to build the query more easily
		final StringWithParameters theQuery = query(
			collection("Product"),
			filterBy(
				and(
					entityLocaleEquals(Locale.ENGLISH),
					attributeContains("url", "www"),
					priceInCurrency(Currency.getInstance("USD"))
				),
				priceInPriceLists("vip", "basic"),
				userFilter(
					facetHaving("variantParameters", entityPrimaryKeyInSet(1))
				)
			),
			require(
				page(1, 20),
				entityFetch(attributeContentAll())
			)
		)
			// and extract the query string and parameters
			.toStringWithParameterExtraction();

		// that could be simply passed to the gRPC client
		final GrpcQueryResponse response = sessionService.query(
			GrpcQueryRequest.newBuilder()
				.setQuery(theQuery.query())
				.addAllPositionalQueryParams(
					theQuery.parameters()
						.stream()
						.map(QueryConverter::convertQueryParam)
						.toList()
				)
				.build()
		);
	}
)
