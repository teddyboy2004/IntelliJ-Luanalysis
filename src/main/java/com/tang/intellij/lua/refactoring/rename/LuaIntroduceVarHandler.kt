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
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Pass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.introduce.inplace.InplaceVariableIntroducer
import com.intellij.refactoring.introduce.inplace.OccurrencesChooser
import com.tang.intellij.lua.psi.*
import com.tang.intellij.lua.refactoring.LuaRefactoringUtil

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
        var caret = dataContext.getData(CommonDataKeys.CARET)
        var offset = caret?.offset
        if (offset == null)
        {
            return
        }
        var data = psiFile.findElementAt(offset)
        if (data !is LuaExpression<*>)
        {
            data = PsiTreeUtil.getParentOfType(data, LuaExpression::class.java)
        }
        if (data == null)
        {
            return
        }
        invoke(project, editor, data as LuaExpression<*>)
    }

    override fun invoke(project: Project, psiElements: Array<PsiElement>, dataContext: DataContext) {

    }

    operator fun invoke(project: Project, editor: Editor, expression: LuaExpression<*>) {
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
                    if (occ == operation.element)
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
            operation.position
    ) {

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
