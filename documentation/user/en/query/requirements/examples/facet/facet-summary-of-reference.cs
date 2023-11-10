EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		HierarchyWithin(
        			"categories",
        			AttributeEquals("code", "e-readers")
        		),
        		AttributeEquals("status", "ACTIVE"),
        		EntityLocaleEquals(CultureInfo.GetCultureInfo("en"))
        	),
        	Require(
        		FacetSummaryOfReference(
        			"brand",
        			Counts,
        			OrderBy(
        				AttributeNatural("name", Asc)
        			),
        			EntityFetch(
        				AttributeContent("name")
        			)
        		),
        		FacetSummaryOfReference(
        			"parameterValues",
        			Counts,
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