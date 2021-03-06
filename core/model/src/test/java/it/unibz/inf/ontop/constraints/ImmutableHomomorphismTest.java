package it.unibz.inf.ontop.constraints;

import com.google.common.collect.ImmutableList;
import it.unibz.inf.ontop.dbschema.BasicDBMetadata;
import it.unibz.inf.ontop.dbschema.DatabaseRelationDefinition;
import it.unibz.inf.ontop.dbschema.QuotedIDFactory;
import it.unibz.inf.ontop.model.atom.AtomPredicate;
import it.unibz.inf.ontop.model.atom.DataAtom;
import it.unibz.inf.ontop.model.type.DBTermType;
import it.unibz.inf.ontop.model.vocabulary.XSD;
import org.junit.Test;

import static it.unibz.inf.ontop.OntopModelTestingTools.*;
import static org.junit.Assert.*;

@SuppressWarnings("ConstantConditions")
public class ImmutableHomomorphismTest {

    @Test
    public void test_empty_from() {
        ImmutableHomomorphism h = ImmutableHomomorphism.builder().extend(TERM_FACTORY.getVariable("x"),
                TERM_FACTORY.getRDFLiteralConstant("a", XSD.STRING)).build();
        ImmutableHomomorphismIterator<AtomPredicate> i = new ImmutableHomomorphismIterator<>(h, ImmutableList.of(), ImmutableList.of());
        assertTrue(i.hasNext());
        assertTrue(i.hasNext());
        assertEquals(h, i.next());
        assertFalse(i.hasNext());
        assertFalse(i.hasNext());
    }

    @Test
    public void test_backtrack() {
        ImmutableHomomorphism h = ImmutableHomomorphism.builder().build();
        ImmutableList<DataAtom<AtomPredicate>> from = ImmutableList.of(
                ATOM_FACTORY.getIntensionalTripleAtom(TERM_FACTORY.getVariable("x"), RDF_FACTORY.createIRI("http://P"),  TERM_FACTORY.getVariable("y")),
                ATOM_FACTORY.getIntensionalTripleAtom(TERM_FACTORY.getVariable("y"), RDF_FACTORY.createIRI("http://Q"), TERM_FACTORY.getVariable("z")),
                ATOM_FACTORY.getIntensionalTripleAtom(TERM_FACTORY.getVariable("z"), RDF_FACTORY.createIRI("http://R"), TERM_FACTORY.getVariable("w")));
        ImmutableList<DataAtom<AtomPredicate>> to = ImmutableList.of(
                ATOM_FACTORY.getIntensionalTripleAtom(TERM_FACTORY.getRDFLiteralConstant("a", XSD.STRING),
                        RDF_FACTORY.createIRI("http://P"),  TERM_FACTORY.getRDFLiteralConstant("b", XSD.STRING)),
                ATOM_FACTORY.getIntensionalTripleAtom(TERM_FACTORY.getRDFLiteralConstant("b", XSD.STRING),
                        RDF_FACTORY.createIRI("http://Q"), TERM_FACTORY.getRDFLiteralConstant("c", XSD.STRING)),
                ATOM_FACTORY.getIntensionalTripleAtom(TERM_FACTORY.getRDFLiteralConstant("a0", XSD.STRING),
                        RDF_FACTORY.createIRI("http://P"),  TERM_FACTORY.getRDFLiteralConstant("b0", XSD.STRING)),
                ATOM_FACTORY.getIntensionalTripleAtom(TERM_FACTORY.getRDFLiteralConstant("b0", XSD.STRING),
                        RDF_FACTORY.createIRI("http://Q"), TERM_FACTORY.getRDFLiteralConstant("c0", XSD.STRING)),
                ATOM_FACTORY.getIntensionalTripleAtom(TERM_FACTORY.getRDFLiteralConstant("c0", XSD.STRING),
                        RDF_FACTORY.createIRI("http://R"), TERM_FACTORY.getRDFLiteralConstant("d0", XSD.STRING)));

        ImmutableHomomorphismIterator<AtomPredicate> i = new ImmutableHomomorphismIterator<>(h, from, to);
        assertTrue(i.hasNext());
        assertTrue(i.hasNext());
        assertEquals(ImmutableHomomorphism.builder()
                .extend(TERM_FACTORY.getVariable("x"), TERM_FACTORY.getRDFLiteralConstant("a0", XSD.STRING))
                .extend(TERM_FACTORY.getVariable("y"), TERM_FACTORY.getRDFLiteralConstant("b0", XSD.STRING))
                .extend(TERM_FACTORY.getVariable("z"), TERM_FACTORY.getRDFLiteralConstant("c0", XSD.STRING))
                .extend(TERM_FACTORY.getVariable("w"), TERM_FACTORY.getRDFLiteralConstant("d0", XSD.STRING))
                .build(), i.next());
        assertFalse(i.hasNext());
        assertFalse(i.hasNext());
    }

