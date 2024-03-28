EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		EntityLocaleEquals(CultureInfo.GetCultureInfo("en")),
        		HierarchyWithin(
        			"categories",
        			AttributeEquals("url", "/en/smartwatches")
        		)
        	),
        	Require(
        		FacetSummaryOfReference(
        			"parameterValues",
        			Impact,
        			FilterGroupBy(
        				AttributeEquals("isVisibleInFilter", true)
        			),
        			OrderBy(
        				AttributeNatural("order", Asc)
        			),
        			OrderGroupBy(
        				AttributeNatural("order", Asc)
        			),
        			EntityFetch(
        				AttributeContent("name")
        			),
        			EntityGroupFetch(
        				AttributeContent("name")
        			)
        		)
        	)
        )
	)
);