final EvitaResponse<SealedEntity> response = evita.queryCatalog(
	"evita",
	session -> {
		return session.query(
			query(
				collection("Product"),
				filterBy(
					and(
						hierarchyWithin(
							attributeEquals("url", "/en/macbooks")
						),
						entityLocaleEquals(Locale.ENGLISH),
						priceInPriceLists("basic"),
						priceInCurrency(Currency.getInstance("USD"))
					)
				),
				require(
					page(2, 24),
					entityFetch(
						attributeContent(),
						priceContent(),
					),
					facetSummary()
				)
			),
			SealedEntity.class
		);
	}
);
PaginatedList<SealedEntity> paginatedList = response.getRecordPage();
List<SealedEntity> entities = paginatedList.getData();
int pageNumber = paginatedList.getPageNumber();
int pageSize = paginatedList.getPageSize();
int totalRecordCount = paginatedList.getTotalRecordCount();
FacetSummary facetSummary = response.getExtraResult(FacetSummary.class);
