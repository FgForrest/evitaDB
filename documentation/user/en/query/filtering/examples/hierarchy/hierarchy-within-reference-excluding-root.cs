EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		HierarchyWithin(
        			"categories",
        			AttributeEquals("code", "keyboards"),
        			ExcludingRoot()
        		)
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code")
        		)
        	)
        )
	)
);