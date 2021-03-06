/*
// $Id$
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// Copyright (C) 2006-2009 Julian Hyde
// All Rights Reserved.
// You must accept the terms of that agreement to use this software.
*/
package mondrian.olap.fun;

import mondrian.calc.Calc;
import mondrian.calc.ExpCompiler;
import mondrian.calc.HierarchyCalc;
import mondrian.calc.impl.AbstractMemberCalc;
import mondrian.mdx.ResolvedFunCall;
import mondrian.olap.*;
import mondrian.rolap.RolapHierarchy;

import java.util.List;
import java.util.Collections;

/**
 * Definition of the <code>&lt;Hierarchy&gt;.CurrentMember</code> MDX
 * builtin function.
 *
 * @author jhyde
 * @version $Id$
 * @since Mar 23, 2006
 */
public class HierarchyCurrentMemberFunDef extends FunDefBase {
    static final HierarchyCurrentMemberFunDef instance =
            new HierarchyCurrentMemberFunDef();

    private HierarchyCurrentMemberFunDef() {
        super(
            "CurrentMember",
            "Returns the current member along a hierarchy during an iteration.",
            "pmh");
    }

    public Calc compileCall(ResolvedFunCall call, ExpCompiler compiler) {
        final HierarchyCalc hierarchyCalc =
            compiler.compileHierarchy(call.getArg(0));
        final Hierarchy hierarchy = hierarchyCalc.getType().getHierarchy();
        if (hierarchy != null) {
            return new FixedCalcImpl(call, hierarchy);
        } else {
            return new CalcImpl(call, hierarchyCalc);
        }
    }

    /**
     * Compiled implementation of the Hierarchy.CurrentMember function that
     * evaluates the hierarchy expression first.
     */
    public static class CalcImpl extends AbstractMemberCalc {
        private final HierarchyCalc hierarchyCalc;

        public CalcImpl(Exp exp, HierarchyCalc hierarchyCalc) {
            super(exp, new Calc[] {hierarchyCalc});
            this.hierarchyCalc = hierarchyCalc;
        }

        protected String getName() {
            return "CurrentMember";
        }

        public Member evaluateMember(Evaluator evaluator) {
            Hierarchy hierarchy = hierarchyCalc.evaluateHierarchy(evaluator);
            return evaluator.getContext(hierarchy);
        }

        public boolean dependsOn(Hierarchy hierarchy) {
            return hierarchyCalc.getType().usesHierarchy(hierarchy, false);
        }
    }

    /**
     * Compiled implementation of the Hierarchy.CurrentMember function that
     * uses a fixed hierarchy.
     */
    public static class FixedCalcImpl extends AbstractMemberCalc {
        // getContext works faster if we give RolapHierarchy rather than
        // Hierarchy
        private final RolapHierarchy hierarchy;

        public FixedCalcImpl(Exp exp, Hierarchy hierarchy) {
            super(exp, new Calc[] {});
            assert hierarchy != null;
            this.hierarchy = (RolapHierarchy) hierarchy;
        }

        protected String getName() {
            return "CurrentMemberFixed";
        }

        public Member evaluateMember(Evaluator evaluator) {
            return evaluator.getContext(hierarchy);
        }

        public boolean dependsOn(Hierarchy hierarchy) {
            return this.hierarchy == hierarchy;
        }

        public List<Object> getArguments() {
            return Collections.<Object>singletonList(hierarchy);
        }
    }
}

// End HierarchyCurrentMemberFunDef.java
