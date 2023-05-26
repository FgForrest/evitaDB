final EvitaResponse<SealedEntity> response = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					and(
						hierarchyWithin(
							"categories",
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
						attributeContentAll(),
						priceContentRespectingFilter()
					),
					facetSummary()
				)
			)
		);
	}
);
PaginatedList<SealedEntity> paginatedList = (PaginatedList<SealedEntity>) response.getRecordPage();
List<SealedEntity> entities = paginatedList.getData();
int pageNumber = paginatedList.getPageNumber();
int pageSize = paginatedList.getPageSize();
int totalRecordCount = paginatedList.getTotalRecordCount();
FacetSummary facetSummary = response.getExtraResult(FacetSummary.class);
