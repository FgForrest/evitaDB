EvitaResponse<ISealedEntity> response = evita.QueryCatalog(
	"evita",
	session => {
		return session.QuerySealedEntity(
			Query(
				Collection("Product"),
				FilterBy(
					And(
						HierarchyWithin(
							"categories",
							AttributeEquals("url", "/en/macbooks")
						),
						EntityLocaleEquals(new CultureInfo("en")),
						PriceInPriceLists("basic"),
						PriceInCurrency(new Currency("USD"))
					)
				),
				Require(
					Page(2, 24),
					EntityFetch(
						AttributeContentAll(),
						PriceContentRespectingFilter()
					),
					FacetSummary()
				)
			)
		);
	}
);

PaginatedList<ISealedEntity> paginatedList = (PaginatedList<ISealedEntity>) response.RecordPage;
IList<ISealedEntity> entities = paginatedList.Data;
int pageNumber = paginatedList.PageNumber;
int pageSize = paginatedList.PageSize;
int totalRecordCount = paginatedList.TotalRecordCount;
FacetSummary facetSummary = response.GetExtraResult<FacetSummary>();
