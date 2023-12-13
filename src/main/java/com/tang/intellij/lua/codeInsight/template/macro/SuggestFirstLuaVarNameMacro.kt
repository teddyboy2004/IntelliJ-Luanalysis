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

package com.tang.intellij.lua.codeInsight.template.macro

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.template.*
import com.intellij.codeInsight.template.impl.MacroCallNode
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.codeInsight.template.impl.VariableNode
import com.intellij.psi.PsiElement
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.intellij.refactoring.suggested.startOffset
import com.tang.intellij.lua.comment.psi.LuaDocTagClass
import com.tang.intellij.lua.editor.LuaNameSuggestionProvider
import com.tang.intellij.lua.lang.type.LuaString
import com.tang.intellij.lua.psi.*
import com.tang.intellij.lua.search.SearchContext
import com.tang.intellij.lua.ty.*

/**
 *
 * Created by TangZX on 2017/4/8.
 */
class SuggestFirstLuaVarNameMacro : Macro() {
    override fun getName(): String {
        return "SuggestFirstLuaVarName"
    }

    override fun getPresentableName(): String {
        return "SuggestFirstLuaVarName(lastID)"
    }

    override fun calculateResult(expressions: Array<Expression>, expressionContext: ExpressionContext): Result? {
        val lastID = getLastID(expressions, expressionContext)
        if (lastID != null) {
            return TextResult(lastID)
        }
        return null
    }

    fun getLastID(expressions: Array<out Expression>, expressionContext: ExpressionContext): String? {
        if (expressions.isEmpty()) {
            return null
        }
        if (expressions.first() !is VariableNode) {
            return null
        }
        if ((expressions.first() as VariableNode).name != "true") {
            return null
        }


        val editor = expressionContext.editor
        if (editor == null) {
            return null
        }
        var template = TemplateManager.getInstance(expressionContext.project).getActiveTemplate(editor) ?: return null
        var index = -1
        for ((i, variable) in template.variables.withIndex()) {
            if (variable.expression is MacroCallNode && (variable.expression as MacroCallNode).macro == this) {
                index = i
                break
            }
        }
        if (index == -1) {
            return null
        }
        var templateState = TemplateManagerImpl.getTemplateState(editor) ?: return null
        if (templateState.currentVariableNumber != index) {
            var isMatch = false
            if (templateState.currentVariableNumber + 1 == index) {
                val stackTrace = Thread.currentThread().stackTrace
                stackTrace.iterator().forEach { stackTraceElement ->
                    if (stackTraceElement.methodName == "nextTab") {
                        isMatch = true
                        return@forEach
                    }
                }
            }
            if (!isMatch) {
                return null
            }
        }

        // 获取当前正确的元素
        var element = expressionContext.psiElementAtStartOffset

        if (element != null) {
            element = element.containingFile.findElementAt(editor.caretModel.offset)
            if (element != null) {
                element = PsiTreeUtil.getParentOfType(element, LuaLocalDefStat::class.java)
            }
        }
        // 如果当前是定义的话，根据赋值后半部分来返回元素名
        if (element is LuaLocalDefStat) {
            val lastChild = element.exprList?.lastChild
            if (lastChild is LuaPsiTypeGuessable) {
                val e = PsiTreeUtil.getDeepestVisibleLast(lastChild)
                return getElementSuggestName(e, lastChild)
            }
        }
        return null
    }


    override fun calculateLookupItems(params: Array<out Expression>, context: ExpressionContext): Array<LookupElement>? {
        val list = mutableListOf<LookupElement>()
        val lastID = getLastID(params, context)
        if (lastID != null) {
            list.add(LookupElementBuilder.create(lastID))
        } else {
            val pin = context.psiElementAtStartOffset
            if (pin != null) LuaDeclarationTree.get(pin.containingFile).walkUpLocal(pin) {
                list.add(LookupElementBuilder.create(it.name))
            }
        }
        return list.toTypedArray()
    }

    companion object {
        fun getElementSuggestName(e: PsiElement?, element: PsiElement): String? {
            // 根据类型判断命名
            if (element is LuaPsiTypeGuessable) {
                val context = SearchContext.get(element.project)
//                val set = HashSet<String>()
//                LuaNameSuggestionProvider.GetSuggestedNames(element, set)
//                if (set.isNotEmpty())
//                {
//                    return set.elementAt(0)
//                }
                var type = element.guessType(context)
                val name = getElementSuggestNameByType(type, context)
                if (name != null && !LuaNameSuggestionProvider.isKeyword(name)) {
                    return name
                }
            }

            // 根据调用函数名
            if (element is LuaCallExpr && element.indexExpr != null) {
                val functionName = element.indexExpr!!.name
                if (functionName != null) {
                    val names = NameUtil.getSuggestionsByName(functionName, "", "", false, false, false)
                    if (names.isNotEmpty() && !LuaNameSuggestionProvider.isKeyword(names[0])) {
                        return names[0]
                    }
                }
            }

            var e1 = e
            var lastText: String? = null
            // 获取最后一个id或者字符
            while (e1 != null && e1.startOffset > element.startOffset && lastText == null) {
                val text = e1.text
                if (text != null) {
                    if (e1.elementType == LuaTypes.ID) {
                        lastText = text
                    } else if (e1.elementType == LuaTypes.STRING) {
                        val value = LuaString.getContent(text).value
                        lastText = value
                    }
                    e1 = PsiTreeUtil.prevVisibleLeaf(e1)
                }
            }
            // 如果是包含路径，可以判断一下是不是引用了

            if (lastText?.contains(".") == true) {
                if (element.text.contains("require(")) {
                    val file = resolveRequireFile(lastText, element.project)
                    if (file != null) {
                        val child = PsiTreeUtil.findChildOfType(file, LuaDocTagClass::class.java)
                        if (child != null && !LuaNameSuggestionProvider.isKeyword(child.name)) {
                            return child.name
                        }
                        val returnText = file.returnStatement()?.exprList?.text
                        // 过短也没有必要返回
                        if (returnText != null && returnText.length > 3 && !LuaNameSuggestionProvider.isKeyword(returnText)) {
                            return returnText
                        }
                    }
                }
                // .只包含最后一个名称
                lastText = lastText.replace(Regex(".*\\."), "")
            }
            if (LuaNameSuggestionProvider.isKeyword(lastText)) {
                return "var"
            }
            return lastText
        }

        // 根据类型判断文件名
        fun getElementSuggestNameByType(ity: ITy?, context: SearchContext): String? {
            var type = ity
            if (type is TyArray) {
                val name = getElementSuggestNameByType(type.base, context)
                if (name != null)
                    return name + "Arr"
            }
            if (type is TyClass) {
                val className = type.className
                if (!className.startsWith("table@")) {
                    return className.replace(Regex(".*\\."), "")
                } else {
                    val superType = type.getSuperType(context)
                    if (superType != null) {
                        type = superType
                    }
                }
            }
            if (type is TyTable) {
                val psi = type.psi
                val declaration = PsiTreeUtil.getParentOfType(psi, LuaDeclaration::class.java)
                if (declaration != null) {
                    if (declaration is LuaAssignStat) {
                        return PsiTreeUtil.getDeepestLast(declaration.varExprList.lastChild).text
                    } else if (declaration is LuaLocalDefStat) {
                        return PsiTreeUtil.getDeepestLast(declaration.localDefList.last()).text
                    }
                }
            }
            return null
        }
    }
}
