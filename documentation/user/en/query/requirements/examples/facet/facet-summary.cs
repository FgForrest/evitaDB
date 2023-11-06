EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		HierarchyWithin(
        			"categories",
        			AttributeEquals("code", "accessories")
        		),
        		AttributeEquals("status", "ACTIVE")
        	),
        	Require(
        		FacetSummary(Counts)
        	)
        )
	)
);