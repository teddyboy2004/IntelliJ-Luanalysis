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

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.template.Template
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.MacroCallNode
import com.intellij.codeInsight.template.impl.TextExpression
import com.intellij.codeInsight.template.macro.CompleteMacro
import com.intellij.codeInsight.template.macro.CompleteSmartMacro
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.intellij.psi.util.prevLeaf
import com.tang.intellij.lua.codeInsight.template.macro.SuggestTypeMacro
import com.tang.intellij.lua.project.LuaSettings
import com.tang.intellij.lua.psi.LuaClassMethodDefStat
import com.tang.intellij.lua.psi.LuaExpression
import com.tang.intellij.lua.psi.LuaParamInfo
import com.tang.intellij.lua.psi.LuaTypes

abstract class ArgsInsertHandler : InsertHandler<LookupElement> {

    protected abstract val params: Array<out LuaParamInfo>

    protected open val isVarargs = false

    protected open val autoInsertParameters: Boolean = LuaSettings.instance.autoInsertParameters

    private var removeFirstSelf = false

    private var mask = -1

    fun withMask(mask: Int): ArgsInsertHandler {
        this.mask = mask
        return this
    }

    override fun handleInsert(insertionContext: InsertionContext, lookupElement: LookupElement) {
        val startOffset = insertionContext.startOffset
        val element = insertionContext.file.findElementAt(startOffset)
        val editor = insertionContext.editor
        var needAppendPar = true
        //如果后面已经有()
        if (element != null) {
            val ex = editor as EditorEx
            val iterator = ex.highlighter.createIterator(startOffset)
            var prevIteratorEnd = iterator.end
            iterator.advance()
            if (!iterator.atEnd()) {
                var tokenType = iterator.tokenType
                while (tokenType === TokenType.WHITE_SPACE) {
                    iterator.advance()
                    if (iterator.atEnd())
                        break
                    prevIteratorEnd = iterator.end
                    tokenType = iterator.tokenType
                }
                //check : lookup-string<caret>expr()
                if (tokenType === LuaTypes.LPAREN) {
                    needAppendPar = prevIteratorEnd != insertionContext.tailOffset
                }
            }

            // 增加判断函数是否使用:，并且前面使用点自动更换成:, 回车不替换
            if (insertionContext.completionChar != '\n' && lookupElement.psiElement is LuaClassMethodDefStat && (lookupElement.psiElement as LuaClassMethodDefStat).classMethodName.colon != null)
            {
                val isSuperCall = element.prevSibling?.prevLeaf()?.text == "__super"
                val preElementType = element.prevSibling?.elementType
                removeFirstSelf = preElementType == LuaTypes.COLON
                // superCall不使用:，自动替换为.
                if (isSuperCall && preElementType == LuaTypes.COLON)
                {
                    editor.document.replaceString(startOffset - 1, startOffset, ".")
                }
                else if (!isSuperCall && preElementType == LuaTypes.DOT) // 自动替换.为:
                {
                    editor.document.replaceString(startOffset - 1, startOffset, ":")
                    removeFirstSelf = true
                }

            }
        }

        if (needAppendPar) {
            // lookup-string<caret>expr() -> lookup-string(expr())
            val expr = findWarpExpr(insertionContext.file, startOffset)
            if (expr != null) {
                val exprNode = expr.node
                val endOffset = exprNode.startOffset + exprNode.textLength
                if (endOffset > insertionContext.selectionEndOffset) {
                    editor.document.insertString(insertionContext.selectionEndOffset, "(")
                    editor.document.insertString(endOffset + 1, ")")
                    editor.caretModel.moveToOffset(endOffset + 2)
                    return
                }
            }
            appendSignature(insertionContext, editor, element)
        }
    }

    protected open fun appendSignature(insertionContext: InsertionContext, editor: Editor, element: PsiElement?) {
        if (autoInsertParameters && insertionContext.completionChar != '\n') {
            val manager = TemplateManager.getInstance(insertionContext.project)
            val template = createTemplate(manager, params)
            editor.caretModel.moveToOffset(insertionContext.selectionEndOffset)
            manager.startTemplate(editor, template)
        } else {
            editor.document.insertString(insertionContext.selectionEndOffset, "()")
            if (params.isEmpty() && !isVarargs) {
                editor.caretModel.moveToOffset(insertionContext.selectionEndOffset)
            } else {
                editor.caretModel.moveToOffset(insertionContext.selectionEndOffset - 1)
                AutoPopupController.getInstance(insertionContext.project).autoPopupParameterInfo(editor, element)
                val manager = TemplateManager.getInstance(insertionContext.project)
                if (params.isNotEmpty())
                {
                    if (params.size > 1 || params.first().name != "self" || !removeFirstSelf)
                    {
                        val template = manager.createTemplate("", "")
                        template.addVariable(TextExpression(""), true)
                        manager.startTemplate(editor, template)
                    }
                }
            }
        }
        removeFirstSelf = false
    }

    private fun findWarpExpr(file: PsiFile, offset: Int): LuaExpression<*>? {
        var expr = PsiTreeUtil.findElementOfClassAtOffset(file, offset, LuaExpression::class.java, true)
        while (expr != null) {
            val parent = expr.parent
            if (parent is LuaExpression<*> && parent.node.startOffset == offset) {
                expr = parent
            } else break
        }
        return expr
    }

    protected open fun createTemplate(manager: TemplateManager, paramDefList: Array<out LuaParamInfo>): Template {
        val template = manager.createTemplate("", "")
        template.addTextSegment("(")

        var isFirst = true

        for (i in paramDefList.indices) {
            if (mask and (1 shl i) == 0) continue
            val paramDef = paramDefList[i]
            if (isFirst &&  paramDef.name == "self" && removeFirstSelf)
            {
                continue
            }
            if (!isFirst)
                template.addTextSegment(", ")
            template.addVariable(paramDef.name, TextExpression(paramDef.name), TextExpression(paramDef.name), true)
            isFirst = false
        }
        template.addTextSegment(")")
        return template
    }
}
