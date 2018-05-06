package it.unibz.inf.ontop.model.term.impl;

import com.google.common.collect.ImmutableList;
import it.unibz.inf.ontop.model.term.functionsymbol.Predicate;
import it.unibz.inf.ontop.model.term.Function;
import it.unibz.inf.ontop.model.term.GroundFunctionalTerm;
import it.unibz.inf.ontop.model.term.GroundTerm;
import it.unibz.inf.ontop.model.term.ImmutableTerm;


import java.util.List;


public class GroundFunctionalTermImpl extends ImmutableFunctionalTermImpl implements GroundFunctionalTerm {

    protected GroundFunctionalTermImpl(ImmutableList<? extends GroundTerm> terms, Predicate functor) {
        super(functor, terms);
    }

    protected GroundFunctionalTermImpl(Predicate functor, List<? extends ImmutableTerm> terms)
            throws GroundTermTools.NonGroundTermException {
        this(GroundTermTools.castIntoGroundTerms(terms), functor);
    }

    public GroundFunctionalTermImpl(Function functionalTermToClone) throws GroundTermTools.NonGroundTermException {
        this(functionalTermToClone.getFunctionSymbol(), GroundTermTools.castIntoGroundTerms(functionalTermToClone.getTerms()));
    }


    @Override
    public ImmutableList<? extends GroundTerm> getTerms() {
        return (ImmutableList<? extends GroundTerm>)super.getTerms();
    }

    @Override
    public boolean isGround() {
        return true;
    }
}
