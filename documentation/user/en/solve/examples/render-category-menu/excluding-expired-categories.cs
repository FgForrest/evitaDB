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
        				Or(
        					AttributeIs("validity", Null),
        					AttributeInRangeNow("validity")
        				)
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