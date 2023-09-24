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

package com.tang.intellij.lua.refactoring.rename

import com.intellij.codeInsight.CodeInsightUtilCore
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Pass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementsAroundOffsetUp
import com.intellij.refactoring.IntroduceTargetChooser
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.introduce.inplace.InplaceVariableIntroducer
import com.intellij.refactoring.introduce.inplace.OccurrencesChooser
import com.intellij.refactoring.suggested.endOffset
import com.tang.intellij.lua.lang.LuaLanguage
import com.tang.intellij.lua.psi.*
import com.tang.intellij.lua.refactoring.LuaRefactoringUtil
import com.tang.intellij.lua.search.SearchContext
import com.tang.intellij.lua.ty.ITyFunction
import com.tang.intellij.lua.ty.TyFunction
import com.tang.intellij.lua.ty.TyPsiFunction


/**
 *
 * Created by tangzx on 2017/4/25.
 */
class LuaIntroduceVarHandler : RefactoringActionHandler {

    internal inner class IntroduceOperation(
            val element: PsiElement,
            val project: Project,
            val editor: Editor,
            val file: PsiFile,
            var occurrences: List<PsiElement>
    ) {
        var isReplaceAll: Boolean = false
        var name = "var"
        var newOccurrences = mutableListOf<PsiElement>()
        var newNameElement: LuaLocalDef? = null
        var position: PsiElement? = null
    }

    override fun invoke(project: Project, editor: Editor, psiFile: PsiFile, dataContext: DataContext) {
        val selectionModel = editor.getSelectionModel();

        // 有选择优先判断选择项
        if (selectionModel.hasSelection()) {
            val expression = findExpressionInRange(psiFile, selectionModel.selectionStart, selectionModel.selectionEnd)
            if (expression!=null)
            {
                invoke(project, editor, expression)
                return
            }
        }

        // 没有或者不合法，显示提示项
        var offset = editor.caretModel.offset;
        if (offset >= psiFile.endOffset) {
            offset = psiFile.endOffset - 1
        }
        val expressions: ArrayList<LuaExpression<*>> = ArrayList();
        if (psiFile is LuaPsiFile) {
            val iterator = psiFile.elementsAroundOffsetUp(offset)
            var context = SearchContext.get(project)
            while (iterator.hasNext()) {
                val next = iterator.next().first
                if (next is LuaFuncBodyOwner<*>)
                {
                    break
                }
                if (next is LuaExpression<*>) {
                    // 跳过调用的函数成员变量
                    if (next is LuaIndexExpr && next.parent is LuaCallExpr)
                    {
                        continue
                    }
                    expressions.add(next)
                }
            }
        }

        val size = expressions.size
        when (size) {
            0 -> {
                return
            }
            1 -> {
                invoke(project, editor, expressions[0])
            }
            else -> {
                IntroduceTargetChooser.showChooser(
                    editor, expressions,
                    object : Pass<Any?>() {
                        override fun pass(t: Any?) {
                            if (t is LuaExpression<*>) {
                                invoke(project, editor, t)
                            }
                        }
                    },
                    { expr -> expr.text }
                )
            }
        }

    }

    fun findExpressionInRange(file: PsiFile, startOffset: Int, endOffset: Int): LuaExpression<*>?
    {
        val expression = CodeInsightUtilCore.findElementInRange(file, startOffset, endOffset, LuaExpression::class.java, LuaLanguage.INSTANCE)
        if (expression is LuaExpression)
        {
            return expression
        }
        return null
    }

    override fun invoke(project: Project, psiElements: Array<PsiElement>, dataContext: DataContext) {

    }

    operator fun invoke(project: Project, editor: Editor, expression: LuaExpression<*>?) {
        if (expression == null)
        {
            return
        }
        val occurrences = getOccurrences(expression)
        val operation = IntroduceOperation(expression, project, editor, expression.containingFile, occurrences)
        OccurrencesChooser.simpleChooser<PsiElement>(editor).showChooser(expression, occurrences, object : Pass<OccurrencesChooser.ReplaceChoice>() {
            override fun pass(choice: OccurrencesChooser.ReplaceChoice) {
                operation.isReplaceAll = choice == OccurrencesChooser.ReplaceChoice.ALL
                WriteCommandAction.runWriteCommandAction(operation.project) { performReplace(operation) }
                performInplaceIntroduce(operation)
            }
        })
    }

