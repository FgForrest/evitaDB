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
        		AttributeEquals("status", "ACTIVE")
        	),
        	Require(
        		ReferenceSummary(
        			Counts,
        			EntityFetch(
        				AttributeContent("code")
        			),
        			EntityGroupFetch(
        				AttributeContent("code")
        			)
        		)
        	)
        )
	)
);