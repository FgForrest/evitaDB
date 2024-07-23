EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		HierarchyWithin(
        			"categories",
        			AttributeEquals("code", "accessories"),
        			Having(
        				AttributeEquals("status", "ACTIVE")
        			)
        		)
        	),
        	Require(
        		HierarchyOfReference(
        			"categories",
        			RemoveEmpty,
        			FromRoot(
        				"topLevel",
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