    @Test
    public void test_multiple() {
        ImmutableHomomorphism h = ImmutableHomomorphism.builder().build();
        ImmutableList<DataAtom<AtomPredicate>> from = ImmutableList.of(
                ATOM_FACTORY.getIntensionalTripleAtom(TERM_FACTORY.getVariable("x"), RDF_FACTORY.createIRI("http://P"),  TERM_FACTORY.getVariable("y")),
                ATOM_FACTORY.getIntensionalTripleAtom(TERM_FACTORY.getVariable("y"), RDF_FACTORY.createIRI("http://Q"), TERM_FACTORY.getVariable("z")),
                ATOM_FACTORY.getIntensionalTripleAtom(TERM_FACTORY.getVariable("z"), RDF_FACTORY.createIRI("http://R"), TERM_FACTORY.getVariable("w")));
        ImmutableList<DataAtom<AtomPredicate>> to = ImmutableList.of(
                ATOM_FACTORY.getIntensionalTripleAtom(TERM_FACTORY.getRDFLiteralConstant("a", XSD.STRING),
                        RDF_FACTORY.createIRI("http://P"),  TERM_FACTORY.getRDFLiteralConstant("b", XSD.STRING)),
                ATOM_FACTORY.getIntensionalTripleAtom(TERM_FACTORY.getRDFLiteralConstant("b", XSD.STRING),
                        RDF_FACTORY.createIRI("http://Q"), TERM_FACTORY.getRDFLiteralConstant("c", XSD.STRING)),
                ATOM_FACTORY.getIntensionalTripleAtom(TERM_FACTORY.getRDFLiteralConstant("c", XSD.STRING),
                        RDF_FACTORY.createIRI("http://R"), TERM_FACTORY.getRDFLiteralConstant("d", XSD.STRING)),
                ATOM_FACTORY.getIntensionalTripleAtom(TERM_FACTORY.getRDFLiteralConstant("a", XSD.STRING),
                        RDF_FACTORY.createIRI("http://P"),  TERM_FACTORY.getRDFLiteralConstant("b0", XSD.STRING)),
                ATOM_FACTORY.getIntensionalTripleAtom(TERM_FACTORY.getRDFLiteralConstant("b0", XSD.STRING),
                        RDF_FACTORY.createIRI("http://Q"), TERM_FACTORY.getRDFLiteralConstant("c0", XSD.STRING)),
                ATOM_FACTORY.getIntensionalTripleAtom(TERM_FACTORY.getRDFLiteralConstant("c0", XSD.STRING),
                        RDF_FACTORY.createIRI("http://R"), TERM_FACTORY.getRDFLiteralConstant("d0", XSD.STRING)));

        ImmutableHomomorphismIterator<AtomPredicate> i = new ImmutableHomomorphismIterator<>(h, from, to);
        assertTrue(i.hasNext());
        assertTrue(i.hasNext());
        assertEquals(ImmutableHomomorphism.builder()
                .extend(TERM_FACTORY.getVariable("x"), TERM_FACTORY.getRDFLiteralConstant("a", XSD.STRING))
                .extend(TERM_FACTORY.getVariable("y"), TERM_FACTORY.getRDFLiteralConstant("b", XSD.STRING))
                .extend(TERM_FACTORY.getVariable("z"), TERM_FACTORY.getRDFLiteralConstant("c", XSD.STRING))
                .extend(TERM_FACTORY.getVariable("w"), TERM_FACTORY.getRDFLiteralConstant("d", XSD.STRING))
                .build(), i.next());
        assertTrue(i.hasNext());
        assertTrue(i.hasNext());
        assertEquals(ImmutableHomomorphism.builder()
                .extend(TERM_FACTORY.getVariable("x"), TERM_FACTORY.getRDFLiteralConstant("a", XSD.STRING))
                .extend(TERM_FACTORY.getVariable("y"), TERM_FACTORY.getRDFLiteralConstant("b0", XSD.STRING))
                .extend(TERM_FACTORY.getVariable("z"), TERM_FACTORY.getRDFLiteralConstant("c0", XSD.STRING))
                .extend(TERM_FACTORY.getVariable("w"), TERM_FACTORY.getRDFLiteralConstant("d0", XSD.STRING))
                .build(), i.next());
        assertFalse(i.hasNext());
        assertFalse(i.hasNext());
    }

