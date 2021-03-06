/*
// $Id$
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// Copyright (C) 2006-2009 Julian Hyde
// All Rights Reserved.
// You must accept the terms of that agreement to use this software.
*/
package mondrian.calc.impl;

import mondrian.calc.Calc;
import mondrian.calc.MemberCalc;
import mondrian.olap.*;
import mondrian.olap.type.ScalarType;
import mondrian.olap.type.Type;

/**
 * Expression which evaluates a few member expressions,
 * sets the dimensional context to the result of those expressions,
 * then yields the value of the current measure in the current
 * dimensional context.
 *
 * <p>The evaluator's context is preserved.
 *
 * <p>Note that a MemberValueCalc with 0 member expressions is equivalent to a
 * {@link mondrian.calc.impl.ValueCalc}; see also {@link mondrian.calc.impl.TupleValueCalc}.
 *
 * @author jhyde
 * @version $Id$
 * @since Sep 27, 2005
 */
public class MemberValueCalc extends GenericCalc {
    private final MemberCalc[] memberCalcs;
    private final Member[] savedMembers;

    public MemberValueCalc(Exp exp, MemberCalc[] memberCalcs) {
        super(exp);
        final Type type = exp.getType();
        assert type instanceof ScalarType : exp;
        this.memberCalcs = memberCalcs;
        this.savedMembers = new Member[memberCalcs.length];
    }

    public Object evaluate(Evaluator evaluator) {
        Member[] members = new Member[memberCalcs.length];
        for (int i = 0; i < memberCalcs.length; i++) {
            MemberCalc memberCalc = memberCalcs[i];
            final Member member = memberCalc.evaluateMember(evaluator);
            if (member == null
                || member.isNull())
            {
                // This method needs to leave the evaluator in the same state
                // it found it.
                for (int j = 0; j < i; j++) {
                    evaluator.setContext(savedMembers[j]);
                }
                return null;
            }
            savedMembers[i] = evaluator.setContext(member);
            members[i] = member;
        }
        final boolean needToReturnNull =
            evaluator.needToReturnNullForUnrelatedDimension(members);
        if (needToReturnNull) {
            evaluator.setContext(savedMembers);
            return null;
        }

        final Object result = evaluator.evaluateCurrent();
        evaluator.setContext(savedMembers);
        return result;
    }

    public Calc[] getCalcs() {
        return memberCalcs;
    }

    public boolean dependsOn(Hierarchy hierarchy) {
        if (super.dependsOn(hierarchy)) {
            return true;
        }
        for (MemberCalc memberCalc : memberCalcs) {
            // If the expression definitely includes the dimension (in this
            // case, that means it is a member of that dimension) then we
            // do not depend on the dimension. For example, the scalar value of
            //   [Store].[USA]
            // does not depend on [Store].
            //
            // If the dimensionality of the expression is unknown, then the
            // expression MIGHT include the dimension, so to be safe we have to
            // say that it depends on the given dimension. For example,
            //   Dimensions(3).CurrentMember.Parent
            // may depend on [Store].
            if (memberCalc.getType().usesHierarchy(hierarchy, true)) {
                return false;
            }
        }
        return true;
    }
}

// End MemberValueCalc.java
