/*
// $Id$
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// Copyright (C) 2006-2007 Julian Hyde
// All Rights Reserved.
// You must accept the terms of that agreement to use this software.
*/
package mondrian.olap.fun;

import mondrian.calc.Calc;
import mondrian.calc.ExpCompiler;
import mondrian.calc.LevelCalc;
import mondrian.calc.impl.AbstractHierarchyCalc;
import mondrian.mdx.ResolvedFunCall;
import mondrian.olap.Exp;
import mondrian.olap.Hierarchy;
import mondrian.olap.Evaluator;
import mondrian.olap.Level;

/**
 * Definition of the <code>&lt;Level&gt;.Hierarchy</code> MDX builtin function.
 *
 * @author jhyde
 * @version $Id$
 * @since Mar 23, 2006
 */
public class LevelHierarchyFunDef extends FunDefBase {
    static final LevelHierarchyFunDef instance = new LevelHierarchyFunDef();

    private LevelHierarchyFunDef() {
        super("Hierarchy", "Returns a level's hierarchy.", "phl");
    }

    public Calc compileCall(ResolvedFunCall call, ExpCompiler compiler) {
        final LevelCalc levelCalc =
                compiler.compileLevel(call.getArg(0));
        return new CalcImpl(call, levelCalc);
    }

    public static class CalcImpl extends AbstractHierarchyCalc {
        private final LevelCalc levelCalc;

        public CalcImpl(Exp exp, LevelCalc levelCalc) {
            super(exp, new Calc[] {levelCalc});
            this.levelCalc = levelCalc;
        }

        public Hierarchy evaluateHierarchy(Evaluator evaluator) {
            Level level = levelCalc.evaluateLevel(evaluator);
            return level.getHierarchy();
        }
    }
}

// End LevelHierarchyFunDef.java