    private fun getOccurrences(expression: LuaExpression<*>): List<PsiElement> {
        return LuaRefactoringUtil.getOccurrences(expression, expression.containingFile)
    }

    private fun findAnchor(occurrences: List<PsiElement>?): PsiElement? {
        var anchor = occurrences!![0]
        next@ do {
            val statement = PsiTreeUtil.getParentOfType(anchor, LuaStatement::class.java)
            if (statement != null) {
                val parent = statement.parent
                for (element in occurrences) {
                    if (!PsiTreeUtil.isAncestor(parent, element, true)) {
                        anchor = statement
                        continue@next
                    }
                }
            }
            return statement
        } while (true)
    }

    private fun isInline(commonParent: PsiElement, operation: IntroduceOperation): Boolean {
        var parent = commonParent
        if (parent === operation.element)
            parent = operation.element.parent
        return parent is LuaExprStat && (!operation.isReplaceAll || operation.occurrences.size == 1)
    }

    private fun performReplace(operation: IntroduceOperation) {
        if (!operation.isReplaceAll)
            operation.occurrences = listOf(operation.element)

        var commonParent = PsiTreeUtil.findCommonParent(operation.occurrences)
        if (commonParent != null) {
            var element = operation.element
            var localDefStat: PsiElement = LuaElementFactory.createWith(operation.project, "local var = " + element.text)
            var needSetPosition = true
            val inline = isInline(commonParent, operation)
            if (inline) {
                if (element is LuaCallExpr && element.parent is LuaExprStat)
                    element = element.parent
                localDefStat = element.replace(localDefStat)
                operation.position = localDefStat
            } else {
                val anchor = findAnchor(operation.occurrences)

                if (anchor == operation.occurrences.first().parent && anchor is LuaExprStat)
                {
                    localDefStat = element.replace(localDefStat)
                    operation.position = localDefStat
                    needSetPosition = false
                }
                else
                {
                    commonParent = anchor?.parent
                    localDefStat = commonParent!!.addBefore(localDefStat, anchor)
                    commonParent.addAfter(LuaElementFactory.newLine(operation.project), localDefStat)
                }

                operation.occurrences.forEach { occ->
                    var identifier = LuaElementFactory.createName(operation.project, operation.name)
                    identifier = occ.replace(identifier)
                    operation.newOccurrences.add(identifier)
                    if (occ == operation.element && needSetPosition)
                        operation.position = identifier
                }
            }

            val localDef = CodeInsightUtilCore.forcePsiPostprocessAndRestoreElement(localDefStat)?.let {
                PsiTreeUtil.findChildOfType(it, LuaLocalDef::class.java)
            }

            if (localDef != null)
                operation.editor.caretModel.moveToOffset(localDef.textOffset)
            operation.newNameElement = localDef
        }
    }

    private fun performInplaceIntroduce(operation: IntroduceOperation) {
        LuaIntroduce(operation).performInplaceRefactoring(null)
    }

    private inner class LuaIntroduce(val operation: IntroduceOperation) : InplaceVariableIntroducer<PsiElement>(
            operation.newNameElement,
            operation.editor,
            operation.project,
            "Introduce Variable",
            operation.newOccurrences.toTypedArray(),
            null // 避免因为在闭包里，导致报错
    ) {

        init { // 补充闭包对应处理逻辑
            myExpr = operation.position
            myExprMarker = if (myExpr != null) createMarker(myExpr) else null
        }

        override fun checkLocalScope(): PsiElement? {
            val currentFile = PsiDocumentManager.getInstance(this.myProject).getPsiFile(this.myEditor.document)
            return currentFile ?: super.checkLocalScope()
        }

        override fun moveOffsetAfter(success: Boolean) {
            val position = exprMarker
            if (position != null)
                operation.editor.caretModel.moveToOffset(position.endOffset)
            super.moveOffsetAfter(success)
        }
    }
}
