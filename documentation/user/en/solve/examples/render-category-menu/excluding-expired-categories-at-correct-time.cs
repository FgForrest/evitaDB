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
        					AttributeInRange(
        						"validity", 
        						DateTimeOffset.Parse("2023-12-05T12:00:00Z", DateTimeFormatInfo.InvariantInfo)
        					)
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