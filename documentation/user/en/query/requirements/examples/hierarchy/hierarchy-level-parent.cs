EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		HierarchyWithin(
        			"categories",
        			AttributeEquals("code", "true-wireless")
        		)
        	),
        	Require(
        		HierarchyOfReference(
        			"categories",
        			RemoveEmpty,
        			Parents(
        				"parents",
        				EntityFetch(
        					AttributeContent("code")
        				),
        				StopAt(
        					Level(2)
        				)
        			)
        		)
        	)
        )
	)
);