    @Test
    public void test_negative() {
        BasicDBMetadata metadata = createDummyMetadata();
        QuotedIDFactory idFactory = metadata.getQuotedIDFactory();
        DBTermType stringType = TYPE_FACTORY.getDBTypeFactory().getDBStringType();

        DatabaseRelationDefinition A = metadata.createDatabaseRelation(idFactory.createRelationID(null, "ADDRESS"));
        A.addAttribute(idFactory.createAttributeID("id"), "varchar", stringType, false);
        A.addAttribute(idFactory.createAttributeID("address"), "varchar", stringType, false);
        DatabaseRelationDefinition S = metadata.createDatabaseRelation(idFactory.createRelationID(null, "STAFF"));
        S.addAttribute(idFactory.createAttributeID("id"), "varchar", stringType, false);
        S.addAttribute(idFactory.createAttributeID("address_id"), "varchar", stringType, false);
        S.addAttribute(idFactory.createAttributeID("store_id"), "varchar", stringType, false);
        DatabaseRelationDefinition T = metadata.createDatabaseRelation(idFactory.createRelationID(null, "STORE"));
        T.addAttribute(idFactory.createAttributeID("id"), "varchar", stringType, false);
        T.addAttribute(idFactory.createAttributeID("staff_id"), "varchar", stringType, false);
        T.addAttribute(idFactory.createAttributeID("address_id"), "varchar", stringType, false);

        ImmutableHomomorphism h = ImmutableHomomorphism.builder().build();
        // ADDRESS(ADDRESS_ID0,ADDRESS3)
        // STAFF(STAFF_ID2,ADDRESS_ID0,STORE_ID2)
        ImmutableList<DataAtom<AtomPredicate>> from = ImmutableList.of(
                ATOM_FACTORY.getDataAtom(A.getAtomPredicate(), TERM_FACTORY.getVariable("ADDRESS_ID0"), TERM_FACTORY.getVariable("ADDRESS3")),
                ATOM_FACTORY.getDataAtom(S.getAtomPredicate(), TERM_FACTORY.getVariable("STAFF_ID2"), TERM_FACTORY.getVariable("ADDRESS_ID0"), TERM_FACTORY.getVariable("STORE_ID2")));
        // STORE(STORE_ID1,STAFF_ID1,ADDRESS_ID0)
        // ADDRESS(ADDRESS_ID0,ADDRESS3)
        // ADDRESS(ADDRESS_ID0,p0)
        // STAFF(STAFF_ID1,p2,p3)
        ImmutableList<DataAtom<AtomPredicate>> to = ImmutableList.of(
                ATOM_FACTORY.getDataAtom(T.getAtomPredicate(), TERM_FACTORY.getVariable("STORE_ID1"),
                        TERM_FACTORY.getVariable("STAFF_ID1"),  TERM_FACTORY.getVariable("ADDRESS_ID0")),
                ATOM_FACTORY.getDataAtom(A.getAtomPredicate(), TERM_FACTORY.getVariable("ADDRESS_ID0"),
                        TERM_FACTORY.getVariable("ADDRESS3")),
                ATOM_FACTORY.getDataAtom(A.getAtomPredicate(), TERM_FACTORY.getVariable("ADDRESS_ID0"),
                        TERM_FACTORY.getVariable("p0")),
                ATOM_FACTORY.getDataAtom(S.getAtomPredicate(), TERM_FACTORY.getVariable("STAFF_ID1"),
                        TERM_FACTORY.getVariable("p2"),  TERM_FACTORY.getVariable("p3")));

        ImmutableHomomorphismIterator<AtomPredicate> i = new ImmutableHomomorphismIterator<>(h, from, to);
        assertFalse(i.hasNext());
    }

}
