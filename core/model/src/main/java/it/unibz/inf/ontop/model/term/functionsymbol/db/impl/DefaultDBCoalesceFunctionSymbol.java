package it.unibz.inf.ontop.model.term.functionsymbol.db.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import it.unibz.inf.ontop.exception.MinorOntopInternalBugException;
import it.unibz.inf.ontop.iq.node.VariableNullability;
import it.unibz.inf.ontop.model.term.*;
import it.unibz.inf.ontop.model.term.functionsymbol.db.DBFunctionSymbolSerializer;
import it.unibz.inf.ontop.model.term.functionsymbol.db.DBIfElseNullFunctionSymbol;
import it.unibz.inf.ontop.model.term.functionsymbol.db.DBIsNullOrNotFunctionSymbol;
import it.unibz.inf.ontop.model.type.DBTermType;
import it.unibz.inf.ontop.substitution.ProtoSubstitution;
import it.unibz.inf.ontop.utils.ImmutableCollectors;

import java.util.Optional;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class DefaultDBCoalesceFunctionSymbol extends AbstractArgDependentTypedDBFunctionSymbol {

    private final DBFunctionSymbolSerializer serializer;

    protected DefaultDBCoalesceFunctionSymbol(String nameInDialect, int arity, DBTermType rootDBTermType,
                                              DBFunctionSymbolSerializer serializer) {
        super(nameInDialect + arity, IntStream.range(0, arity)
                .boxed()
                .map(i -> rootDBTermType)
                .collect(ImmutableCollectors.toList()));
        this.serializer = serializer;
    }

    @Override
    protected boolean tolerateNulls() {
        return true;
    }

    @Override
    protected boolean mayReturnNullWithoutNullArguments() {
        return false;
    }

    @Override
    protected Stream<? extends ImmutableTerm> extractPossibleValues(ImmutableList<? extends ImmutableTerm> terms) {
        return terms.stream();
    }

    @Override
    public boolean isPreferringToBePostProcessedOverBeingBlocked() {
        return false;
    }

    @Override
    public String getNativeDBString(ImmutableList<? extends ImmutableTerm> terms, Function<ImmutableTerm, String> termConverter, TermFactory termFactory) {
        return serializer.getNativeDBString(terms, termConverter, termFactory);
    }

    @Override
    public boolean isAlwaysInjectiveInTheAbsenceOfNonInjectiveFunctionalTerms() {
        return false;
    }

    @Override
    public boolean canBePostProcessed(ImmutableList<? extends ImmutableTerm> arguments) {
        return true;
    }

    @Override
    protected ImmutableTerm buildTermAfterEvaluation(ImmutableList<ImmutableTerm> newTerms, TermFactory termFactory,
                                                     VariableNullability variableNullability) {
        ImmutableList<ImmutableTerm> remainingTerms = newTerms.stream()
                .filter(t -> !t.isNull())
                .collect(ImmutableCollectors.toList());

        switch (remainingTerms.size()) {
            case 0:
                return termFactory.getNullConstant();
            case 1:
                return remainingTerms.get(0);
            default:
        }

        ImmutableTerm firstRemainingTerm = remainingTerms.get(0);

        ImmutableList<ImmutableTerm> termsAfterNullConstraintPropagation = propagateNullConstraints(firstRemainingTerm,
                remainingTerms.subList(1, remainingTerms.size()), variableNullability, termFactory)
                .collect(ImmutableCollectors.toList());

        ImmutableList<ImmutableTerm> simplifiedTerms = mergeIfElseNulls(termsAfterNullConstraintPropagation,
                variableNullability, termFactory);

        switch (simplifiedTerms.size()) {
            case 0:
                return termFactory.getNullConstant();
            case 1:
                return simplifiedTerms.get(0);
            default:
                return termFactory.getDBCoalesce(simplifiedTerms);
        }
    }

    /**
     * The following terms are ONLY considered the previous term evaluates to NULL.
     * TODO: explain further how we exploit this observation
     *
     * Recursive
     *
     */
    private Stream<ImmutableTerm> propagateNullConstraints(ImmutableTerm term,
                                                           ImmutableList<ImmutableTerm> followingTerms,
                                                           VariableNullability variableNullability,
                                                           TermFactory termFactory) {
        if (followingTerms.isEmpty())
            return Stream.of(term);

        IncrementalEvaluation evaluation = term.evaluateIsNotNull(variableNullability);

        switch (evaluation.getStatus()) {
            case IS_NULL:
                throw new MinorOntopInternalBugException("evaluateIsNotNull can evaluate to NULL");
                // Always null -> skip it
            case IS_FALSE:
                // (Tail)-recursive call
                return propagateNullConstraints(followingTerms.get(0),
                        followingTerms.subList(1, followingTerms.size()),
                        variableNullability, termFactory);
                // Never null -> the following terms will never be considered
            case IS_TRUE:
                return Stream.of(term);
            default:
                // continue
        }

        Optional<Variable> variableToNullify = evaluation.getNewExpression()
                // New expression
                .map(Optional::of)
                .map(oe -> oe
                        .filter(e -> (e.getFunctionSymbol() instanceof DBIsNullOrNotFunctionSymbol)
                                && !((DBIsNullOrNotFunctionSymbol) e.getFunctionSymbol()).isTrueWhenNull())
                        // We extract the sub-term of the IS_NOT_NULL expression
                        .map(e -> e.getTerm(0)))
                // Same expression (IS_NOT_NULL(term))
                .orElseGet(() -> Optional.of(term))
                // Currently we only consider the case where the sub-term is a variable
                // TODO: generalize it
                .filter(t -> t instanceof Variable)
                .map(t -> (Variable) t);

        Optional<ProtoSubstitution<Constant>> nullifyingSubstitution = variableToNullify
                .map(v -> termFactory.getProtoSubstitution(ImmutableMap.of(v, termFactory.getNullConstant())));

        ImmutableList<ImmutableTerm> substitutedFollowingTerms = nullifyingSubstitution
                .map(s ->followingTerms.stream()
                        .map(s::apply)
                        .map(t -> t.simplify(variableNullability))
                        .collect(ImmutableCollectors.toList()))
                .orElse(followingTerms);

        return Stream.concat(
                Stream.of(term),
                // (Non-tail) recursive call
                propagateNullConstraints(substitutedFollowingTerms.get(0),
                        substitutedFollowingTerms.subList(1, substitutedFollowingTerms.size()),
                        variableNullability, termFactory));
    }

    /**
     * TODO: generalize to arity > 2
     */
    private ImmutableList<ImmutableTerm> mergeIfElseNulls(ImmutableList<ImmutableTerm> terms,
                                                   VariableNullability variableNullability, TermFactory termFactory) {
        if (terms.size() != 2)
            return terms;

        ImmutableTerm firstTerm = terms.get(0);
        ImmutableTerm secondTerm = terms.get(1);
        if ((firstTerm instanceof ImmutableFunctionalTerm) &&
                ((ImmutableFunctionalTerm) firstTerm).getFunctionSymbol() instanceof DBIfElseNullFunctionSymbol) {
            ImmutableFunctionalTerm firstFunctionalTerm = (ImmutableFunctionalTerm) firstTerm;
            ImmutableExpression firstCondition = (ImmutableExpression) firstFunctionalTerm.getTerm(0);
            ImmutableTerm thenValueFirstTerm = firstFunctionalTerm.getTerm(1);

            if ((secondTerm instanceof ImmutableFunctionalTerm) &&
                    ((ImmutableFunctionalTerm) secondTerm).getFunctionSymbol() instanceof DBIfElseNullFunctionSymbol) {
                ImmutableFunctionalTerm secondFunctionalTerm = (ImmutableFunctionalTerm) secondTerm;
                ImmutableExpression secondCondition = (ImmutableExpression) secondFunctionalTerm.getTerm(0);
                ImmutableTerm thenValueSecondTerm = secondFunctionalTerm.getTerm(1);

                // Same "then" value
                if (thenValueFirstTerm.equals(thenValueSecondTerm)) {
                    ImmutableTerm mergedTerm = termFactory.getIfElseNull(
                            termFactory.getDisjunction(firstCondition, secondCondition),
                            thenValueFirstTerm)
                            .simplify(variableNullability);

                    return ImmutableList.of(mergedTerm);
                }
                // The first "then" is never null
                else if (!thenValueFirstTerm.isNullable(variableNullability.getNullableVariables())) {
                    ImmutableTerm mergedTerm = termFactory.getIfElseNull(
                            termFactory.getDisjunction(firstCondition, secondCondition),
                            // Uses a IF-THEN-ELSE
                            termFactory.getIfThenElse(firstCondition, thenValueFirstTerm, thenValueSecondTerm))
                            .simplify(variableNullability);

                    return ImmutableList.of(mergedTerm);
                }
                // Otherwise would be a IF-ELSE-IF-ELSE-NULL which is not interesting
                else
                    return terms;
            }
            // The first "then" is never null
            else if (!thenValueFirstTerm.isNullable(variableNullability.getNullableVariables())) {
                ImmutableTerm mergedTerm =
                        // Uses a IF-THEN-ELSE
                        termFactory.getIfThenElse(firstCondition, thenValueFirstTerm, secondTerm)
                        .simplify(variableNullability);

                return ImmutableList.of(mergedTerm);
            }
            else
                return terms;
        }
        else
            return terms;
    }

    @Override
    public IncrementalEvaluation evaluateIsNotNull(ImmutableList<? extends ImmutableTerm> terms, TermFactory termFactory,
                                                   VariableNullability variableNullability) {
        Optional<ImmutableExpression> disjunction = termFactory.getDisjunction(terms.stream()
                .map(termFactory::getDBIsNotNull));

        return disjunction
                .map(IncrementalEvaluation::declareSimplifiedExpression)
                .orElseGet(IncrementalEvaluation::declareIsFalse);
    }
}
