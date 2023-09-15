final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = EvitaSessionServiceGrpc.newBlockingStub(channel);
final List<GrpcQueryParam> params = new ArrayList<>();
params.add(QueryConverter.convertQueryParam("Product"));
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

final GrpcQueryResponse response = evitaSessionBlockingStub.query(GrpcQueryRequest.newBuilder()
	.setQuery(stringQuery)
	.addAllPositionalQueryParams(params)
	.build());