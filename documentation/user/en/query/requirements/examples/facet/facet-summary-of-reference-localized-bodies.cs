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
        		EntityLocaleEquals(CultureInfo.GetCultureInfo("en")),
        		AttributeEquals("status", "ACTIVE")
        	),
        	Require(
        		FacetSummaryOfReference(
        			"parameterValues",
        			Counts,
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