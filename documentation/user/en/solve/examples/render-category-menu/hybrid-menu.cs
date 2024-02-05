EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		HierarchyWithin(
        			"categories",
        			AttributeEquals("code", "over-ear")
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
        					Level(1)
        				)
        			),
        			Siblings(
        				"siblings",
        				EntityFetch(
        					AttributeContent("code")
        				),
        				StopAt(
        					Distance(1)
        				)
        			),
        			Parents(
        				"parents",
        				EntityFetch(
        					AttributeContent("code")
        				)
        			)
        		)
        	)
        )
	)
);