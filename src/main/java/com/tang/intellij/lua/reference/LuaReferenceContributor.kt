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

package com.tang.intellij.lua.reference

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.*
import com.intellij.util.IncorrectOperationException
import com.intellij.util.ProcessingContext
import com.intellij.util.applyIf
import com.tang.intellij.lua.project.LuaSettings
import com.tang.intellij.lua.psi.*
import com.tang.intellij.lua.search.SearchContext
import com.tang.intellij.lua.ty.returnStatement
import org.jaxen.expr.LiteralExpr

/**
 * reference contributor
 * Created by tangzx on 2016/12/14.
 */
class LuaReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(psiReferenceRegistrar: PsiReferenceRegistrar) {
        psiReferenceRegistrar.registerReferenceProvider(psiElement().withElementType(LuaTypes.CALL_EXPR), CallExprReferenceProvider())
        psiReferenceRegistrar.registerReferenceProvider(psiElement().withElementType(LuaTypes.INDEX_EXPR), IndexExprReferenceProvider())
        psiReferenceRegistrar.registerReferenceProvider(psiElement().withElementType(LuaTypes.NAME_EXPR), NameReferenceProvider())
        psiReferenceRegistrar.registerReferenceProvider(psiElement().withElementType(LuaTypes.GOTO_STAT), GotoReferenceProvider())
        psiReferenceRegistrar.registerReferenceProvider(psiElement().withElementType(LuaTypes.LITERAL_EXPR), LuaFileStringReferenceProvider())
    }

    internal inner class LuaFileStringReferenceProvider : PsiReferenceProvider() {

        inner class LuaFileStringReference(val expr: LuaLiteralExpr)
            : PsiReferenceBase<LuaLiteralExpr>(expr) {

            val id = expr.psi

            override fun getVariants(): Array<Any> = arrayOf()

            @Throws(IncorrectOperationException::class)
            override fun handleElementRename(newElementName: String): PsiElement {
                return expr
            }

            override fun getRangeInElement(): TextRange {
                val start = id.node.startOffset - myElement.node.startOffset
                return TextRange(start, start + id.textLength)
            }

            override fun isReferenceTo(element: PsiElement): Boolean {
                return false
            }

            override fun resolve(): PsiElement? {
                val filePsi = resolveRequireFile(id.text.replace("\"",""), id.project)
                if (filePsi != null) {
                    val returnStatement = filePsi.returnStatement()

                    if (returnStatement != null && returnStatement.exprList?.expressionList?.size == 1) {
                        val resolvedNameExpr = returnStatement.exprList!!.expressionList.first() as? LuaNameExpr

                        return if (resolvedNameExpr != null) {
                            resolveInFile(SearchContext.get(myElement.project), resolvedNameExpr.name, resolvedNameExpr)
                        } else returnStatement
                    }
                    if (returnStatement != null) {
                        return returnStatement
                    }
                }
                return filePsi
            }
        }

        override fun getReferencesByElement(psiElement: PsiElement, processingContext: ProcessingContext): Array<PsiReference> {
            if (psiElement is LuaLiteralExpr && psiElement.text != null && psiElement.text.contains(Regex("[.\\\\]"))) {
                return arrayOf(LuaFileStringReference(psiElement))
            }
            return PsiReference.EMPTY_ARRAY
        }
    }

    internal inner class GotoReferenceProvider : PsiReferenceProvider() {
        override fun getReferencesByElement(psiElement: PsiElement, processingContext: ProcessingContext): Array<PsiReference> {
            if (psiElement is LuaGotoStat && psiElement.id != null)
                return arrayOf(GotoReference(psiElement))
            return PsiReference.EMPTY_ARRAY
        }
    }

    internal inner class CallExprReferenceProvider : PsiReferenceProvider() {

        override fun getReferencesByElement(psiElement: PsiElement, processingContext: ProcessingContext): Array<PsiReference> {
            val expr = psiElement as LuaCallExpr
            val nameRef = expr.expression
            if (nameRef is LuaNameExpr) {
                if (LuaSettings.isRequireLikeFunctionName(nameRef.getText())) {
                    return arrayOf(LuaRequireReference(expr))
                }
            }
            return PsiReference.EMPTY_ARRAY
        }
    }

    internal inner class IndexExprReferenceProvider : PsiReferenceProvider() {

        override fun getReferencesByElement(psiElement: PsiElement, processingContext: ProcessingContext): Array<PsiReference> {
            val indexExpr = psiElement as LuaIndexExpr
            val id = indexExpr.id
            if (id != null) {
                return arrayOf(LuaIndexReference(indexExpr, id))
            }
            val idExpr = indexExpr.idExpr as? LuaLiteralExpr
            return if (idExpr != null && idExpr.kind == LuaLiteralKind.String) {
                arrayOf(LuaIndexBracketReference(indexExpr, idExpr))
            } else PsiReference.EMPTY_ARRAY
        }
    }

    internal inner class NameReferenceProvider : PsiReferenceProvider() {
        override fun getReferencesByElement(psiElement: PsiElement, processingContext: ProcessingContext): Array<PsiReference> {
            return arrayOf(LuaNameReference(psiElement as LuaNameExpr))
        }
    }
}
