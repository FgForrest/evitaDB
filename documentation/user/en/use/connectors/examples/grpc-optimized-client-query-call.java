final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = 
	EvitaSessionServiceGrpc.newBlockingStub(channel);

final StringWithParameters theQuery = query(
	collection("Product"),
	filterBy(
		and(
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
).toStringWithParameterExtraction();

final GrpcQueryResponse response = evitaSessionBlockingStub.query(
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