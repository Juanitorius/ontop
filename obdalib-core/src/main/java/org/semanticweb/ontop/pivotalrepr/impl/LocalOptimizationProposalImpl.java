package org.semanticweb.ontop.pivotalrepr.impl;

import org.semanticweb.ontop.pivotalrepr.IntermediateQuery;
import org.semanticweb.ontop.pivotalrepr.LocalOptimizationProposal;
import org.semanticweb.ontop.pivotalrepr.QueryNode;

/**
 * Abstract class
 */
public abstract class LocalOptimizationProposalImpl implements LocalOptimizationProposal {

    private final IntermediateQuery targetQuery;

    protected LocalOptimizationProposalImpl(IntermediateQuery targetQuery) {
        this.targetQuery = targetQuery;
    }

    protected IntermediateQuery getTargetQuery() {
        return targetQuery;
    }
}
