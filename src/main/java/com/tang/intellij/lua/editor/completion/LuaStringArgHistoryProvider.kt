/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tang.intellij.lua.editor.completion

import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.suggested.endOffset
import com.tang.intellij.lua.lang.LuaIcons
import com.tang.intellij.lua.psi.*
import com.tang.intellij.lua.stubs.index.LuaStringArgIndex

class LuaStringArgHistoryProvider : LuaCompletionProvider() {
    companion object {
        val STRING_ARG = PlatformPatterns.psiElement(LuaTypes.STRING)
            .withParent(
                PlatformPatterns.psiElement(LuaTypes.LITERAL_EXPR).withParent(LuaArgs::class.java)
            )
    }

    override fun addCompletions(session: CompletionSession) {
        val argExpr = session.parameters.position.parent as? LuaExpression<*> ?: return
        val callExpr = PsiTreeUtil.getParentOfType(session.parameters.position, LuaCallExpr::class.java) ?: return
        val fnName = callExpr.expression.name ?: return
        var index = callExpr.argList.indexOf(argExpr)
        index = if (callExpr.isMethodColonCall) index + 1 else index
        LuaStringArgIndex.processValues(fnName, GlobalSearchScope.projectScope(callExpr.project)) { arg ->
            if (arg.argIndex == index) {
                session.resultSet.addElement(
                    LookupElementBuilder.create(arg.argString)
                        .withIcon(LuaIcons.STRING_ARG_HISTORY)
                        .withTypeText("History", true)
                        .withInsertHandler(OverrideInsertHandler())
                )
            }
            true
        }
    }

    internal class OverrideInsertHandler() : InsertHandler<LookupElement> {
        override fun handleInsert(insertionContext: InsertionContext, lookupElement: LookupElement) {
            val startOffset = insertionContext.startOffset
            val element = insertionContext.file.findElementAt(startOffset)
            val editor = insertionContext.editor
            if (element != null) {
                val endOffset = element.endOffset
                editor.caretModel.moveToOffset(endOffset)
            }
        }
    }
}
